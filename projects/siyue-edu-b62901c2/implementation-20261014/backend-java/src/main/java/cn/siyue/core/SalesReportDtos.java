package cn.siyue.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/** Stable payloads for assignment-cohort sales reporting and scoped employee activity. */
public final class SalesReportDtos {
    private SalesReportDtos() { }
    /** 按分配队列口径计算的员工销售指标，金额和成交耗时只取有证据的事实。
     * @param employeeId 员工公开标识
     * @param assignedUnique 去重分配线索数
     * @param convertedUnique 去重成交线索数
     * @param conversionRate 成交比例
     * @param overdueUnique 去重逾期线索数
     * @param overdueRate 逾期比例
     * @param lowSample 样本是否低于阈值
     * @param minimumSample 采用的最低样本阈值
     * @param receivedAmount 归属实收金额
     * @param refundedAmount 归属实退金额
     * @param netReceivedAmount 实收减实退金额
     * @param averageHoursToConversion 有完整时点证据的平均成交小时数
     * @param conversionDurationCoverage 可计算成交耗时的记录数量
     */
    public record Metric(String employeeId, long assignedUnique, long convertedUnique,
            BigDecimal conversionRate, long overdueUnique, BigDecimal overdueRate, boolean lowSample,
            int minimumSample, BigDecimal receivedAmount, BigDecimal refundedAmount,
            BigDecimal netReceivedAmount, BigDecimal averageHoursToConversion,
            long conversionDurationCoverage) { }
    /** 同一授权范围和时间窗的员工销售指标集合。
     * @param items 员工指标列表
     * @param scope 查询视图范围
     * @param from 查询起始时刻
     * @param to 查询截止时刻
     * @param cohort 采用的分配队列口径标识
     * @param cohortDescription 该口径的解释
     */
    public record Report(List<Metric> items, String scope, Instant from, Instant to,
            String cohort, String cohortDescription) { }
    /** 销售活动审计的最小可见投影。
     * @param id 事件标识
     * @param at 事件时刻
     * @param employeeId 员工公开标识
     * @param leadId 关联线索公开标识
     * @param action 活动操作码
     * @param outcome 事件结果
     */
    public record Activity(String id, Instant at, String employeeId, String leadId,
            String action, String outcome) { }
    /** 授权员工跟进历史投影。
     * @param id 跟进记录标识
     * @param at 跟进时刻
     * @param employeeId 跟进员工标识
     * @param leadId 线索标识
     * @param channel 跟进渠道
     * @param note 跟进说明
     * @param nextFollowAt 可选下次跟进时刻
     */
    public record Followup(String id, Instant at, String employeeId, String leadId,
            String channel, String note, Instant nextFollowAt) { }
    /** 销售活动审计的最小可见投影。
     * @param id 事件标识
     * @param at 事件时刻
     * @param employeeId 员工公开标识
     * @param leadId 关联线索公开标识
     * @param action 活动操作码
     * @param outcome 事件结果
     */
    /** 使用游标读取的销售活动页。
     * @param items 当前页活动
     * @param hasMore 是否仍有后续数据
     * @param nextCursor 后续页游标，无后续时可为空
     */
    public record ActivityPage(List<Activity> items, boolean hasMore, String nextCursor) { }
    /** 销售活动审计的最小可见投影。
     * @param id 事件标识
     * @param at 事件时刻
     * @param employeeId 员工公开标识
     * @param leadId 关联线索公开标识
     * @param action 活动操作码
     * @param outcome 事件结果
     */
    /** 服务内使用的销售活动时刻查询条件。
     * @param employeeId 可选员工标识
     * @param action 可选活动操作码
     * @param leadId 可选线索标识
     * @param from 起始时刻
     * @param to 截止时刻
     * @param limit 最大返回条数
     * @param cursor 可选后续页游标
     */
    public record ActivityQuery(String employeeId, String action, String leadId, Instant from,
            Instant to, int limit, String cursor) { }
    /** 销售活动审计的最小可见投影。
     * @param id 事件标识
     * @param at 事件时刻
     * @param employeeId 员工公开标识
     * @param leadId 关联线索公开标识
     * @param action 活动操作码
     * @param outcome 事件结果
     */
    /** HTTP销售活动日期输入，由服务转换为业务时区的时刻边界。
     * @param employeeId 可选员工标识
     * @param action 可选活动操作码
     * @param leadId 可选线索标识
     * @param from 可选起始当地日期
     * @param to 可选截止当地日期
     * @param limit 可选最大返回条数
     * @param cursor 可选后续页游标
     */
    public record ActivityRequest(String employeeId, String action, String leadId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, Integer limit, String cursor) { }
    /** 销售历史通用游标页。
     * @param <T> 当前历史资源的投影类型
     * @param items 当前页记录
     * @param hasMore 是否有后续记录
     * @param nextCursor 后续页游标
     */
    public record Page<T>(List<T> items, boolean hasMore, String nextCursor) { }
}
