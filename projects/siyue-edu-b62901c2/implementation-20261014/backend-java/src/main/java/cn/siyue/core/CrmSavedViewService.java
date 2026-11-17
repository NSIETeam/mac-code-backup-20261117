package cn.siyue.core;

import cn.siyue.core.CrmSavedViewDtos.Create;
import cn.siyue.core.CrmSavedViewDtos.Delete;
import cn.siyue.core.CrmSavedViewDtos.Update;
import cn.siyue.core.CrmSavedViewDtos.View;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Collections;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validates and audits private CRM queue views against each queue's permissions. */
@Service
public class CrmSavedViewService {
    private static final Map<String, String> PERMISSIONS = Map.of("leads", "crm.lead.read",
        "tasks", "crm.task.read", "taskBatches", "crm.task.read",
        "opportunities", "crm.opportunity.read", "serviceTickets", "crm.ticket.read",
        "customers", "crm.customer.read");
    private static final Map<String, Set<String>> FILTERS = Map.of("leads",
        Set.of("status", "customerId", "activityId"), "tasks", Set.of("status", "taskType"),
        "taskBatches", Set.of("status", "taskType"), "opportunities", Set.of("stage", "customerId"),
        "serviceTickets", Set.of("status"), "customers", Set.of("searchType", "query"));
    private static final Set<String> LEAD_STATUSES = Set.of("new", "contacting", "qualified", "no_need",
        "unreachable", "invalid_contact", "archived");
    private static final Set<String> TASK_STATUSES = Set.of("pending", "in_progress", "completed", "cancelled");
    private final AccessRepository access;
    private final CrmSavedViewRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** 装配个人保存筛选的身份锁、持久化与幂等依赖。
     * @param access 当前身份状态和权限锁读取器
     * @param repository 个人视图及每类队列数量、名称唯一性持久化仓库
     * @param mutations 幂等回执和审计仓库
     * @param mapper 请求摘要及重放结果 JSON 编解码器
     */
    public CrmSavedViewService(AccessRepository access, CrmSavedViewRepository repository,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.repository = repository;
        this.mutations = mutations;
        this.mapper = mapper;
    }
    /** 列出调用者在一个获授权队列中的个人保存筛选。
     * @param actor 具备对应队列读取权限的身份
     * @param resourceType leads、tasks、taskBatches、opportunities、serviceTickets 或 customers
     * @return 当前员工该队列未删除的视图，最多 20 项
     * @throws BusinessException 队列类型未知或缺少对应读取权限时
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<View> list(Actor actor, String resourceType) {
        requireResource(actor, resourceType);
        return repository.list(actor, resourceType);
    }
    /** 锁定当前身份后创建个人视图，串行检查每员工每类队列的 20 项上限。
     * @param actor 具有目标队列读取权限的身份，写入前重新核验
     * @param key 16 至 128 位允许字母数字、点、下划线、冒号和短横线的幂等键
     * @param input 队列类型、最多 80 字符的名称和该队列允许的筛选键值
     * @return 新建视图或相同请求的原始回执
     * @throws BusinessException 权限、输入、配额、名称唯一性或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或回执 JSON 编解码失败时
     */
    @Transactional(rollbackFor = Exception.class)
    public View create(Actor actor, String key, Create input) throws JsonProcessingException {
        requireResource(actor, input.resourceType());
        requireKey(key);
        String name = validName(input.name());
        Map<String, String> filters = validFilters(input.resourceType(), input.filters());
        actor = access.lockActive(actor.id());
        requireResource(actor, input.resourceType());
        String hash = mutations.hash(mapper.writeValueAsString(List.of(input.resourceType(), name, filters)));
        String replay = mutations.replay(actor, key, "crm.saved-view.create", hash);
        if (replay != null) { return mapper.readValue(replay, View.class); }
        View view = repository.create(actor, input.resourceType(), name, filters);
        complete(actor, key, "crm.saved-view.create", hash, view);
        return view;
    }
    /** 按拥有者和版本更新个人视图，原子记录变更回执及审计。
     * @param actor 视图所属员工，仍需具备对应队列读取权限
     * @param id 当前员工拥有的视图公开编号
     * @param key 16 至 128 位允许字母数字、点、下划线、冒号和短横线的幂等键
     * @param input 新名称、筛选键值及期望版本
     * @return 更新后的视图或相同请求原始回执
     * @throws BusinessException 身份、所有权、权限、筛选、名称、版本或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或回执 JSON 编解码失败时
     */
    @Transactional(rollbackFor = Exception.class)
    public View update(Actor actor, String id, String key, Update input) throws JsonProcessingException {
        requireKey(key);
        actor = access.lockActive(actor.id());
        View current = repository.get(actor, id);
        requireResource(actor, current.resourceType());
        String name = validName(input.name());
        Map<String, String> filters = validFilters(current.resourceType(), input.filters());
        String hash = mutations.hash(mapper.writeValueAsString(List.of(id, name, filters, input.expectedVersion())));
        String replay = mutations.replay(actor, key, "crm.saved-view.update", hash);
        if (replay != null) { return mapper.readValue(replay, View.class); }
        View view = repository.update(actor, id, name, filters, input.expectedVersion());
        complete(actor, key, "crm.saved-view.update", hash, view);
        return view;
    }
    /** 通过所有权、权限和版本检查后逻辑删除个人视图。
     * @param actor 视图所属员工，仍需具备对应队列读取权限
     * @param id 当前员工拥有的视图公开编号
     * @param key 16 至 128 位允许字母数字、点、下划线、冒号和短横线的幂等键
     * @param input 待删除视图的期望版本
     * @return 删除的视图编号，或相同请求已保存的编号
     * @throws BusinessException 身份、所有权、权限、版本或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或回执 JSON 编解码失败时
     */
    @Transactional(rollbackFor = Exception.class)
    public String delete(Actor actor, String id, String key, Delete input) throws JsonProcessingException {
        requireKey(key);
        actor = access.lockActive(actor.id());
        View current = repository.get(actor, id);
        requireResource(actor, current.resourceType());
        String hash = mutations.hash(mapper.writeValueAsString(List.of(id, input.expectedVersion())));
        String replay = mutations.replay(actor, key, "crm.saved-view.delete", hash);
        if (replay != null) { return mapper.readValue(replay, String.class); }
        String deleted = repository.delete(actor, id, input.expectedVersion());
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "crm.saved-view.delete", hash,
            mapper.writeValueAsString(deleted), deleted));
        return deleted;
    }
    private void requireResource(Actor actor, String resourceType) {
        String permission = PERMISSIONS.get(resourceType);
        if (permission == null) { invalid("队列类型无效"); }
        if ("leads".equals(resourceType) && actor.permissions().contains("lead.read")) { return; }
        actor.require(permission);
    }
    private Map<String, String> validFilters(String resourceType, Map<String, String> filters) {
        if (filters == null || filters.size() > 8 || !FILTERS.containsKey(resourceType)
                || !FILTERS.get(resourceType).containsAll(filters.keySet())) {
            invalid("筛选字段不属于所选队列或超过限制");
        }
        Map<String, String> clean = new TreeMap<>();
        filters.forEach((key, value) -> {
            if (key == null || value == null || value.isBlank() || value.length() > 120
                    || value.codePoints().anyMatch(Character::isISOControl)
                    || !validFilterValue(resourceType, key, value)) {
                invalid("筛选值无效");
            }
            clean.put(key, value.trim());
        });
        return Collections.unmodifiableMap(clean);
    }
    private boolean validFilterValue(String resourceType, String key, String value) {
        if (resourceType.equals("customers")) {
            return key.equals("query") || key.equals("searchType")
                && Set.of("auto", "name", "phone", "student_number", "student_id", "opportunity_id",
                    "lead_id", "email", "customer_id").contains(value);
        }
        if (key.equals("customerId") || key.equals("activityId")) {
            return value.matches("[A-Za-z0-9_-]{1,120}");
        }
        return validQueueFilterValue(resourceType, key, value);
    }
    private boolean validQueueFilterValue(String resourceType, String key, String value) {
        if (resourceType.equals("leads") && key.equals("status")) { return LEAD_STATUSES.contains(value); }
        if (resourceType.equals("tasks") && key.equals("status")) { return TASK_STATUSES.contains(value); }
        if (resourceType.equals("taskBatches") && key.equals("status")) {
            return Set.of("active", "completed", "cancelled").contains(value);
        }
        if (resourceType.equals("tasks") || resourceType.equals("taskBatches")) {
            return Set.of("marketing", "service").contains(value);
        }
        if (resourceType.equals("opportunities") && key.equals("stage")) {
            return Set.of("qualified", "appointment", "trial", "negotiation", "won", "closed").contains(value);
        }
        if (resourceType.equals("serviceTickets") && key.equals("status")) {
            return Set.of("open", "processing", "closed").contains(value);
        }
        return false;
    }
    private String validName(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 80
                || value.codePoints().anyMatch(Character::isISOControl)) { invalid("视图名称无效"); }
        return value.trim();
    }
    private void complete(Actor actor, String key, String action, String hash, View view)
            throws JsonProcessingException {
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(view), view.id()));
    }
    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) { invalid("Idempotency-Key 格式无效"); }
    }
    private static void invalid(String message) {
        throw new BusinessException(ErrorCode.INVALID_INPUT, message);
    }
}
