package cn.siyue.core;

/** Server-side aggregate shown by the legacy learning-data page. */
public final class LearningMetricsDtos {
    private LearningMetricsDtos() { }
    /** 旧学习数据页面使用的服务端范围化聚合。
     * @param scope 已授权查询范围
     * @param students 去重学员数
     * @param activeStudents 活跃学员数
     * @param historicalStudents 历史学员数
     * @param activeEnrollments 有效报名数
     * @param consumptions 课消记录数
     */
    public record Summary(String scope, long students, long activeStudents,
            long historicalStudents, long activeEnrollments, long consumptions) { }
}
