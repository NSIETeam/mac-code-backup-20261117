package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** The intake route returns minimal evidence; full opportunity access remains separately checked. */
@RestController
@RequestMapping("/api/v2/crm/opportunity-needs")
@Validated
public class OpportunityNeedController {
    private final AccessRepository access;
    private final OpportunityNeedService service;
    private final OpportunityNeedReadRepository reads;
    private final OpportunityConfigRepository config;
    private final OpportunityAssignmentService assignment;
    /**
     * 连接需求录入最小证据、需求详情及分配入口，完整商机读取仍单独授权。
     * @param access 可信 issuer/subject 的员工绑定解析器
     * @param service 需求预览、提交、档案及判断的应用服务
     * @param reads 客户最小证据与商机范围查询仓储
     * @param config 按客户或商机校区返回当前需求配置
     * @param assignment 接收人资格查询及版本化分配服务
     */
    public OpportunityNeedController(AccessRepository access,OpportunityNeedService service,OpportunityNeedReadRepository reads,
            OpportunityConfigRepository config,OpportunityAssignmentService assignment) {
        this.access=access; this.service=service; this.reads=reads; this.config=config; this.assignment=assignment;
    }
    /**
     * 按需求录入许可和客户读取关系提供登记所需最小上下文。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param id 客户公开标识
     * @return 客户身份、版本、校区及脱敏联系方式
     * @throws BusinessException 录入权限或客户读取范围无效时
     */
    @GetMapping("/customers/{id}/context")
    public ApiResponse<Context> context(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id) {
        return ApiResponse.success(service.context(actor(jwt),id));
    }
    /**
     * 读取客户有效关联孩子，使用客户关系范围，不授予一般学员档案访问。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param id 客户公开标识
     * @param query 页码及每页数量；服务端限制最大 100 条
     * @return 去重的关联孩子身份、年级和关系页
     * @throws BusinessException 录入权限、客户范围或分页无效时
     */
    @GetMapping("/customers/{id}/students")
    public ApiResponse<StudentPage> students(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,@ModelAttribute PageQuery query) {
        return ApiResponse.success(service.students(actor(jwt),id,query));
    }
    /**
     * 列出客户既有需求最小证据，并分别标记当前是否允许查看完整商机。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param id 客户公开标识
     * @param query 有界分页条件
     * @return 需求候选页；无商机读取资格时不披露负责人及阶段
     * @throws BusinessException 录入权限、客户范围或分页无效时
     */
    @GetMapping("/customers/{id}/existing")
    public ApiResponse<CandidatePage> existing(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,@ModelAttribute PageQuery query) {
        return ApiResponse.success(service.existing(actor(jwt),id,query));
    }
    /**
     * 要求原 lead.follow 或现代录入与客户读取组合，按客户校区返回可选需求配置。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param customerId 当前客户读取范围内的公开标识
     * @return 该校区的部门、项目、学科、学期及字段配置
     * @throws BusinessException 录入权限或客户范围无效时
     */
    @GetMapping("/options")
    public ApiResponse<Options> options(@AuthenticationPrincipal Jwt jwt,@RequestParam @Size(max=120) String customerId) {
        Actor actor=actor(jwt); OpportunityNeedSupport.intake(actor);
        return ApiResponse.success(config.options(reads.customer(actor,customerId,false).campusId()));
    }
    /**
     * 根据当前可读商机的校区读取需求编辑选项。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param id 路由中的商机公开标识
     * @return 商机校区适用的需求配置
     * @throws BusinessException 商机读取权限或范围无效时
     */
    @GetMapping("/{id}/options")
    public ApiResponse<Options> optionsFor(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id) {
        return ApiResponse.success(service.optionsFor(actor(jwt),id));
    }
    /**
     * 核验客户、学生、来源和需求字段并查找匹配需求，不创建商机。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param input 需求客户、关联孩子、业务身份、档案、来源及提交模式
     * @return 当前匹配候选、版本化来源证据和提交所需预览
     * @throws BusinessException 录入权限、范围、配置或需求字段校验失败时
     */
    @PostMapping("/preview")
    public ApiResponse<Preview> preview(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Input input) {
        return ApiResponse.success(service.preview(actor(jwt),input));
    }
    /**
     * 锁后复核预览来源与身份，原子创建或复用需求并追加咨询证据。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 原需求输入及用户确认的预览版本证据
     * @return 需求提交最小回执；重放仍按当前权限隐藏不可见详情
     * @throws BusinessException 录入权限、来源范围、预览过期、需求配置或幂等校验失败时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PostMapping("/commit-preview")
    public ApiResponse<Receipt> commit(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Commit input) throws JsonProcessingException {
        return ApiResponse.success(service.commit(actor(jwt),key,input));
    }
    /**
     * 读取当前商机范围内的需求身份、档案及最近判断。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param id 路由中的商机公开标识
     * @return 带当前授权商机投影的需求详情
     * @throws BusinessException 商机读取权限或范围无效时
     */
    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id) {
        return ApiResponse.success(service.detail(actor(jwt),id));
    }
    /**
     * 在锁后写权限复核及期望版本通过后更新需求档案，不改变固定需求身份。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param id 路由中的商机公开标识
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 需求档案字段及期望版本
     * @return 更新后的需求详情或按当前披露规则投影的原回执
     * @throws BusinessException 原 lead.follow 或现代商机写入/读取组合、范围、字段、版本或幂等校验失败时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PutMapping("/{id}/profile")
    public ApiResponse<Detail> profile(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ProfileUpdate input) throws JsonProcessingException {
        return ApiResponse.success(service.profile(actor(jwt),id,key,input));
    }
    /**
     * 记录需求判断快照并保留历史，办理前复核写权限和商机范围。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param id 路由中的商机公开标识
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 判断内容和期望版本
     * @return 需求详情及最新判断，或当前授权投影的原回执
     * @throws BusinessException 写入/读取权限、范围、判断内容、版本或幂等校验失败时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PostMapping("/{id}/judgments")
    public ApiResponse<Detail> judge(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody JudgmentInput input) throws JsonProcessingException {
        return ApiResponse.success(service.judge(actor(jwt),id,key,input));
    }
    /**
     * 按客户或商机校区列出有效且可接收分配的员工。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param query customerId/opportunityId 二选一及有界分页
     * @return 在分配范围内的合格员工页
     * @throws BusinessException 分配许可、对象读取或录入许可、选择器、范围或分页无效时
     */
    @GetMapping("/assignees")
    public ApiResponse<AssigneePage> assignees(@AuthenticationPrincipal Jwt jwt,@Valid @ModelAttribute AssigneeQuery query) {
        return ApiResponse.success(assignment.list(actor(jwt),query));
    }
    /**
     * 按期望版本分配商机；移出本人范围时仅返回受控分配回执。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param id 路由中的商机公开标识
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 合格负责人公开标识、原因及期望版本
     * @return 负责人、递增版本及 visible/left_scope 状态；重放重新投影当前可见性
     * @throws BusinessException 分配/写入权限、商机范围、接收人资格、原因、版本或幂等校验失败时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PutMapping("/{id}/assignment")
    public ApiResponse<AssignmentReceipt> assign(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Assignment input) throws JsonProcessingException {
        return ApiResponse.success(assignment.assign(actor(jwt),id,key,input));
    }
    /**
     * 读取当前可见需求的咨询事件历史。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param id 路由中的商机公开标识
     * @param query 有界分页条件
     * @return 按最新优先排列的咨询事件页，含原操作人及时间
     * @throws BusinessException 商机读取权限、范围或分页无效时
     */
    @GetMapping("/{id}/consultations")
    public ApiResponse<ConsultationPage> consultations(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,@ModelAttribute PageQuery query) {
        return ApiResponse.success(service.consultations(actor(jwt),id,query));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(),jwt.getSubject()); }
}
