package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.MarkRead;
import cn.siyue.core.OpportunityNeedDtos.Notification;
import cn.siyue.core.OpportunityNeedDtos.NotificationPage;
import cn.siyue.core.OpportunityNeedDtos.NotificationQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpportunityNotificationServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final OpportunityNotificationRepository repository = mock(OpportunityNotificationRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final OpportunityNotificationService service = new OpportunityNotificationService(access, repository, mutations, mapper);
    private final Actor reader = new Actor(7, "EMP_7", "own", Set.of(), Set.of("crm.opportunity.read"));
    private final String key = "notification_read_key_1";
    private final String action = "crm.opportunity.notification.read";

    private Notification notification(long version, String visibility) {
        return new Notification("NTF_1", visibility.equals("visible") ? "OPP_1" : null,
            visibility.equals("visible") ? "NEVT_1" : null, "repeat_consultation", Instant.EPOCH,
            version > 1 ? Instant.parse("2026-10-14T01:02:03.678Z") : null, version, visibility);
    }

    @Test void listsBoundedUnreadDefaultsWithoutCustomerOrAccountManagementPermissions() {
        var query = new NotificationQuery("unread", 1, 20);
        var page = new NotificationPage(List.of(notification(1, "restricted")), 1, 1, 20);
        when(repository.list(reader, query)).thenReturn(page);
        assertSame(page, service.list(reader, null));
        assertSame(page, service.list(reader, new NotificationQuery(null, null, null)));
        verifyNoInteractions(access, mutations);
    }

    @Test void invalidFiltersOrMissingSalesReadNeverQueryNotifications() {
        Actor adminOnly = new Actor(8, "ADMIN", "all", Set.of(), Set.of("system.access.manage"));
        assertThrows(BusinessException.class, () -> service.list(adminOnly, null));
        for (var query : List.of(new NotificationQuery("bad", 1, 20),
                new NotificationQuery("all", 0, 20), new NotificationQuery("read", 1, 101))) {
            assertThrows(BusinessException.class, () -> service.list(reader, query));
        }
        verifyNoInteractions(repository);
    }

    @Test void firstReadLocksCurrentRecipientThenPersistsOneReceipt() throws Exception {
        var input = new MarkRead(1); var current = notification(1, "visible"); var receipt = notification(2, "visible");
        when(access.lockActive(reader.id())).thenReturn(reader);
        when(repository.get(reader, "NTF_1", true)).thenReturn(current);
        when(mutations.hash(mapper.writeValueAsString(List.of("NTF_1", input)))).thenReturn("hash");
        when(repository.markRead(reader, current, input)).thenReturn(receipt);
        assertSame(receipt, service.markRead(reader, "NTF_1", key, input));
        var order = inOrder(access, repository, mutations);
        order.verify(access).lockActive(reader.id()); order.verify(repository).get(reader, "NTF_1", true);
        order.verify(mutations).hash(anyString()); order.verify(mutations).replay(reader, key, action, "hash");
        order.verify(repository).markRead(reader, current, input);
        order.verify(mutations).complete(eq(reader), argThat(write -> write.resourceId().equals("NTF_1")
            && write.action().equals(action) && write.key().equals(key)));
    }

    @Test void successfulReplayChecksRecipientAndRedactsUnderCurrentScopeWithoutVersionRecheck() throws Exception {
        var current = notification(9, "restricted"); var receipt = notification(2, "visible");
        var masked = notification(2, "restricted");
        when(access.lockActive(reader.id())).thenReturn(reader);
        when(repository.get(reader, "NTF_1", true)).thenReturn(current);
        when(mutations.hash(anyString())).thenReturn("hash");
        when(mutations.replay(reader, key, action, "hash")).thenReturn(mapper.writeValueAsString(receipt));
        when(repository.redactReplay(receipt, current)).thenReturn(masked);
        assertSame(masked, service.markRead(reader, "NTF_1", key, new MarkRead(1)));
        var order = inOrder(repository, mutations);
        order.verify(repository).get(reader, "NTF_1", true); order.verify(mutations).hash(anyString());
        order.verify(mutations).replay(reader, key, action, "hash");
        order.verify(repository).redactReplay(receipt, current);
        verify(repository, never()).markRead(any(), any(), any()); verify(mutations, never()).complete(any(), any());
    }

    @Test void revokedPermissionIsRecheckedBeforeOwnershipOrReplay() {
        when(access.lockActive(reader.id())).thenReturn(new Actor(7, "EMP_7", "own", Set.of(), Set.of()));
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class,
            () -> service.markRead(reader, "NTF_1", key, new MarkRead(1))).code());
        verifyNoInteractions(repository, mutations);
    }

    @Test void otherRecipientOrDisabledAccountCannotReplaySavedNotifications() {
        when(access.lockActive(reader.id())).thenReturn(reader);
        when(repository.get(reader, "NTF_1", true)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "missing"));
        assertThrows(BusinessException.class, () -> service.markRead(reader, "NTF_1", key, new MarkRead(1)));
        verifyNoInteractions(mutations);
        reset(repository);
        when(access.lockActive(reader.id())).thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "disabled"));
        assertThrows(BusinessException.class, () -> service.markRead(reader, "NTF_1", key, new MarkRead(1)));
        verifyNoInteractions(repository, mutations);
    }

    @Test void differentBodySameKeyDoesNotTouchReadState() {
        when(access.lockActive(reader.id())).thenReturn(reader);
        when(repository.get(reader, "NTF_1", true)).thenReturn(notification(2, "visible"));
        when(mutations.hash(anyString())).thenReturn("different");
        when(mutations.replay(reader, key, action, "different"))
            .thenThrow(new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "conflict"));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, assertThrows(BusinessException.class,
            () -> service.markRead(reader, "NTF_1", key, new MarkRead(2))).code());
        verify(repository, never()).markRead(any(), any(), any()); verify(mutations, never()).complete(any(), any());
    }

    @Test void invalidInputAndUnprivilegedActorFailBeforeAccountLock() {
        assertThrows(BusinessException.class, () -> service.markRead(reader, "NTF_1", "bad", new MarkRead(1)));
        assertThrows(BusinessException.class, () -> service.markRead(reader, "NTF_1", key, new MarkRead(0)));
        assertThrows(BusinessException.class, () -> service.markRead(reader, " ", key, new MarkRead(1)));
        assertThrows(BusinessException.class, () -> service.markRead(reader, "NTF_1", key, null));
        Actor none = new Actor(8, "EMP_8", "all", Set.of(), Set.of("crm.customer.read"));
        assertThrows(BusinessException.class, () -> service.markRead(none, "NTF_1", key, new MarkRead(1)));
        verifyNoInteractions(access, repository, mutations);
    }
}
