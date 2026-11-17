package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.MarkRead;
import cn.siyue.core.OpportunityNeedDtos.Notification;
import cn.siyue.core.OpportunityNeedDtos.NotificationPage;
import cn.siyue.core.OpportunityNeedDtos.NotificationQuery;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reminder reads and acknowledgements use current employee authorization and immutable retry facts.
 * @see OpportunityNotificationRepository
 * @see MutationRepository
 */
@Service
public class OpportunityNotificationService {
    private static final String ACTION = "crm.opportunity.notification.read";
    private final AccessRepository access;
    private final OpportunityNotificationRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** Injects actor locking, reminder persistence and retry tracking.
     * @param access repository used to reload and lock the active actor
     * @param repository scoped opportunity reminder persistence
     * @param mutations idempotency and audit persistence
     * @param mapper serializer for stable mutation request and response bodies
     */
    public OpportunityNotificationService(AccessRepository access, OpportunityNotificationRepository repository,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access; this.repository = repository; this.mutations = mutations; this.mapper = mapper;
    }

    /** Lists reminders with validated status and bounded pagination.
     * @param actor authenticated actor requesting reminders
     * @param query requested status and optional page settings; null selects defaults
     * @return reminders visible to the actor
     * @throws BusinessException if read permission, status, or pagination checks fail
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public NotificationPage list(Actor actor, NotificationQuery query) {
        CrmLegacyAccess.read(actor, "crm.opportunity.read");
        String status = query == null ? null : OpportunityNeedSupport.clean(query.status());
        if (status == null) { status = "unread"; }
        if (!Set.of("unread", "read", "all").contains(status)) {
            OpportunityNeedSupport.invalid("提醒筛选状态无效");
        }
        int page = OpportunityNeedSupport.page(query == null ? null : query.page());
        int size = OpportunityNeedSupport.size(query == null ? null : query.size());
        return repository.list(actor, new NotificationQuery(status, page, size));
    }

    /** Current account and recipient checks precede successful replay; mutable version checks follow it.
     * @param actor authenticated actor acknowledging the reminder
     * @param id public reminder identifier
     * @param key stable idempotency key
     * @param input expected reminder version
     * @return updated reminder or the prior result for an identical retry, redacted against current state
     * @throws JsonProcessingException if the request or receipt cannot be serialized
     * @throws BusinessException if permission, recipient scope, identifier, version, or replay checks fail
     */
    @Transactional(rollbackFor = Exception.class)
    public Notification markRead(Actor actor, String id, String key, MarkRead input)
            throws JsonProcessingException {
        CrmLegacyAccess.read(actor, "crm.opportunity.read"); OpportunityNeedSupport.key(key);
        if (id == null || id.isBlank() || id.length() > 120 || input == null || input.expectedVersion() < 1) {
            OpportunityNeedSupport.invalid("提醒编号或版本无效");
        }
        actor = access.lockActive(actor.id()); CrmLegacyAccess.read(actor, "crm.opportunity.read");
        Notification current = repository.get(actor, id, true);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(id, input)));
        String replay = mutations.replay(actor, key, ACTION, hash);
        if (replay != null) {
            return repository.redactReplay(mapper.readValue(replay, Notification.class), current);
        }
        Notification receipt = repository.markRead(actor, current, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, ACTION, hash,
            mapper.writeValueAsString(receipt), receipt.id()));
        return receipt;
    }
}
