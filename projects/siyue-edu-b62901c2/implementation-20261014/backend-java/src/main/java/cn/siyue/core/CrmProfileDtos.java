package cn.siyue.core;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** Explicit Node-compatible profile changes; missing patch fields are preserved. */
public final class CrmProfileDtos {
    private CrmProfileDtos() { }
    /** 客户或活动编辑时经范围过滤的员工、校区或表单候选。
     * @param id 资源公开标识
     * @param name 显示名称
     * @param campusId 关联校区公开标识，可为空
     */
    public record Choice(String id,String name,String campusId) { }
    /** 客户及推广活动编辑选项页。
     * @param items 当前页选项
     * @param total 匹配总数
     * @param page 从1开始的页码
     * @param size 页大小
     */
    public record Options(List<Choice> items,long total,int page,int size) { }
    /** 客户及推广活动编辑选项页。
     * @param items 当前页选项
     * @param total 匹配总数
     * @param page 从1开始的页码
     * @param size 页大小
     */
    /** 限定编辑选项类型和校区的查询条件。
     * @param kind 请求的选项种类，服务按白名单校验
     * @param campusId 可选限定校区公开标识
     * @param page 从1开始的页码
     * @param size 每页条数
     */
    public record OptionsQuery(String kind,String campusId,int page,int size) { }
    /** 保留未提供字段的显式增量修改，不允许任意字段写入。
     * @param patch 非空字段映射；字段白名单、值类型及可清空语义由服务检查
     * @param expectedVersion 当前资源期望版本
     */
    public record Patch(@NotNull Map<String,Object> patch,@Min(1) long expectedVersion) { }
    /** 把线索关联现有客户或据其建立客户的版本条件。
     * @param leadId 线索公开标识
     * @param customerId 可选现有客户标识；省略时据线索建立客户
     * @param expectedVersion 线索期望版本
     */
    public record FromLead(@NotBlank @Size(max=120) String leadId,@Size(max=120) String customerId,
            @Min(1) long expectedVersion) { }
    /** 客户档案联系人及关系投影。
     * @param id 联系人标识
     * @param name 联系人姓名
     * @param relationship 与客户或孩子的关系
     * @param phone 当前授权投影的联系电话
     * @param primary 是否为主要联系人
     */
    public record Contact(String id,String name,String relationship,String phone,boolean primary) { }
    /** 客户可编辑档案及乐观锁版本，不包含关联业务全量历史。
     * @param id 客户公开标识
     * @param name 客户姓名
     * @param grade 年级显示值
     * @param note 客户备注
     * @param campusId 校区公开标识
     * @param ownerId 负责人公开标识
     * @param status 客户状态
     * @param version 当前版本
     * @param contacts 联系人列表
     */
    public record Customer(String id,String name,String grade,String note,String campusId,String ownerId,
            String status,long version,List<Contact> contacts) { }
    /** 线索转客户所需的当前基本资料及版本。
     * @param id 线索公开标识
     * @param name 姓名
     * @param phone 联系电话
     * @param grade 年级
     * @param customerId 已有客户关联，可为空
     * @param version 线索当前版本
     */
    public record LeadProfile(String id,String name,String phone,String grade,String customerId,long version) { }
    /** 线索转客户操作的关联回执。
     * @param record 关联后的客户档案
     * @param leadId 来源线索标识
     * @param leadVersion 关联后的线索版本
     * @param created 是否本次新建客户
     */
    public record Link(Customer record,String leadId,long leadVersion,boolean created) { }
    /** 推广活动档案及当前表单、参与者、推广者关联。
     * @param id 活动公开标识
     * @param name 活动名称
     * @param campusId 校区标识，全局活动可为空
     * @param channel 推广渠道
     * @param description 活动描述
     * @param start 开始时刻
     * @param end 结束时刻
     * @param budgetMinor 预算最小货币单位金额，可为空
     * @param targetCount 目标数量，可为空
     * @param formIds 关联表单公开标识列表
     * @param participantIds 参与员工标识列表
     * @param promoterIds 推广员工标识列表
     * @param status 活动当前状态，发布后为active
     * @param version 当前乐观锁版本
     */
    public record Campaign(String id,String name,String campusId,String channel,String description,
            java.time.Instant start,java.time.Instant end,Long budgetMinor,Integer targetCount,
            List<String> formIds,List<String> participantIds,List<String> promoterIds,String status,long version) { }
}
