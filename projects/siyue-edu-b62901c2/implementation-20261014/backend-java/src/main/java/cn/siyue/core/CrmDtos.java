package cn.siyue.core;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import cn.siyue.core.AcademicDtos.StudentInput;

/** CRM客户、活动、获客、商机、财务与任务的请求和回执契约；存储加密由仓储负责，读取联系人使用脱敏投影。 */
public final class CrmDtos {
    private CrmDtos() { }
    /** 客户联系人的角色、渠道、原始联系值及主联系人标记；加密与重复身份判定在写入服务执行。 */
    public record CustomerContactInput(
            @NotBlank @Pattern(regexp = "guardian|student|other") String personRole,
            @NotBlank @Pattern(regexp = "phone|email") String channel,
            @NotBlank @Size(max = 254) String value, boolean primary) { }
    /** 创建客户的校区、显示名称和最多20个联系人，不把客户家庭身份等同学员身份。 */
    public record CustomerInput(@NotBlank @Size(max = 120) String campusId,
            @NotBlank @Size(max = 120) String displayName,
            @Size(max = 20) List<@Valid CustomerContactInput> contacts) { }
    /** 将既有学员关联到客户家庭的请求，关系限定为子女、监护、自身或其他。 */
    public record CustomerStudentInput(@NotBlank @Size(max = 120) String studentId,
            @NotBlank @Pattern(regexp = "child|ward|self|other") String relationship) { }
    /** 新建学员并关联到客户家庭的输入；创建和关联在业务服务的同一事务完成。 */
    public record CustomerStudentIntakeInput(@NotNull @Valid StudentInput student,
            @NotBlank @Pattern(regexp = "child|ward|self|other") String relationship) { }
    /** 客户联系人读取投影，仅包含脱敏联系方式及角色、渠道和主联系人标记。 */
    public record CustomerContact(String id, String personRole, String channel,
            String valueMasked, boolean primary) { }
    /** 客户家庭与学员的关联摘要，区分关联公开标识与学员公开标识。 */
    public record CustomerStudent(String linkId, String studentId, String name,
            String grade, String relationship) { }
    /** 客户列表摘要，携带校区、当前负责人、显示名称和行版本。 */
    public record CustomerSummary(String id, String campusId, String ownerId,
            String displayName, long version) { }
    /** 客户详情及其脱敏联系人、关联学员集合；访问范围由读取服务校验。 */
    public record Customer(String id, String campusId, String ownerId, String displayName,
            long version, List<CustomerContact> contacts, List<CustomerStudent> students) { }
    /** 客户摘要分页，包含同一筛选与权限范围的总数。 */
    public record CustomerPage(List<CustomerSummary> items, long total, int page, int size) { }
    /** 活动创建输入，包含所属校区、类型、起止时间及参与者/推广者成员集合。 */
    public record ActivityInput(@NotBlank @Size(max = 120) String campusId,
            @NotBlank @Size(max = 120) String title,
            @NotBlank @Pattern(regexp = "event|campaign|referral|other") String activityType,
            @NotNull java.time.Instant startsAt, @NotNull java.time.Instant endsAt,
            @Size(max = 2000) String description,
            @Size(max = 200) List<@NotBlank @Size(max = 120) String> participantIds,
            @Size(max = 200) List<@NotBlank @Size(max = 120) String> promoterIds) { }
    /** 活动详情的当前状态、成员集合和行版本；推广活动的发布操作使用独立契约。 */
    public record Activity(String id, String campusId, String ownerId, String title,
            String activityType, String status, java.time.Instant startsAt, java.time.Instant endsAt,
            String description, long version, List<String> participantIds, List<String> promoterIds) { }
    /** 授权范围内的活动分页及总数。 */
    public record ActivityPage(List<Activity> items, long total, int page, int size) { }
    /** 活动状态转换的预期行版本，最小值为1。 */
    public record ActivityVersion(@Min(1) long expectedVersion) { }
    /** 完整替换活动参与者与推广者的成员列表及预期版本；每类最多200人。 */
    public record ActivityMembersInput(@NotNull @Size(max = 200)
            List<@NotBlank @Size(max = 120) String> participantIds,
            @NotNull @Size(max = 200) List<@NotBlank @Size(max = 120) String> promoterIds,
            @Min(1) long expectedVersion) { }
    /** 表单字段定义，包含稳定字段键、显示类型、是否必填及选择项；布局字段也使用此结构。 */
    public record FormFieldInput(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String id,
            @NotBlank @Size(max = 120) String title,
            @NotBlank @Pattern(regexp = "name|phone|email|text|textarea|single|multiple|dropdown|gender|date|time|description|divider|image") String type,
            @Size(max = 500) String description, boolean required,
            @Size(max = 100) List<@NotBlank @Size(max = 120) String> options) { }
    /** 营销表单创建内容与线索字段映射；字段键引用、表单类型及收集规则由服务进一步核对。 */
    public record MarketingFormInput(@NotBlank @Size(max = 120) String campusId,
            @NotBlank @Size(max = 160) String title, @Size(max = 2000) String description,
            @NotBlank @Pattern(regexp = "lead|survey|custom") String formKind, boolean collectLead,
            @Size(min = 1, max = 100) List<@Valid FormFieldInput> fields,
            java.util.Map<@NotBlank @Pattern(regexp = "studentName|guardianPhone|subject|term|detail") String,
                @NotBlank @Size(max = 80) String> leadFieldMapping) { }
    /** 营销表单发布等版本化操作的预期行版本。 */
    public record MarketingFormVersion(@Min(1) long expectedVersion) { }
    /** 员工表单详情，区分可编辑实体版本和当前已发布内容修订号。 */
    public record MarketingForm(String id, String campusId, String ownerId, String title,
            String description, String formKind, boolean collectLead, String status,
            long currentRevision, List<FormFieldInput> fields, java.util.Map<String,String> leadFieldMapping,
            long version) { }
    /** 员工可访问的营销表单分页及总数。 */
    public record MarketingFormPage(List<MarketingForm> items, long total, int page, int size) { }
    /** 活动与表单的关联读取投影，保留表单当前状态、修订号及关联时间。 */
    public record ActivityFormLink(String activityId, String formId, String formTitle,
            String formStatus, long revision, java.time.Instant linkedAt) { }
    /** 活动关联表单的输入；可选活动预期版本是否必填由对应活动业务规则决定。 */
    public record ActivityFormLinkInput(@NotBlank @Size(max = 120) String formId,
            @Min(1) @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Long expectedVersion) {
        /**
         * 构造未提供活动版本的兼容关联输入；是否允许由关联业务核验。
         * @param formId 目标表单公开标识
         */
        public ActivityFormLinkInput(String formId) { this(formId,null); }
    }
    /** 公开表单的已发布内容、提交键、展示设置及来源活动；不携带员工授权信息。 */
    public record PublicFormView(String id,String title,String description,String formKind,boolean collectLead,
            long revision,String activityId,List<FormFieldInput> fields,String submissionKey,
            java.util.Map<String,Object> settings,String campusId) {
    }
    /** 公开表单提交内容，支持试听预约及推广链接来源；来源关系由服务端验证并冻结。 */
    public record PublicFormSubmissionInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{16,100}") String submissionId,
            @NotNull @Size(max=100) java.util.Map<@NotBlank @Size(max=80) String,Object> answersById,
            @Size(max=120) String activityId,@Valid FormTrialDtos.BookingInput trialBooking,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @Size(max=120) String promotionLinkId) {
        /**
         * 构造不包含试听预约和推广链接的公开提交。
         * @param submissionId 客户端稳定提交标识
         * @param answersById 按字段键组织的答案
         * @param activityId 可选来源活动公开标识
         */
        public PublicFormSubmissionInput(String submissionId,java.util.Map<String,Object> answersById,String activityId) {
            this(submissionId,answersById,activityId,null,null);
        }
        /**
         * 构造带可选试听预约但不带推广链接的公开提交。
         * @param submissionId 客户端稳定提交标识
         * @param answersById 按字段键组织的答案
         * @param activityId 可选来源活动公开标识
         * @param trialBooking 可选试听预约输入
         */
        public PublicFormSubmissionInput(String submissionId,Map<String,Object> answersById,String activityId,
                FormTrialDtos.BookingInput trialBooking) {this(submissionId,answersById,activityId,trialBooking,null);}

    }
    /** 公开表单接收回执，附可选试听预约结果和奖励；received不等同审核通过。 */
    public record PublicFormReceipt(String submissionId,boolean received,String status,FormTrialDtos.BookingReceipt trialBooking,
            FormRewardRepository.Reward reward) {
        /**
         * 构造没有奖励内容的公开表单回执。
         * @param submissionId 稳定提交标识
         * @param received 是否已接收
         * @param status 当前提交状态
         * @param trialBooking 可选试听预约回执
         */
        public PublicFormReceipt(String submissionId,boolean received,String status,FormTrialDtos.BookingReceipt trialBooking) {
            this(submissionId,received,status,trialBooking,null);
        }
        /**
         * 构造不包含试听预约或奖励内容的公开表单回执。
         * @param submissionId 稳定提交标识
         * @param received 是否已接收
         * @param status 当前提交状态
         */
        public PublicFormReceipt(String submissionId,boolean received,String status) { this(submissionId,received,status,null); }
    }
    /** 表单审核决定、必填意见及可选既有客户绑定；独立家庭确认与预期版本仍须业务校验。 */
    public record FormSubmissionReviewInput(@NotBlank @Pattern(regexp="approve|reject") String decision,
            @NotBlank @Size(max=2000) String note,@Size(max=120) String customerId,
            boolean confirmSeparateHousehold,@Min(1) long expectedVersion) { }
    /** 员工审核所见的表单提交详情，包含冻结修订、答案、审核结果和关联客户/线索。 */
    public record CrmFormSubmission(String id,String formId,long revision,String activityId,
            java.time.Instant submittedAt,String status,java.util.Map<String,Object> answers,
            String reviewerId,String reviewNote,String customerId,String leadId,long version) { }
    /** 授权审核范围内的表单提交分页。 */
    public record CrmFormSubmissionPage(List<CrmFormSubmission> items,long total,int page,int size) { }
    /** 客户触点的来源、渠道、发生时间及摘要，可关联活动和学员。 */
    public record TouchpointInput(@Size(max = 120) String activityId,
            @Size(max = 120) String studentId,
            @NotBlank @Pattern(regexp = "front_desk|online_form|phone|wecom|referral|import|other") String source,
            @NotBlank @Pattern(regexp = "phone|wecom|text|in_person|form|import") String channel,
            @NotBlank @Size(max = 2000) String summary,
            @NotNull java.time.Instant occurredAt) { }
    /** 追加式客户触点事实，保留操作者与发生时间及可选活动/学员关系。 */
    public record Touchpoint(String id, String customerId, String activityId, String studentId,
            String actorId, String source, String channel, String summary,
            java.time.Instant occurredAt) { }
    /** 从客户建立线索的输入，保留来源、学科、学期及下次跟进时间。 */
    public record LeadInput(@NotBlank @Size(max = 120) String customerId,
            @Size(max = 120) String activityId, @Size(max = 120) String studentId,
            @NotBlank @Pattern(regexp = "online_form|front_desk|phone|referral|import|other") String source,
            @NotBlank @Size(max = 200) String title, @Size(max = 100) String subject,
            @Size(max = 100) String term, @Size(max = 5000) String detail,
            java.time.Instant nextFollowAt, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @Pattern(regexp="self|public") String followChoice) { }
    /** 线索详情，分别保留初筛状态、决策状态及负责人和决策时限。 */
    public record Lead(String id, String customerId, String campusId, String activityId,
            String studentId, String ownerId, String source, String subject, String term,
            String title, String status, String detail, java.time.Instant nextFollowAt, long version,
            String decisionStatus, String decisionById, java.time.Instant decisionDeadline,
            java.time.Instant decisionTimedOutAt) { }
    /** 可读取线索的分页集合及同范围总数。 */
    public record LeadPage(List<Lead> items, long total, int page, int size) { }
    /** 线索列表组合筛选，支持校区、负责人、来源、学科和下次跟进时间边界。 */
    public record LeadQuery(@Size(max = 24) String status, @Size(max = 120) String customerId,
            @Size(max = 120) String activityId, @Min(1) @Max(100000) Integer page,
            @Min(1) @Max(100) Integer size, @Size(max = 120) String campusId,
            @Size(max = 120) String ownerId,
            @Pattern(regexp = "online_form|front_desk|phone|referral|import|other") String source,
            @Size(max = 100) String subject, Boolean unassignedOnly, Boolean activeOnly,
            @org.springframework.format.annotation.DateTimeFormat(iso =
                org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.Instant nextFollowFrom,
            @org.springframework.format.annotation.DateTimeFormat(iso =
                org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.Instant nextFollowUntil) {
        /**
         * 构造仅使用初筛状态、客户、活动及分页的兼容线索查询。
         * @param status 可选初筛状态
         * @param customerId 可选客户公开标识
         * @param activityId 可选活动公开标识
         * @param page 可选页码
         * @param size 可选每页条数
         */
        public LeadQuery(String status, String customerId, String activityId, Integer page, Integer size) {
            this(status, customerId, activityId, page, size, null, null, null, null, null, null, null, null);
        }
    }
    /** 线索分配或转移负责人的目标、原因和预期版本。 */
    public record LeadOwner(@NotBlank @Size(max = 120) String ownerId,
            @NotBlank @Size(max = 1000) String reason, @Min(1) long expectedVersion) { }
    /** 释放线索所需的原因和预期版本，不指定新的负责人。 */
    public record LeadRelease(@NotBlank @Size(max = 1000) String reason,
            @Min(1) long expectedVersion) { }
    /** 线索初筛状态修改输入；与旧CRM销售阶段分开，不能代替销售阶段操作。 */
    public record LeadStatus(@NotBlank @Pattern(regexp = "new|contacting|qualified|no_need|unreachable|invalid_contact|archived") String status,
            @Size(max = 1000) String reason, java.time.Instant nextFollowAt,
            @Min(1) long expectedVersion) { }
    /** 旧CRM销售阶段的读取回执，保留线索公开标识及行版本。 */
    public record LeadSalesStage(String id,String stage,long version,String intent,String status) {
        /** Preserves construction of historical receipts without inventing an intent or queue state.
         * @param id lead public identifier
         * @param stage sales stage
         * @param version optimistic version
         */
        public LeadSalesStage(String id,String stage,long version) { this(id,stage,version,null,null); }
    }
    /** 旧销售阶段的版本化修改，仅接收待报名、已流失或跟进中；转换资格由服务校验。 */
    public record LeadSalesStageInput(@NotBlank @Pattern(regexp="待报名|已流失|跟进中") String stage,
            @Size(max=1000) String reason,@Min(1) long expectedVersion) { }
    /** 线索历史事件，保留状态和负责人变化、说明、渠道及发生时间。 */
    public record LeadEvent(String id, String actorId, String eventType,
            String fromOwnerId, String toOwnerId, String fromStatus, String toStatus,
            String detail, java.time.Instant occurredAt, String channel) { }
    /** 追加线索跟进内容及可选下次跟进时间，同时提交当前行版本。 */
    public record LeadFollowupInput(@NotBlank @Pattern(regexp = "phone|wecom|in_person|online|other") String channel,
            @NotBlank @Size(max = 2000) String summary, java.time.Instant nextFollowAt,
            @Min(1) long expectedVersion, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @Pattern(regexp="high|medium|low|lost_cold") String disposition) {
        /** Keeps existing follow-up callers unchanged when no disposition was selected.
         * @param channel supported contact channel
         * @param summary interaction content encrypted at rest
         * @param nextFollowAt optional future follow-up time
         * @param expectedVersion version of the lead being followed
         */
        public LeadFollowupInput(String channel,String summary,java.time.Instant nextFollowAt,long expectedVersion) {
            this(channel,summary,nextFollowAt,expectedVersion,null);
        }
    }
    /** CRM工作台的日期窗口、队列统计卡及统一asOf时点，供队列点击保持计算口径。 */
    public record CrmWorkbench(String view, java.time.LocalDate date, java.time.Instant generatedAt,
            java.time.Instant fromAt, java.time.Instant untilAt, List<CrmWorkbenchCard> cards, java.time.Instant asOf) { }
    /** 待办队列卡的数量与导航条件，保留兼容线索筛选及新的资源/队列查询参数。 */
    public record CrmWorkbenchCard(String key, String label, long count, Map<String, String> leadFilters,
            String resource, String queue, Map<String, String> query) {
        /**
         * 构造线索资源的兼容队列卡；队列名使用key，扩展query为空。
         * @param key 稳定队列键
         * @param label 界面标题
         * @param count 匹配数量
         * @param leadFilters 点击卡片采用的线索筛选
         */
        public CrmWorkbenchCard(String key, String label, long count, Map<String, String> leadFilters) {
            this(key, label, count, leadFilters, "leads", key, Map.of());
        }
    }
    /** 待办队列的最小定位投影，包含资源标识、状态、创建/截止时间和负责人。 */
    public record CrmWorkbenchQueueItem(String id, String status, java.time.Instant createdAt,
            java.time.Instant dueAt, String ownerId) { }
    /** 同一asOf时点的待办队列分页和总数。 */
    public record CrmWorkbenchQueuePage(List<CrmWorkbenchQueueItem> items, long total, int page, int size,
            java.time.Instant asOf) { }
    /** 工作台队列请求，限定个人/团队范围、资源类型、队列和分页；权限仍按资源独立核验。 */
    public record CrmWorkbenchQueueQuery(@NotBlank @Pattern(regexp = "mine|team") String scope,
            @NotBlank @Pattern(regexp = "leads|trialBookings|formSubmissions|leadReclaimRequests|attributionRequests|crmTasks|opportunities|serviceTickets") String resource,
            @NotBlank @Size(max = 32) String queue, java.time.Instant asOf,
            @Min(1) @Max(100000) int page, @Min(1) @Max(100) int size) { }
    /** 客户商机的创建输入，可关联来源线索、活动与学员，保留学科/学期需求。 */
    public record OpportunityInput(@NotBlank @Size(max = 120) String customerId,
            @Size(max = 120) String sourceLeadId,
            @Size(max = 120) String activityId, @Size(max = 120) String studentId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 100) String subject, @NotBlank @Size(max = 100) String term,
            @Size(max = 5000) String detail) { }
    /** 商机当前需求、销售阶段、负责人、下次跟进时间和行版本。 */
    public record Opportunity(String id, String customerId, String sourceLeadId, String campusId, String ownerId,
            String activityId, String studentId, String title, String subject, String term,
            String stage, String detail, java.time.Instant nextFollowAt, long version) { }
    /** 授权范围内的商机分页及总数。 */
    public record OpportunityPage(List<Opportunity> items, long total, int page, int size) { }
    /** 商机追加式历史记录，保留阶段变化、跟进渠道及发生时间。 */
    public record OpportunityEvent(String id, String actorId, String eventType,
            String fromStage, String toStage, String channel, String detail,
            java.time.Instant occurredAt) { }
    /** 追加商机跟进说明和下次跟进时间的版本化输入。 */
    public record OpportunityFollowup(@NotBlank @Pattern(regexp = "phone|wecom|in_person|online|other") String channel,
            @NotBlank @Size(max = 5000) String detail, java.time.Instant nextFollowAt,
            @Min(1) long expectedVersion) { }
    /** 商机销售阶段转换输入；合法枚举不代表任意阶段之间均可转换。 */
    public record OpportunityStage(@NotBlank @Pattern(regexp = "qualified|appointment|trial|negotiation|closed") String stage,
            @Size(max = 1000) String reason, java.time.Instant nextFollowAt,
            @Min(1) long expectedVersion) { }
    /** 下单时选择的班级公开标识及读取时班级版本，用于冻结收费条款。 */
    public record ClassOrderInput(@NotBlank @Size(max=120) String classId,@Min(1) long expectedVersion) { }
    /** 订单中冻结的班级、课程和收费规则快照，独立于之后的班级配置修改。 */
    public record ClassOrderTerms(String classId,String className,String courseId,long classVersion,
            AcademicDtos.ClassFees fees) { }
    /** 班级收费条款及以元计的应收报价，金额按JSON字符串编码。 */
    public record ClassQuote(ClassOrderTerms terms,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalAmount) { }
    /** 允许下单的班级报价分页及总数。 */
    public record ClassQuotePage(List<ClassQuote> items,long total,int page,int size) { }
    /** 商机订单的应收金额、商品说明和商机预期版本，可附班级收费条款绑定。 */
    public record OrderInput(@NotBlank @Size(max = 120) String opportunityId,
            @NotNull @jakarta.validation.constraints.DecimalMin("0.01")
            @jakarta.validation.constraints.Digits(integer = 16, fraction = 2) BigDecimal totalAmount,
            @NotBlank @Size(max = 300) String productSnapshot, @Min(1) long expectedOpportunityVersion,
            @Valid @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            ClassOrderInput classBinding) {
        /**
         * 构造不绑定班级收费条款的商机订单输入。
         * @param opportunityId 商机公开标识
         * @param totalAmount 以元计的应收金额
         * @param productSnapshot 商品说明快照
         * @param expectedOpportunityVersion 读取时商机行版本
         */
        public OrderInput(String opportunityId,BigDecimal totalAmount,String productSnapshot,long expectedOpportunityVersion) {
            this(opportunityId,totalAmount,productSnapshot,expectedOpportunityVersion,null);
        }
    }
    /** 订单应收、实收与已退金额汇总及原始/当前归因负责人，可附冻结班级条款。 */
    public record CrmOrder(String id, String opportunityId, String customerId, String studentId,
            String campusId, String attributedOwnerId, String currentAttributionOwnerId,
            String productSnapshot, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal receivedAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal refundedAmount,
            String refundStatus, String status, long version,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            ClassOrderTerms classTerms) { }
    /** 授权范围内的订单分页及总数。 */
    public record OrderPage(List<CrmOrder> items, long total, int page, int size) { }
    /** 记录订单实收的金额、方式、参考号和发生时间；必须提交订单当前版本。 */
    public record ReceiptInput(@NotNull @jakarta.validation.constraints.DecimalMin("0.01")
            @jakarta.validation.constraints.Digits(integer = 16, fraction = 2) BigDecimal amount,
            @NotBlank @Pattern(regexp = "bank_transfer|pos|cash|online|other") String method,
            @Size(max = 1000) String reference, @NotNull java.time.Instant receivedAt,
            @Min(1) long expectedOrderVersion) { }
    /** 追加式订单实收事实及操作者，金额以元计并按JSON字符串编码。 */
    public record CrmReceipt(String id, String orderId, String actorId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            String method, String reference, java.time.Instant receivedAt) { }
    /** 统计期内单订单的实收金额、笔数及最近实收时间，关联客户与归因负责人。 */
    public record ReceiptMetric(String orderId, String opportunityId, String customerId,
            String campusId, String attributedOwnerId, BigDecimal receivedInPeriod,
            long receiptCount, java.time.Instant lastReceivedAt) { }
    /** 实收报表分页及统计期全量汇总，明确时间边界和生成时间。 */
    public record ReceiptReport(List<ReceiptMetric> items, long orderCount, BigDecimal totalReceived,
            java.time.Instant fromAt, java.time.Instant untilAt, java.time.Instant generatedAt,
            int page, int size) { }
    /** 单订单统计期内实收、实退、净现金流及各自笔数。 */
    public record CashFlowMetric(String orderId, String opportunityId, String customerId,
            String campusId, String attributedOwnerId, BigDecimal receipts, BigDecimal refunds,
            BigDecimal netCashFlow, long receiptCount, long refundCount, java.time.Instant lastEventAt) { }
    /** 收支报表分页及统计期汇总，净现金流为实收与实退之差。 */
    public record CashFlowReport(List<CashFlowMetric> items, long orderCount, BigDecimal receipts,
            BigDecimal refunds, BigDecimal netCashFlow, java.time.Instant fromAt,
            java.time.Instant untilAt, java.time.Instant generatedAt, int page, int size) { }
    /** 发起退款审核的金额、原因及可选证据；不会直接生成实退款项。 */
    public record RefundRequestInput(@NotNull @jakarta.validation.constraints.DecimalMin("0.01")
            @jakarta.validation.constraints.Digits(integer = 16, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 2000) String reason, @Size(max = 2000) String evidence) { }
    /** 退款申请的批准/拒绝意见及申请当前版本。 */
    public record RefundReviewInput(@NotBlank @Pattern(regexp = "approve|reject") String decision,
            @NotBlank @Size(max = 2000) String note, @Min(1) long expectedVersion) { }
    /** 获批退款的实际退款方式、参考号、发生时间及申请预期版本。 */
    public record RefundRecordInput(@NotBlank @Pattern(regexp = "bank_transfer|pos|cash|online|other") String method,
            @Size(max = 1000) String reference, @NotNull java.time.Instant refundedAt,
            @Min(1) long expectedVersion) { }
    /** 退款申请与审核状态详情，保留申请人、审核人及审核时间。 */
    public record CrmRefundRequest(String id, String orderId, String applicantId, BigDecimal amount,
            String reason, String evidence, String status, String reviewerId, String reviewNote,
            java.time.Instant reviewedAt, long version) { }
    /** 授权范围内的退款申请分页。 */
    public record RefundRequestPage(List<CrmRefundRequest> items, long total, int page, int size) { }
    /** 追加式实退款项，关联审核申请、订单、操作者及发生时间。 */
    public record CrmRefund(String id, String requestId, String orderId, String actorId,
            BigDecimal amount, String method, String reference, java.time.Instant refundedAt) { }
    /** 授权范围内的实际退款记录分页。 */
    public record RefundPage(List<CrmRefund> items, long total, int page, int size) { }
    /** 退款处理历史的状态变化、操作者和发生时间。 */
    public record RefundEvent(String id, String actorId, String eventType, String fromStatus,
            String toStatus, String detail, java.time.Instant occurredAt) { }
    /** 申请调整订单归因负责人的目标人员、原因和可选证据。 */
    public record AttributionRequestInput(@NotBlank @Size(max = 120) String proposedOwnerId,
            @NotBlank @Size(max = 2000) String reason, @Size(max = 2000) String evidence) { }
    /** 归因调整申请的审核决定、意见及预期版本。 */
    public record AttributionReviewInput(@NotBlank @Pattern(regexp = "approve|reject") String decision,
            @NotBlank @Size(max = 2000) String note, @Min(1) long expectedVersion) { }
    /** 归因申请详情，区分订单原归因、当前归因和本次申请目标。 */
    public record AttributionRequest(String id, String orderId, String applicantId,
            String originalOwnerId, String currentOwnerId, String proposedOwnerId,
            String reason, String evidence, String status, String reviewerId, String reviewNote,
            java.time.Instant reviewedAt, long version) { }
    /** 授权范围内的归因申请分页。 */
    public record AttributionRequestPage(List<AttributionRequest> items, long total, int page, int size) { }
    /** 审核通过后追加的归因调整事实，保留变更双方、审核人和证据。 */
    public record AttributionAdjustment(String id, String requestId, String orderId,
            String fromOwnerId, String toOwnerId, String reviewerId, String reason,
            String evidence, java.time.Instant adjustedAt) { }
    /** 归因申请的追加式状态变更历史。 */
    public record AttributionEvent(String id, String actorId, String eventType, String fromStatus,
            String toStatus, String detail, java.time.Instant occurredAt) { }
    /** 创建服务工单类型的编码、名称、启用标记和可选校区范围。 */
    public record ServiceTicketTypeInput(@Size(max = 120) String campusId,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{1,39}") String typeCode,
            @NotBlank @Size(max = 100) String name, boolean active) { }
    /** 服务工单类型当前配置与行版本。 */
    public record ServiceTicketType(String id, String campusId, String typeCode,
            String name, boolean active, long version) { }
    /** 仅更新工单类型名称和启用状态的版本化输入。 */
    public record ServiceTicketTypeUpdate(@NotBlank @Size(max = 100) String name,
            boolean active, @Min(1) long expectedVersion) { }
    /** 客户服务工单的类型、问题说明、可选负责人及报名/班级关联。 */
    public record ServiceTicketInput(@NotBlank @Size(max = 120) String customerId,
            @NotBlank @Size(max = 40) String typeCode,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 5000) String description,
            @Size(max = 120) String ownerId, @Size(max = 120) String enrollmentId,
            @Size(max = 120) String classId, java.time.Instant dueAt) { }
    /** 服务工单转交的目标负责人、原因和预期版本。 */
    public record ServiceTicketOwner(@NotBlank @Size(max = 120) String ownerId,
            @NotBlank @Size(max = 1000) String reason, @Min(1) long expectedVersion) { }
    /** 追加工单跟进详情、可选下次跟进时间及预期版本。 */
    public record ServiceTicketFollowup(@NotBlank @Size(max = 5000) String detail,
            java.time.Instant nextFollowAt, @Min(1) long expectedVersion) { }
    /** 工单关闭时记录的解决说明及预期版本。 */
    public record ServiceTicketClose(@NotBlank @Size(max = 5000) String resolution,
            @Min(1) long expectedVersion) { }
    /** 关联既有退款申请到服务工单的输入，不直接发起或执行退款。 */
    public record ServiceTicketRefundLink(@NotBlank @Size(max = 120) String refundRequestId,
            @Min(1) long expectedVersion) { }
    /** 工单当前状态、负责人、解决结果及可选退款申请/实退信息。 */
    public record ServiceTicket(String id, String customerId, String campusId,
            ServiceTicketType type, String ownerId, String createdBy, String enrollmentId,
            String classId, String refundRequestId, String refundStatus, BigDecimal refundAmount,
            java.time.Instant refundAt, String title, String description, String status,
            java.time.Instant dueAt, java.time.Instant nextFollowAt, String resolution,
            String actualResult, java.time.Instant completedAt, long version) { }
    /** 授权范围内的服务工单分页。 */
    public record ServiceTicketPage(List<ServiceTicket> items, long total, int page, int size) { }
    /** 工单历史事件，保留负责人、状态、退款关联和实际结果变化。 */
    public record ServiceTicketEvent(String id, String actorId, String eventType,
            String fromOwnerId, String toOwnerId, String fromStatus, String toStatus,
            String refundRequestId, String detail, String actualResult, java.time.Instant occurredAt) { }
    /** 任务配额策略中单个负责人的分配数量，范围0至10000。 */
    public record TaskQuota(@NotBlank @Size(max = 120) String ownerId, @Min(0) @Max(10000) int count) { }
    /** 任务目标选择器中的关联学员摘要，不承载联系人、订单或外部身份字段。 */
    public record TaskTargetStudent(String id, String name, String grade, String relationship) { }
    /** 客户关联学员的任务选择器分页。 */
    public record TaskTargetStudentPage(List<TaskTargetStudent> items, long total, int page, int size) { }
    /** 任务需求来源引用，限定为线索或触点，并可附来源版本供提交时复核。 */
    public record TaskSourceRef(@NotBlank @Pattern(regexp = "lead|touchpoint") String type,
            @NotBlank @Size(max = 120) String id, @Min(1) Long version) { }
    /** 按客户、可选学员和需求维度描述待分配任务目标，保留来源及需求身份。 */
    public record TaskTargetInput(@NotBlank @Size(max = 120) String customerId,
            @Size(max = 120) String studentId, @Size(max = 100) String subject,
            @Size(max = 100) String term,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) @Size(max = 100) List<@NotNull @Valid TaskSourceRef> sources,
            @Valid @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) OpportunityNeedDtos.Identity needIdentity) {
        /**
         * 将缺省来源集合转为空集合，并复制非空集合以固定目标来源。
         * @param customerId 客户公开标识
         * @param studentId 可选学员公开标识
         * @param subject 可选学科
         * @param term 可选学期
         * @param sources 可选线索或触点引用集合
         * @param needIdentity 可选需求身份
         * @throws NullPointerException sources包含null元素
         */
        public TaskTargetInput { sources = sources == null ? List.of() : List.copyOf(sources); }
        /**
         * 构造不带来源引用或需求身份的兼容任务目标。
         * @param customerId 客户公开标识
         * @param studentId 可选学员公开标识
         * @param subject 可选学科
         * @param term 可选学期
         */
        public TaskTargetInput(String customerId, String studentId, String subject, String term) {
            this(customerId, studentId, subject, term, List.of(),null);
        }
        /**
         * 构造带来源引用但未指定需求身份的任务目标。
         * @param customerId 客户公开标识
         * @param studentId 可选学员公开标识
         * @param subject 可选学科
         * @param term 可选学期
         * @param sources 可选线索或触点引用集合
         * @throws NullPointerException sources包含null元素
         */
        public TaskTargetInput(String customerId,String studentId,String subject,String term,List<TaskSourceRef> sources) {
            this(customerId,studentId,subject,term,sources,null);
        }
    }
    /** 预览中可分配目标的负责人结果及来源、需求身份，不表示已经写入任务。 */
    public record TaskTargetAssignment(String targetKey, String customerId, String studentId,
            String subject, String term, String ownerId, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<TaskSourceRef> sources,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) OpportunityNeedDtos.Identity needIdentity) {
        /**
         * 固定可分配目标的来源引用集合；缺省来源转为空集合。
         * @param targetKey 精确任务目标键
         * @param customerId 客户公开标识
         * @param studentId 可选学员公开标识
         * @param subject 学科
         * @param term 学期
         * @param ownerId 拟分配负责人公开标识
         * @param sources 来源引用集合
         * @param needIdentity 可选需求身份
         * @throws NullPointerException sources包含null元素
         */
        public TaskTargetAssignment { sources = sources == null ? List.of() : List.copyOf(sources); }
    }
    /** 预览或提交中跳过的具体任务目标、原因及原来源身份。 */
    public record TaskTargetSkipped(String targetKey, String customerId, String studentId,
            String subject, String term, String reason, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<TaskSourceRef> sources,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) OpportunityNeedDtos.Identity needIdentity) {
        /**
         * 固定被跳过目标的来源引用集合；缺省来源转为空集合。
         * @param targetKey 精确任务目标键
         * @param customerId 客户公开标识
         * @param studentId 可选学员公开标识
         * @param subject 学科
         * @param term 学期
         * @param reason 跳过原因
         * @param sources 来源引用集合
         * @param needIdentity 可选需求身份
         * @throws NullPointerException sources包含null元素
         */
        public TaskTargetSkipped { sources = sources == null ? List.of() : List.copyOf(sources); }
    }
    /** 营销/服务任务批量分配输入，包含目标、负责人、策略、配额及截止时间。 */
    public record TaskBatchInput(@NotBlank @Size(max = 200) String title,
            @NotBlank @Pattern(regexp = "marketing|service") String taskType,
            @Size(max = 100) String serviceType,
            @Size(min = 1, max = 500) List<@NotBlank @Size(max = 120) String> customerIds,
            @Size(max = 120) String activityId, @Size(max = 100) String subject,
            @Size(max = 100) String term, @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 120) String> ownerIds,
            @NotBlank @Pattern(regexp = "even|quota|owner_first") String strategy,
            @Size(max = 100) List<@Valid TaskQuota> quotas, @NotNull java.time.Instant dueAt,
            @Size(max = 5000) String note,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @Size(min = 1, max = 500) List<@NotNull @Valid TaskTargetInput> targets) { }
    /** 已建立任务批次的状态、创建/跳过计数、任务标识及负责人分配汇总。 */
    public record TaskBatch(String id, String title, String taskType, String serviceType,
            String campusId, java.time.Instant dueAt, String status, int createdCount,
            int skippedCount, List<String> taskIds, java.util.Map<String, Long> assignmentCounts,
            long version, String activityId) {
    }
    /** 任务详情及来源/需求身份；身份状态由学员标识计算，未提供来源状态时标记unknown。 */
    public record CrmTask(String id, String batchId, String customerId, String campusId,
            String ownerId, String taskType, String serviceType, String title,
            String subject, String term, String status, java.time.Instant dueAt,
            String note, String result, String resultNote, String opportunityId,
            java.time.Instant completedAt, long version, String studentId, String identityStatus,
            String activityId, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<TaskSourceRef> sources, String sourceStatus,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) OpportunityNeedDtos.Identity needIdentity,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) OpportunityNeedDtos.Identity resolvedNeedIdentity) {
        /**
         * 根据studentId重算身份状态，固定来源集合，并为缺省sourceStatus设置unknown。
         * @param id 任务公开标识
         * @param batchId 批次公开标识
         * @param customerId 客户公开标识
         * @param campusId 校区公开标识
         * @param ownerId 负责人公开标识
         * @param taskType 任务类型
         * @param serviceType 服务分类
         * @param title 任务标题
         * @param subject 学科
         * @param term 学期
         * @param status 任务状态
         * @param dueAt 截止时间
         * @param note 任务说明
         * @param result 完成结果分类
         * @param resultNote 完成说明
         * @param opportunityId 关联商机公开标识
         * @param completedAt 完成时间
         * @param version 任务行版本
         * @param studentId 可选学员公开标识
         * @param identityStatus 兼容输入值，构造时由studentId覆盖
         * @param activityId 关联活动公开标识
         * @param sources 来源引用集合
         * @param sourceStatus 来源状态，null时使用unknown
         * @param needIdentity 原始需求身份
         * @param resolvedNeedIdentity 已解析需求身份
         * @throws NullPointerException sources包含null元素
         */
        public CrmTask {
            identityStatus = studentId == null ? "household_unidentified" : "student_identified";
            sources = sources == null ? List.of() : List.copyOf(sources);
            sourceStatus = sourceStatus == null ? "unknown" : sourceStatus;
        }
    }
    /** 批量任务写入回执，保留实际任务及按客户和精确目标两种粒度的跳过结果。 */
    public record TaskBatchReceipt(TaskBatch batch, List<CrmTask> tasks, List<String> skippedCustomerIds,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            List<TaskTargetSkipped> skippedTargets) {
        /**
         * 固定精确目标粒度的跳过结果，缺省跳过目标转为空集合。
         * @param batch 已创建批次
         * @param tasks 实际创建任务
         * @param skippedCustomerIds 兼容客户粒度跳过标识
         * @param skippedTargets 精确目标粒度跳过结果
         * @throws NullPointerException skippedTargets包含null元素
         */
        public TaskBatchReceipt {
            skippedTargets = skippedTargets == null ? List.of() : List.copyOf(skippedTargets);
        }
        /**
         * 构造仅提供客户粒度跳过结果的兼容批次回执。
         * @param batch 已创建批次
         * @param tasks 实际创建任务
         * @param skippedCustomerIds 跳过客户公开标识
         */
        public TaskBatchReceipt(TaskBatch batch, List<CrmTask> tasks, List<String> skippedCustomerIds) {
            this(batch, tasks, skippedCustomerIds, List.of());
        }
    }
    /** 任务完成结果和版本，可关联商机及明确的需求身份解析结果。 */
    public record TaskCompletion(@NotBlank @Pattern(regexp = "qualified|no_need|unreachable|invalid_contact|completed|refused") String result,
            @Size(max = 5000) String note, @Size(max = 120) String opportunityId,
            @Min(1) long expectedVersion,
            @Valid @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) OpportunityNeedDtos.NeedResolution needResolution) {
        /**
         * 构造不提供需求身份解析结果的兼容任务完成输入。
         * @param result 完成结果分类
         * @param note 可选完成说明
         * @param opportunityId 可选关联商机标识
         * @param expectedVersion 任务预期行版本
         */
        public TaskCompletion(String result,String note,String opportunityId,long expectedVersion) {
            this(result,note,opportunityId,expectedVersion,null);
        }
    }
    /** 任务负责人、待办状态、截止时间及原因的版本化部分更新输入。 */
    public record TaskUpdate(@Size(max = 120) String ownerId,
            @Pattern(regexp = "pending|in_progress|cancelled") String status,
            java.time.Instant dueAt, @Size(max = 1000) String reason, @Min(1) long expectedVersion) { }
    /** 任务列表状态、类型、批次和活动筛选，省略分页时使用第1页每页50条。 */
    public record TaskQuery(@Pattern(regexp = "pending|in_progress|completed|cancelled") String status,
            @Pattern(regexp = "marketing|service") String taskType, @Size(max = 120) String batchId,
            @Size(max = 120) String activityId, @Min(1) @Max(100000) Integer page, @Min(1) @Max(100) Integer size) {
        /**
         * 补齐缺省分页，不在构造器中代替Bean Validation校验范围。
         * @param status 可选任务状态
         * @param taskType 可选任务类型
         * @param batchId 可选批次标识
         * @param activityId 可选活动标识
         * @param page 页码，null使用1
         * @param size 每页条数，null使用50
         */
        public TaskQuery { page = page == null ? 1 : page; size = size == null ? 50 : size; }
    }
    /** 任务批次列表状态、类型和活动筛选，省略分页时使用第1页每页50条。 */
    public record TaskBatchQuery(@Pattern(regexp = "active|completed|cancelled") String status,
            @Pattern(regexp = "marketing|service") String taskType, @Size(max = 120) String activityId,
            @Min(1) @Max(100000) Integer page, @Min(1) @Max(100) Integer size) {
        /**
         * 补齐批次查询缺省分页；状态与分页范围仍由请求校验执行。
         * @param status 可选批次状态
         * @param taskType 可选任务类型
         * @param activityId 可选活动标识
         * @param page 页码，null使用1
         * @param size 每页条数，null使用50
         */
        public TaskBatchQuery { page = page == null ? 1 : page; size = size == null ? 50 : size; }
    }
    /** 授权范围内的任务分页。 */
    public record TaskPage(List<CrmTask> items, long total, int page, int size) { }
    /** 任务批次列表摘要，包含创建、跳过、未完成和已完成计数。 */
    public record TaskBatchSummary(String id, String title, String taskType, String serviceType,
            String campusId, java.time.Instant dueAt, String status, long createdCount,
            int skippedCount, long openCount, long completedCount, long version, String activityId) {
    }
    /** 授权范围内的任务批次分页。 */
    public record TaskBatchPage(List<TaskBatchSummary> items, long total, int page, int size) { }
    /** 批量分配预览的数量、负责人分配及精确目标集合；集合在构造时复制为只读快照。 */
    public record TaskBatchPreview(int selectedCount, int eligibleCount, int skippedCount,
            String campusId, java.util.Map<String, Long> assignmentCounts,
            java.util.Map<String, String> customerAssignments, List<String> eligibleCustomerIds,
            List<String> skippedCustomerIds,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            List<TaskTargetAssignment> eligibleTargets,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            List<TaskTargetSkipped> skippedTargets) {
        /**
         * 复制预览中的所有集合和映射，缺省集合转为空值集合以固定确认快照。
         * @param selectedCount 选中目标数
         * @param eligibleCount 可分配目标数
         * @param skippedCount 跳过目标数
         * @param campusId 预览校区公开标识
         * @param assignmentCounts 负责人分配数量
         * @param customerAssignments 兼容客户到负责人映射
         * @param eligibleCustomerIds 可分配客户标识
         * @param skippedCustomerIds 跳过客户标识
         * @param eligibleTargets 精确可分配目标
         * @param skippedTargets 精确跳过目标
         * @throws NullPointerException 任一非空集合含null元素或非空映射含null键/值
         */
        public TaskBatchPreview {
            assignmentCounts = assignmentCounts == null ? java.util.Map.of() : java.util.Map.copyOf(assignmentCounts);
            customerAssignments = customerAssignments == null ? java.util.Map.of() : java.util.Map.copyOf(customerAssignments);
            eligibleCustomerIds = eligibleCustomerIds == null ? List.of() : List.copyOf(eligibleCustomerIds);
            skippedCustomerIds = skippedCustomerIds == null ? List.of() : List.copyOf(skippedCustomerIds);
            eligibleTargets = eligibleTargets == null ? List.of() : List.copyOf(eligibleTargets);
            skippedTargets = skippedTargets == null ? List.of() : List.copyOf(skippedTargets);
        }
        /**
         * 构造仅含汇总及负责人配额的兼容预览，目标级集合均为空。
         * @param selectedCount 选中目标数
         * @param eligibleCount 可分配目标数
         * @param skippedCount 跳过目标数
         * @param campusId 校区公开标识
         * @param assignmentCounts 负责人分配数量
         * @throws NullPointerException assignmentCounts包含null键或值
         */
        public TaskBatchPreview(int selectedCount, int eligibleCount, int skippedCount,
                String campusId, java.util.Map<String, Long> assignmentCounts) {
            this(selectedCount, eligibleCount, skippedCount, campusId, assignmentCounts,
                java.util.Map.of(), List.of(), List.of(), List.of(), List.of());
        }
    }
    /** 将原始分配输入与用户确认的预览绑定，服务端重算并核对后才写入任务。 */
    public record TaskBatchCommitInput(@Valid @NotNull TaskBatchInput input,
            @NotNull TaskBatchPreview expectedPreview) { }
    /** 客户获客数据的CSV/XLSX内容、列映射和归属配置；导入解析与逐行校验在服务端进行。 */
    public record CrmImportInput(@NotBlank @Pattern(regexp = "csv|xlsx") String format,
            @NotBlank @Size(max = 950000) String content,
            @NotNull Map<@NotBlank @Size(max = 80) String, @NotBlank @Size(max = 200) String> mapping,
            @NotBlank @Size(max = 120) String campusId, @Size(max = 120) String activityId,
            @Size(max = 100) String source, @Size(max = 200) String fileName) { }
    /** 导入逐行分类和结果，手机号仅脱敏显示，可关联导入后客户、线索及触点。 */
    public record CrmImportRow(int rowNumber, String category, String studentName,
            String phoneMasked, List<String> errors, String customerId, String leadId, String touchpointId) { }
    /** 导入分类计数，分别统计新客户、已有客户触点、重复需求、需审核和无效行。 */
    public record CrmImportSummary(int newCustomer, int existingTouch, int duplicateDemand,
            int needsReview, int invalid) { }
    /** 导入预览的源列、目标字段、字典、逐行结果和分类汇总。 */
    public record CrmImportPreview(List<String> columns, List<String> fields,
            Map<String, List<String>> dictionaries, List<CrmImportRow> items,
            CrmImportSummary summary, int total) { }
    /** 导入提交回执，包含批次标识及实际逐行结果和汇总。 */
    public record CrmImportReceipt(String batchId, CrmImportSummary summary, List<CrmImportRow> items) { }
}
