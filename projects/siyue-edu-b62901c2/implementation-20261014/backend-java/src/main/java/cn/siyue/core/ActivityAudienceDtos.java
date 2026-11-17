package cn.siyue.core;

import cn.siyue.core.CrmDtos.TaskSourceRef;
import java.time.Instant;
import java.util.List;

/** Acquisition evidence rows, deliberately distinct from household or demand counts. */
public final class ActivityAudienceDtos {
    private ActivityAudienceDtos() { }
    /** 活动获客证据项，以线索/触点来源而非家庭去重计数，附客户、学员、需求及任务证据标记。 */
    public record Item(String key, TaskSourceRef source, String customerId, String customerName,
            String studentId, String studentName, String subject, String term, String ownerId,
            Instant occurredAt, boolean hasTaskEvidence) { }
    /** 活动获客证据分页，unit明确计数单位，activityId保留当前活动上下文。 */
    public record Page(List<Item> items, long total, int page, int size, String unit, String activityId) { }
    /** 获客来源类型、客户、时间和任务关联状态筛选，默认第1页每页20条。 */
    public record Query(String sourceType, String customerId, Instant fromAt, Instant untilAt, String taskState,
            Integer page, Integer size) {
        /**
         * 补齐获客证据分页，保留原来源、时间和任务筛选。
         * @param sourceType 来源类型筛选
         * @param customerId 客户公开标识筛选
         * @param fromAt 发生时间起点
         * @param untilAt 发生时间终点
         * @param taskState 任务关联状态筛选
         * @param page 页码，null使用1
         * @param size 每页条数，null使用20
         */
        public Query { page = page == null ? 1 : page; size = size == null ? 20 : size; }
    }
}
