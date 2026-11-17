package cn.siyue.core;

import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Teaching publication is independent from storefront availability. */
public final class CoursePublicationDtos {
    private CoursePublicationDtos() { }
    /** 发布当前教学草稿时的乐观锁条件；与商城上下架请求独立。
     * @param expectedVersion 调用者读取的当前课程行版本，必须大于零
     */
    public record Input(@Min(1) long expectedVersion) { }
    /** 将课程标识和发布输入绑定为服务端幂等摘要对象。
     * @param courseId 课程公开标识
     * @param input 本次发布的预期版本
     */
    public record Command(String courseId, Input input) { }
    /** 教学发布标记及当前草稿与最近发布版本之间的关系。
     * @param published 当前课程是否标记为已发布
     * @param curriculumVersion 当前教学内容版本
     * @param publishedVersion 最近发布的版本号，无发布记录时为空
     */
    public record Status(boolean published,long curriculumVersion,Long publishedVersion) { }
    /** 不可变教学发布快照中的一个讲次。
     * @param id 源讲次公开标识
     * @param sequence 讲次顺序
     * @param title 发布时讲次标题
     * @param durationMinutes 发布时课时分钟数，可为空
     */
    public record Lesson(String id,int sequence,String title,Integer durationMinutes) { }
    /** 某次教学发布冻结的课程内容；后续草稿编辑不覆盖此快照。
     * @param courseVersion 发布所依据的课程行版本
     * @param curriculumVersion 发布时教学内容版本
     * @param name 发布时课程名称
     * @param courseType 发布时课程类型
     * @param subject 发布时学科
     * @param grade 发布时年级
     * @param price 发布时课程价格，以元计
     * @param publishedAt 发布时间的UTC时间点
     * @param lessons 按顺序保存的发布讲次
     */
    public record Snapshot(long courseVersion,long curriculumVersion,String name,String courseType,
            String subject,String grade,BigDecimal price,Instant publishedAt,List<Lesson> lessons) { }
    /** 当前课程发布状态及最近一次不可变教学快照。
     * @param id 课程公开标识
     * @param version 当前课程行版本，供后续乐观锁使用
     * @param published 当前教学发布标记
     * @param publishedSnapshot 最近发布的教学快照，尚未发布时为空
     */
    public record View(String id,long version,boolean published,Snapshot publishedSnapshot) { }
}
