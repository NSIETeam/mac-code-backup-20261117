package cn.siyue.core;

import java.time.Instant;
import java.util.List;

/** Minimal projections for preparing and carrying out assigned CRM work. */
public final class CrmTaskReadDtos {
    private CrmTaskReadDtos() { }
    /** 任务接收人选择器筛选及分页；未指定任务类型时采用marketing，省略分页时第1页每页20条。 */
    public record AssigneeQuery(String campusId, String taskType, String query, Integer page, Integer size) {
        /**
         * 补齐任务类型及分页默认值，查询合法性仍由服务校验。
         * @param campusId 可选校区公开标识
         * @param taskType 任务类型，null时使用marketing
         * @param query 可选人员搜索文本
         * @param page 页码，null时使用1
         * @param size 每页数量，null时使用20
         */
        public AssigneeQuery {
            taskType = taskType == null ? "marketing" : taskType;
            page = page == null ? 1 : page;
            size = size == null ? 20 : size;
        }
    }
    /** 符合当前分配资格的人员显示项及待办数量，不暴露其身份提供方subject。 */
    public record Assignee(String principalId, String displayName, long pendingCount) { }
    /** 任务接收人候选分页，说明查询校区、任务类型、生成时间和工作量统计范围。 */
    public record AssigneePage(List<Assignee> items, long total, int page, int size,
            String campusId, String taskType, Instant generatedAt, String workloadScope) { }
    /** 任务执行上下文中的客户标识及显示名称，不携带完整客户档案。 */
    public record Customer(String id, String displayName) { }
    /** 任务执行上下文中的学员标识、姓名及年级摘要。 */
    public record Student(String id, String name, String grade) { }
    /** 任务完成时可关联商机的需求身份、匹配类别及行版本投影。 */
    public record Opportunity(String id, String title, String stage, String subject, String term,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            OpportunityNeedDtos.Identity needIdentity,String match,long version) {
        /**
         * 构造无需求身份的兼容商机选项，匹配类别设为exact、版本设为1。
         * @param id 商机公开标识
         * @param title 商机标题
         * @param stage 商机阶段
         * @param subject 学科
         * @param term 学期
         */
        public Opportunity(String id,String title,String stage,String subject,String term) {
            this(id,title,stage,subject,term,null,"exact",1);
        }
    }
    /** 任务关联商机选择器的分页结果及匹配总数。 */
    public record OpportunityPage(List<Opportunity> items, long total, int page, int size) { }
    /** 授权任务执行上下文，组合客户、可选学员、脱敏联系人、活动和联系方式披露策略。 */
    public record Context(CrmDtos.CrmTask task, Customer customer, Student student,
            List<CrmDtos.CustomerContact> contacts, String activityId, String activityTitle,
            String contactPolicy) { }
}
