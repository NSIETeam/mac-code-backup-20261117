package cn.siyue.core;

import cn.siyue.core.CrmDtos.Customer;
import cn.siyue.core.CrmDtos.CustomerContact;
import cn.siyue.core.CrmDtos.CustomerContactInput;
import cn.siyue.core.CrmDtos.CustomerInput;
import cn.siyue.core.CrmDtos.CustomerPage;
import cn.siyue.core.CrmDtos.CustomerStudent;
import cn.siyue.core.CrmDtos.CustomerStudentInput;
import cn.siyue.core.CrmDtos.Activity;
import cn.siyue.core.CrmDtos.ActivityInput;
import cn.siyue.core.CrmDtos.ActivityMembersInput;
import cn.siyue.core.CrmDtos.ActivityPage;
import cn.siyue.core.CrmDtos.ActivityVersion;
import cn.siyue.core.CrmDtos.Touchpoint;
import cn.siyue.core.CrmDtos.TouchpointInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRM customer identity transaction boundary; messaging providers are not invoked. */
@Service
public class CrmService {
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9 ()-]{7,32}");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}");
    private final AccessRepository access;
    private final CrmRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /**
     * Wires the scoped access, persistence, idempotency and JSON collaborators.
     * @param access actor resolution and active-principal access repository.
     * @param repository CRM persistence repository.
     * @param mutations idempotency and mutation-audit repository.
     * @param mapper JSON serializer used for request fingerprints and stored replay responses.
     */
    public CrmService(AccessRepository access, CrmRepository repository,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.repository = repository;
        this.mutations = mutations;
        this.mapper = mapper;
    }
    /**
     * Creates a household customer without merging identities that share a phone number.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The created customer or the stored result of an identical retry.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public Customer createCustomer(Actor actor, String key, CustomerInput input)
            throws JsonProcessingException {
        String action = "crm.customer.create";
        CrmLegacyAccess.operation(actor, "lead.create", "crm.customer.write");
        requireKey(key);
        validateContacts(input.contacts());
        actor = access.lockActive(actor.id());
        CrmLegacyAccess.operation(actor, "lead.create", "crm.customer.write");
        repository.authorizeCampus(actor, input.campusId());
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, Customer.class); }
        Customer customer = repository.createCustomer(actor, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(customer), customer.id()));
        return customer;
    }
    /**
     * Searches exact customer-name, email or phone indexes within the caller’s current scope.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param query optional exact-match query text.
     * @param page 1-based page number.
     * @param size maximum number of records in the page.
     * @return A bounded page of customers visible to the actor.
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CustomerPage customers(Actor actor, String query, int page, int size) {
        CrmLegacyAccess.read(actor, "crm.customer.read");
        if (page < 1 || page > 100000 || size < 1 || size > 100
                || (query != null && query.length() > 254)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "客户查询条件或分页范围无效");
        }
        return repository.customers(actor, query, page, size);
    }
    /** Executes an imported customer saved view after its resource and caller permissions are checked.
     * @param actor caller whose modern customer-read or exact legacy CRM read grant is required
     * @param savedView nonnull customer filters; search is limited to 200 characters
     * @param page one-based page from 1 through 100000
     * @param size requested page size from 1 through 100
     * @return scoped customer summaries and a total computed using the same saved-view predicate
     * @throws BusinessException if permissions, saved-view resource, search length or pagination are invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CustomerPage savedViewCustomers(Actor actor, LegacySavedViewQuery savedView, int page, int size) {
        CrmLegacyAccess.read(actor, "crm.customer.read");
        if (page < 1 || page > 100000 || size < 1 || size > 100 || savedView == null
                || savedView.search() != null && savedView.search().length() > 200) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "客户保存视图筛选或分页范围无效");
        }
        savedView.authorize(actor, "customers");
        return repository.customers(actor, savedView, page, size);
    }
    /**
     * Validates the typed search mode and its additional permission before running a scoped, bounded search.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param input validated request payload.
     * @return A bounded page of customers visible to the actor.
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CustomerPage customers(Actor actor, CustomerSearchDtos.Query input) {
        CrmLegacyAccess.read(actor, "crm.customer.read");
        if (input == null || !Set.of("auto", "name", "phone", "student_number", "student_id", "opportunity_id", "lead_id", "email", "customer_id").contains(input.searchType())
                || input.page() < 1 || input.page() > 100000 || input.size() < 1 || input.size() > 100
                || input.query() != null && input.query().length() > 254
                || !"auto".equals(input.searchType()) && (input.query() == null || input.query().isBlank())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "客户检索类型、内容或分页范围无效");
        }
        requireSearchPermission(actor, input.searchType());
        return "auto".equals(input.searchType()) ? repository.customers(actor, input.query(), input.page(), input.size())
            : repository.customers(actor, input);
    }
    private void requireSearchPermission(Actor actor, String type) {
        if ("opportunity_id".equals(type)) { CrmLegacyAccess.read(actor, "crm.opportunity.read"); }
        if ("lead_id".equals(type)) { CrmLegacyAccess.read(actor, "crm.lead.read"); }
        if (Set.of("student_id", "student_number").contains(type)
                && !actor.permissions().contains("crm.opportunity.intake")) { actor.require("student.read"); }
    }
    /**
     * Returns a scoped customer and includes family-student links only when student-read permission is present.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param customerId customer public identifier.
     * @return The created customer or the stored result of an identical retry.
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Customer customer(Actor actor, String customerId) {
        CrmLegacyAccess.read(actor, "crm.customer.read");
        return repository.customer(actor, customerId, actor.permissions().contains("student.read"));
    }
    /**
     * Appends a validated household contact using scope checks, an idempotency key and an audit record.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param customerId customer public identifier.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The appended contact or stored result of an identical retry.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public CustomerContact addContact(Actor actor, String customerId, String key,
            CustomerContactInput input) throws JsonProcessingException {
        String action = "crm.customer.contact.add";
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.customer.write");
        requireKey(key);
        validateContacts(List.of(input));
        actor = access.lockActive(actor.id());
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.customer.write");
        repository.authorizeContactAppend(actor, customerId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(customerId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, CustomerContact.class); }
        CustomerContact contact = repository.addContact(actor, customerId, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(contact), contact.id()));
        return contact;
    }
    /**
     * Links an already visible student to a customer without merging their identities.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param customerId customer public identifier.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The customer-student relation or stored result of an identical retry.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public CustomerStudent linkStudent(Actor actor, String customerId, String key,
            CustomerStudentInput input) throws JsonProcessingException {
        String action = "crm.customer.student.link";
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.customer.write");
        actor.require("student.read");
        requireKey(key);
        actor = access.lockActive(actor.id());
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.customer.write");
        actor.require("student.read");
        repository.authorizeStudentLink(actor, customerId, input.studentId());
        String hash = mutations.hash(mapper.writeValueAsString(List.of(customerId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, CustomerStudent.class); }
        CustomerStudent relation = repository.linkStudent(actor, customerId, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(relation), relation.linkId()));
        return relation;
    }
    /**
     * Creates a campus-scoped activity and its explicit participant and promoter memberships.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The resulting activity or stored result of an identical retry.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public Activity createActivity(Actor actor, String key, ActivityInput input)
            throws JsonProcessingException {
        String action = "crm.activity.create";
        actor.require("crm.activity.write");
        requireKey(key);
        validateActivity(input);
        actor = access.lockActive(actor.id());
        actor.require("crm.activity.write");
        repository.authorizeCampus(actor, input.campusId());
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, Activity.class); }
        Activity activity = repository.createActivity(actor, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(activity), activity.id()));
        return activity;
    }
    /**
     * Returns a bounded activity page filtered by the supplied view, type, status, campus and query.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param filters activity search filters, including scope and pagination.
     * @return A bounded page of visible activities.
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public ActivityPage activities(Actor actor,CrmActivityQuery filters) {
        CrmLegacyAccess.read(actor, "crm.activity.read");
        int page=filters.page(),size=filters.size();
        String view=filters.view(),activityType=filters.activityType(),status=filters.status(),query=filters.query();
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "活动分页范围无效");
        }
        if (!Set.of("scope", "created", "participating").contains(view)
                || activityType != null && !Set.of("event", "campaign", "referral", "other").contains(activityType)
                || status != null && !Set.of("active", "closed").contains(status)
                || query != null && query.length() > 120) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "活动筛选条件无效");
        }
        return repository.activities(actor,new CrmActivityQuery(filters.campusId(),view,activityType,status,
            query == null ? null : query.trim(),page,size));
    }
    /**
     * Replaces participant and promoter memberships only at the expected activity version; identical retries replay.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param activityId value supplied to the method.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The resulting activity or stored result of an identical retry.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public Activity updateActivityMembers(Actor actor, String activityId, String key,
            ActivityMembersInput input) throws JsonProcessingException {
        String action = "crm.activity.members.replace";
        actor.require("crm.activity.write");
        requireKey(key);
        validateActivityMembers(input);
        actor = access.lockActive(actor.id());
        actor.require("crm.activity.write");
        repository.authorizeActivity(actor, activityId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(activityId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, Activity.class); }
        Activity activity = repository.replaceActivityMembers(actor, activityId, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(activity), activity.id()));
        return activity;
    }
    /**
     * Closes an authorized activity only at the expected version; identical retries replay.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param activityId value supplied to the method.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The resulting activity or stored result of an identical retry.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public Activity closeActivity(Actor actor, String activityId, String key, ActivityVersion input)
            throws JsonProcessingException {
        String action = "crm.activity.close";
        CrmLegacyAccess.operation(actor, "lead.create", "crm.activity.write");
        requireKey(key);
        actor = access.lockActive(actor.id());
        CrmLegacyAccess.operation(actor, "lead.create", "crm.activity.write");
        repository.authorizeActivity(actor, activityId);
        repository.authorizeLegacyCampaign(actor,activityId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(activityId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, Activity.class); }
        Activity activity = repository.closeActivity(actor, activityId, input.expectedVersion());
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(activity), activity.id()));
        return activity;
    }
    /**
     * Appends an encrypted customer interaction after checking touchpoint and related-object permissions.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param customerId customer public identifier.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The appended touchpoint or stored result of an identical retry.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public Touchpoint createTouchpoint(Actor actor, String customerId, String key, TouchpointInput input)
            throws JsonProcessingException {
        String action = "crm.customer.touchpoint.create";
        requireTouchpointPermissions(actor,input);
        requireKey(key);
        validateTouchpoint(input);
        actor = access.lockActive(actor.id());
        requireTouchpointPermissions(actor,input);
        repository.authorizeTouchpoint(actor, customerId, input);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(customerId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, Touchpoint.class); }
        Touchpoint touchpoint = repository.appendTouchpoint(actor, customerId, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(touchpoint), touchpoint.id()));
        return touchpoint;
    }
    /**
     * Returns the latest touchpoints visible under both customer and touchpoint-read permissions.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param customerId customer public identifier.
     * @return the operation result.
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<Touchpoint> touchpoints(Actor actor, String customerId) {
        CrmLegacyAccess.read(actor, "crm.customer.read");
        CrmLegacyAccess.read(actor, "crm.touchpoint.read");
        return repository.touchpoints(actor, customerId);
    }
    private void validateActivity(ActivityInput input) {
        if (input == null || input.campusId() == null || input.title() == null || input.title().isBlank()
                || input.activityType() == null || input.description() != null && input.description().length() > 2000
                || !Set.of("event", "campaign", "referral", "other").contains(input.activityType())
                || input.startsAt() == null || input.endsAt() == null || !input.endsAt().isAfter(input.startsAt())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "活动结束时间必须晚于开始时间");
        }
        rejectDuplicateIds(input.participantIds());
        rejectDuplicateIds(input.promoterIds());
    }
    private void rejectDuplicateIds(List<String> ids) {
        if (ids != null && (ids.size() > 200 || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 120))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "活动成员编号无效或重复");
        }
    }
    private void validateActivityMembers(ActivityMembersInput input) {
        if (input == null || input.expectedVersion() < 1 || input.participantIds() == null
                || input.promoterIds() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "活动成员及版本必须完整提交");
        }
        rejectDuplicateIds(input.participantIds());
        rejectDuplicateIds(input.promoterIds());
    }
    private void validateContacts(List<CustomerContactInput> contacts) {
        if (contacts == null) { return; }
        Set<String> primaryChannels = new HashSet<>();
        Set<String> exactValues = new HashSet<>();
        for (CustomerContactInput contact : contacts) validateContact(contact,primaryChannels,exactValues);
    }
    private void validateContact(CustomerContactInput contact,Set<String> primaryChannels,Set<String> exactValues){
        if (contact == null || contact.channel() == null || contact.value() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "联系方式不能为空");
        }
        String channel = contact.channel();
        validateContactKind(contact,channel);
        validateContactFormat(contact,channel);
        if (contact.primary() && !primaryChannels.add(channel)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "每种联系方式最多设置一个主要联系人方式");
        }
        String normalized = channel.equals("phone")
            ? contact.value().trim().replaceAll("[\\s()\\-]", "")
            : contact.value().trim().toLowerCase(java.util.Locale.ROOT);
        if (!exactValues.add(channel + ":" + normalized)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "同一客户档案不能重复录入相同联系方式");
        }
    }
    private void validateContactKind(CustomerContactInput contact,String channel){
        if (!Set.of("phone", "email").contains(channel)
                || !Set.of("guardian", "student", "other").contains(contact.personRole())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "联系方式类型或关联人员类型无效");
        }
    }
    private void validateContactFormat(CustomerContactInput contact,String channel){
        if (channel.equals("phone") && !PHONE.matcher(contact.value()).matches()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "手机号格式无效");
        }
        if (channel.equals("email") && !EMAIL.matcher(contact.value()).matches()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "邮箱格式无效");
        }
    }
    private void requireTouchpointPermissions(Actor actor,TouchpointInput input){
        CrmLegacyAccess.operation(actor, "lead.create", "crm.touchpoint.write", "crm.customer.read");
        if (input.activityId() != null && !input.activityId().isBlank()) { CrmLegacyAccess.read(actor, "crm.activity.read"); }
        if (input.studentId() != null && !input.studentId().isBlank()) { actor.require("student.read"); }
    }
    private void validateTouchpoint(TouchpointInput input){
        if (input.source() == null || !Set.of("front_desk", "online_form", "phone", "wecom", "referral", "import", "other").contains(input.source())
                || input.channel() == null || !Set.of("phone", "wecom", "text", "in_person", "form", "import").contains(input.channel())
                || input.summary() == null || input.summary().isBlank() || input.summary().length() > 2000) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "触点来源、渠道或内容无效");
        }
        if (input.occurredAt() == null || input.occurredAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "触点发生时间不能晚于当前时间");
        }
    }
    private void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请提供有效幂等键");
        }
    }
}
