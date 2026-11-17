package cn.siyue.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Sales demand contracts remain separate from accounting and academic DTOs. */
public final class OpportunityNeedDtos {
    private OpportunityNeedDtos() { }
    /** 需求业务身份输入，部门与项目的组合规则由配置决定。
     * @param departmentId 必填部门公开标识，至多120字符
     * @param projectId 可选项目公开标识，是否必填由部门projectMode决定
     */
    public record Identity(@NotBlank @Size(max=120) String departmentId, @Size(max=120) String projectId) { }
    /** 需求身份的标识与显示名快照。
     * @param departmentId 部门公开标识
     * @param departmentName 部门显示名
     * @param projectId 项目公开标识，可为空
     * @param projectName 项目显示名，可为空
     * @param projectMode 部门项目选择模式
     */
    public record IdentityView(String departmentId,String departmentName,String projectId,
            String projectName,String projectMode) { }
    /** 需求可变档案输入，不作为需求固定身份键。
     * @param gradeId 可选年级配置标识
     * @param preferredCampusId 可选意向校区标识
     * @param classTypeId 可选班型配置标识
     * @param levelId 可选程度配置标识
     * @param fiscalYearId 可选财年配置标识
     * @param quarterId 可选季度配置标识
     */
    public record Profile(@Size(max=120) String gradeId,@Size(max=120) String preferredCampusId,
            @Size(max=120) String classTypeId,@Size(max=120) String levelId,
            @Size(max=120) String fiscalYearId,@Size(max=120) String quarterId) { }
    /** 需求档案配置标识及对应名称快照。
     * @param gradeId 年级标识
     * @param preferredCampusId 意向校区标识
     * @param classTypeId 班型标识
     * @param levelId 程度标识
     * @param fiscalYearId 财年标识
     * @param quarterId 季度标识
     * @param gradeName 年级名称
     * @param preferredCampusName 意向校区名称
     * @param classTypeName 班型名称
     * @param levelName 程度名称
     * @param fiscalYearName 财年名称
     * @param quarterName 季度名称
     */
    public record ProfileView(String gradeId,String preferredCampusId,String classTypeId,String levelId,
            String fiscalYearId,String quarterId,String gradeName,String preferredCampusName,
            String classTypeName,String levelName,String fiscalYearName,String quarterName) { }
    /** 需求预览及提交共用的原始输入，来源记录和负责人由服务独立授权。
     * @param customerId 必填客户公开标识
     * @param studentId 可选有效关联孩子公开标识
     * @param sourceLeadId 可选来源线索标识
     * @param activityId 可选来源活动标识
     * @param title 非空需求标题，至多200字符
     * @param subject 非空意向学科，至多100字符
     * @param term 非空意向学期，至多100字符
     * @param identity 必填部门/项目身份
     * @param profile 可选需求档案
     * @param detail 非空咨询内容，至多5000字符
     * @param ownerId 可选负责人公开标识，提交时核验资格
     */
    public record Input(@NotBlank @Size(max=120) String customerId,@Size(max=120) String studentId,
            @Size(max=120) String sourceLeadId,@Size(max=120) String activityId,
            @NotBlank @Size(max=200) String title,@NotBlank @Size(max=100) String subject,
            @NotBlank @Size(max=100) String term,@Valid @NotNull Identity identity,
            @Valid Profile profile,@NotBlank @Size(max=5000) String detail,@Size(max=120) String ownerId) { }
    /** 需求匹配最小证据，完整商机读取资格不足时隐藏负责人和阶段。
     * @param targetRef 候选商机公开标识
     * @param version 候选当前版本
     * @param title 需求标题
     * @param studentId 可披露的关联孩子标识
     * @param subject 意向学科
     * @param term 意向学期
     * @param identityStatus classified或legacy_unknown
     * @param identity 已分类身份快照，旧记录可为空
     * @param match 当前匹配结果说明，可为空
     * @param readable 是否允许查看完整商机
     * @param ownerId 仅完整读取允许时披露的负责人
     * @param stage 仅完整读取允许时披露的阶段
     * @param studentVisibility visible或restricted，说明关联孩子当前可见性
     */
    public record Candidate(String targetRef,long version,String title,String studentId,String subject,
            String term,String identityStatus,IdentityView identity,String match,boolean readable,
            String ownerId,String stage,String studentVisibility) { }
    /** 预览中输入与既有需求字段的差异。
     * @param targetRef 被比较需求标识
     * @param field 发生差异的字段名
     * @param requested 本次输入的显示值
     * @param current 当前记录的显示值
     */
    public record Difference(String targetRef,String field,String requested,String current) { }
    /** 提交前可核对的版本化计划；提交时重算并比较，不能作为长期授权。
     * @param catalogVersion 需求配置版本
     * @param inputHash 规范输入摘要
     * @param customerVersion 来源客户版本
     * @param sourceLeadVersion 可选来源线索版本
     * @param activityVersion 可选来源活动版本
     * @param scopeCampusId 本次需求适用校区
     * @param candidates 匹配或相关需求最小证据
     * @param allowedActions 当前允许的处理动作
     * @param selectedOwnerId 计划接收人公开标识
     * @param differences 输入与既有需求的字段差异
     */
    public record Preview(long catalogVersion,String inputHash,long customerVersion,Long sourceLeadVersion,
            Long activityVersion,String scopeCampusId,List<Candidate> candidates,List<String> allowedActions,
            String selectedOwnerId,List<Difference> differences) { }
    /** 用户对预览的明确处理选择。
     * @param action create、reuse、classify_existing或create_distinct
     * @param targetRef 复用或分类目标的公开标识
     * @param reason 选择需要理由时的说明，至多1000字符
     */
    public record Decision(@NotBlank @Pattern(regexp="create|reuse|classify_existing|create_distinct") String action,
            @Size(max=120) String targetRef,@Size(max=1000) String reason) { }
    /** 绑定原输入、确认预览和处理决定的幂等提交载荷。
     * @param input 原需求输入
     * @param expectedPreview 用户确认的完整预览证据
     * @param decision 用户选择的处理动作、目标及理由
     */
    public record Commit(@NotNull @Valid Input input,@NotNull Preview expectedPreview,
            @NotNull @Valid Decision decision) { }
    /** 需求提交最小回执，完整商机投影依当前权限决定是否返回。
     * @param outcome 本次需求处理结果
     * @param opportunityId 对应商机公开标识
     * @param consultationId 新增咨询事件标识
     * @param ownerChanged 是否改变负责人
     * @param notificationState 交接提醒生成状态
     * @param opportunity 允许完整读取时的商机投影，否则为空
     * @param identityStatus 需求已分类或旧身份未明确的状态
     */
    public record Receipt(String outcome,String opportunityId,String consultationId,boolean ownerChanged,
            String notificationState,CrmDtos.Opportunity opportunity,String identityStatus) { }
    /** 当前授权商机及其需求身份、可变档案与最新判断。
     * @param opportunity 商机授权投影
     * @param identityStatus classified或legacy_unknown
     * @param identity 固定业务身份快照
     * @param profile 当前需求档案快照
     * @param judgment 最近的判断快照，可为空
     */
    public record Detail(CrmDtos.Opportunity opportunity,String identityStatus,IdentityView identity,
            ProfileView profile,JudgmentView judgment) { }
    /** 在商机及配置版本同时匹配后更新需求档案。
     * @param profile 完整需求档案输入
     * @param expectedVersion 至少为1的商机期望版本
     * @param expectedCatalogVersion 至少为1的需求配置期望版本
     */
    public record ProfileUpdate(@NotNull @Valid Profile profile,@Min(1) long expectedVersion,
            @Min(1) long expectedCatalogVersion) { }
    /** 记录一个配置内的需求判断及必要理由。
     * @param judgmentId 必填判断配置标识
     * @param reasonId 可选原因配置标识，具体要求随判断配置
     * @param detail 非空判断说明，至多1000字符
     * @param expectedVersion 商机期望版本
     * @param expectedCatalogVersion 需求配置期望版本
     */
    public record JudgmentInput(@NotBlank @Size(max=120) String judgmentId,@Size(max=120) String reasonId,
            @NotBlank @Size(max=1000) String detail,@Min(1) long expectedVersion,
            @Min(1) long expectedCatalogVersion) { }
    /** 不可变判断快照的显示字段。
     * @param judgmentId 判断配置标识
     * @param judgmentName 判断显示名
     * @param reasonId 原因配置标识，可为空
     * @param reasonName 原因显示名，可为空
     * @param detail 判断说明
     * @param effect 判断的业务效果
     * @param createdAt 判断记录时刻
     */
    public record JudgmentView(String judgmentId,String judgmentName,String reasonId,String reasonName,
            String detail,String effect,Instant createdAt) { }
    /** 客户关系授权内的需求登记最小上下文。
     * @param customer 客户标识、校区及版本
     * @param contacts 脱敏的客户联系方式
     * @param studentSelection 关联孩子选择方式，当前为customer_linked
     */
    public record Context(Customer customer,List<CrmDtos.CustomerContact> contacts,String studentSelection) { }
    /** 需求录入所需客户最小身份。
     * @param id 客户公开标识
     * @param displayName 客户显示名
     * @param campusId 客户校区公开标识，可为空
     * @param version 客户当前版本
     */
    public record Customer(String id,String displayName,String campusId,long version) { }
    /** 客户有效关联孩子的选择投影，不含一般学员完整档案。
     * @param id 孩子公开标识
     * @param name 姓名
     * @param grade 年级
     * @param relationship 与客户的关系
     */
    public record Student(String id,String name,String grade,String relationship) { }
    /** 客户有效关联孩子分页结果。
     * @param items 当前页客户有效关联孩子
     * @param total 匹配总数
     * @param page 从1开始的页码
     * @param size 页大小
     */
    public record StudentPage(List<Student> items,long total,int page,int size) { }
    /** 需求最小证据候选分页结果。
     * @param items 当前页需求最小证据候选
     * @param total 匹配总数
     * @param page 从1开始的页码
     * @param size 页大小
     */
    public record CandidatePage(List<Candidate> items,long total,int page,int size) { }
    /** 需求配置项的当前版本投影。
     * @param id 配置项公开标识
     * @param kind 配置类型
     * @param code 类型内业务编码
     * @param label 显示名称
     * @param parentId 父配置项公开标识，可为空
     * @param campusId 限定校区公开标识，可为空
     * @param projectMode 部门的项目选择模式，非部门可为空
     * @param effect 判断的业务效果，非判断可为空
     * @param active 是否启用
     * @param version 乐观锁版本
     */
    public record Option(String id,String kind,String code,String label,String parentId,String campusId,
            String projectMode,String effect,boolean active,long version) { }
    /** 创建需求配置项，类别与父子结构由配置服务核验。
     * @param kind 非空配置类别，至多30字符
     * @param code 1至60位字母数字及下划线点连字符，首位为字母数字
     * @param label 非空名称，至多100字符
     * @param parentId 可选父配置项标识
     * @param campusId 可选限定校区标识
     * @param projectMode 适用部门时的项目模式
     * @param effect 适用判断时的业务效果
     */
    public record OptionCreate(@NotBlank @Size(max=30) String kind,
            @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_.-]{0,59}") String code,
            @NotBlank @Size(max=100) String label,@Size(max=120) String parentId,
            @Size(max=120) String campusId,String projectMode,String effect) { }
    /** 仅替换配置项名称和启用状态，不更改原编码或层级。
     * @param label 非空显示名，至多100字符
     * @param active 新启用状态
     * @param expectedVersion 配置项期望版本
     */
    public record OptionUpdate(@NotBlank @Size(max=100) String label,boolean active,@Min(1) long expectedVersion) { }
    /** 配置写入回执，供后续需求预览核对目录版本。
     * @param item 写入后的配置项
     * @param catalogVersion 更新后的需求配置总版本
     */
    public record OptionReceipt(Option item,long catalogVersion) { }
    /** 需求配置管理的类别、状态及校区过滤。
     * @param kind 可选配置类别
     * @param status active、inactive或all
     * @param campusId 可选校区标识
     * @param page 可选页码，默认1
     * @param size 可选页大小，默认20
     */
    public record OptionQuery(String kind,@Pattern(regexp="active|inactive|all") String status,
            @Size(max=120) String campusId,Integer page,Integer size) { }
    /** 需求配置管理页和同一目录版本。
     * @param items 当前页配置项
     * @param total 匹配总数
     * @param page 从1开始的页码
     * @param size 页大小
     * @param catalogVersion 需求配置总版本
     */
    public record OptionPage(List<Option> items,long total,int page,int size,long catalogVersion) { }
    /** 某个客户或商机校区可使用的需求选项集合。
     * @param catalogVersion 配置总版本
     * @param scopeCampusId 本次选项的范围校区
     * @param items 可用配置项
     * @param campuses 可选意向校区
     */
    public record Options(long catalogVersion,String scopeCampusId,List<Option> items,List<Campus> campuses) { }
    /** 需求档案可选意向校区。
     * @param id 校区公开标识
     * @param name 校区名称
     */
    public record Campus(String id,String name) { }
    /** 需求子资源分页；服务使用默认1/20并检查边界。
     * @param page 可选页码，范围1至100000
     * @param size 可选页大小，范围1至100
     */
    public record PageQuery(Integer page,Integer size) { }
    /** 根据客户或既有商机查询合格接收人，两种目标必须恰选其一。
     * @param customerId 可选客户公开标识
     * @param opportunityId 可选商机公开标识
     * @param page 可选页码，默认1
     * @param size 可选页大小，默认20
     */
    public record AssigneeQuery(@Size(max=120) String customerId,@Size(max=120) String opportunityId,
            Integer page,Integer size) { }
    /** 通过权限、范围、在职和改密状态校验的分配员工。
     * @param id 员工公开标识
     * @param name 员工显示名
     */
    public record Assignee(String id,String name) { }
    /** 合格分配员工分页结果。
     * @param items 当前页合格分配员工
     * @param total 匹配总数
     * @param page 从1开始的页码
     * @param size 页大小
     */
    public record AssigneePage(List<Assignee> items,long total,int page,int size) { }
    /** 版本化转交商机的输入，目标接收资格在事务内重新校验。
     * @param ownerId 非空目标负责人公开标识
     * @param reason 非空分配原因，至多1000字符
     * @param expectedVersion 商机期望版本
     */
    public record Assignment(@NotBlank @Size(max=120) String ownerId,@NotBlank @Size(max=1000) String reason,
            @Min(1) long expectedVersion) { }
    /** 分配后的最小回执，允许说明资源已离开当前范围而不披露详情。
     * @param opportunityId 被分配商机标识
     * @param ownerId 已授权操作写入的负责人标识
     * @param version 分配后的版本
     * @param scopeStatus visible或left_scope
     */
    public record AssignmentReceipt(String opportunityId,String ownerId,long version,String scopeStatus) { }
    /** 需求追加咨询事件的历史投影。
     * @param id 事件公开标识
     * @param actorId 原操作员工公开标识
     * @param detail 咨询内容
     * @param createdAt 事件时刻
     */
    public record Consultation(String id,String actorId,String detail,Instant createdAt) { }
    /** 需求咨询事件分页结果。
     * @param items 当前页需求咨询事件
     * @param total 匹配总数
     * @param page 从1开始的页码
     * @param size 页大小
     */
    public record ConsultationPage(List<Consultation> items,long total,int page,int size) { }
    /** 需求交接提醒；关联商机详情仍按当前可见性单独控制。
     * @param id 提醒公开标识
     * @param opportunityId 关联商机标识
     * @param consultationId 关联咨询事件标识
     * @param type 提醒类型
     * @param createdAt 提醒生成时刻
     * @param readAt 已读时刻，未读时为空
     * @param version 提醒版本
     * @param visibility 关联资源的当前可见性
     */
    public record Notification(String id,String opportunityId,String consultationId,String type,Instant createdAt,
            Instant readAt,long version,String visibility) { }
    /** 接收者提醒的状态过滤及分页。
     * @param status unread、read或all
     * @param page 可选页码，默认1
     * @param size 可选页大小，默认20
     */
    public record NotificationQuery(@Pattern(regexp="unread|read|all") String status,Integer page,Integer size) { }
    /** 接收者提醒分页结果。
     * @param items 当前页接收者提醒
     * @param total 匹配总数
     * @param page 从1开始的页码
     * @param size 页大小
     */
    public record NotificationPage(List<Notification> items,long total,int page,int size) { }
    /** 确认提醒已读的版本条件。
     * @param expectedVersion 至少为1的提醒期望版本
     */
    public record MarkRead(@Min(1) long expectedVersion) { }
    /** 任务处理时补足旧商机业务分类的受控输入。
     * @param departmentId 目标部门公开标识
     * @param projectId 可选项目公开标识
     * @param expectedOpportunityVersion 商机期望版本
     * @param reason 非空分类原因，至多1000字符
     */
    public record NeedResolution(@NotBlank @Size(max=120) String departmentId,@Size(max=120) String projectId,
            @Min(1) long expectedOpportunityVersion,@NotBlank @Size(max=1000) String reason) { }
    /** 任务所请求及实际解析的需求身份；空分量不序列化，保持旧请求摘要及CLI计划载荷。
     * @param needIdentity 任务要求的需求身份，可为空
     * @param resolvedNeedIdentity 处理时解析的需求身份，可为空
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskIdentity(Identity needIdentity,Identity resolvedNeedIdentity) { }
}
