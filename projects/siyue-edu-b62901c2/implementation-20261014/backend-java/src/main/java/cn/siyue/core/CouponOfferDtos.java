package cn.siyue.core;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 优惠券模板维护、版本化发布和范围选项；金额在响应中使用十进制字符串。 */
public final class CouponOfferDtos {
    private CouponOfferDtos() { }
    /** 创建或编辑优惠券的完整业务配置，关联课程与有效期由服务核验。
     * @param name 非空券名，至多100字符
     * @param description 可选描述，至多3000字符
     * @param template 通用优惠、续费优惠、老带新优惠或双人同行优惠
     * @param campusId 可选适用校区公开标识
     * @param startDate 非空开始日期
     * @param endDate 非空结束日期，不得早于开始日期
     * @param discountAmount 正优惠金额，最多两位小数且不超过十亿元
     * @param thresholdAmount 非负使用门槛，最多两位小数且不超过十亿元
     * @param total 发行总量，1至1000000
     * @param maxPerUser 每用户上限，1至1000
     * @param courseIds 适用课程公开标识列表，最多100项
     * @param expectedVersion 编辑时的期望版本；创建时可省略
     */
    public record Input(@NotBlank @Size(max=100) String name,@Size(max=3000) String description,
            @NotBlank @Pattern(regexp="通用优惠|续费优惠|老带新优惠|双人同行优惠") String template,
            @Size(max=120) String campusId,@NotNull LocalDate startDate,@NotNull LocalDate endDate,
            @NotNull @DecimalMin("0.01") @DecimalMax("1000000000") @Digits(integer=10,fraction=2) BigDecimal discountAmount,
            @NotNull @DecimalMin("0") @DecimalMax("1000000000") @Digits(integer=10,fraction=2) BigDecimal thresholdAmount,
            @Min(1) @Max(1000000) int total,@Min(1) @Max(1000) int maxPerUser,
            @NotNull @Size(max=100) List<@NotBlank @Size(max=120) String> courseIds,@Min(1) Long expectedVersion) { }
    /** 发布优惠券时的乐观锁输入。
     * @param expectedVersion 至少为1的当前优惠券版本
     */
    public record Publication(@Min(1) long expectedVersion) { }
    /** 优惠券配置和使用数量投影，领取与直接使用分别计数。
     * @param id 优惠券公开标识
     * @param name 券名称
     * @param description 券说明
     * @param template 业务模板类型
     * @param campusId 适用校区公开标识，可为空
     * @param startDate 生效日期
     * @param endDate 截止日期
     * @param discountAmount 优惠金额，以字符串序列化
     * @param thresholdAmount 消费门槛，以字符串序列化
     * @param total 发行上限
     * @param maxPerUser 单用户领取限制
     * @param courseIds 适用课程公开标识列表
     * @param published 是否已发布
     * @param version 当前版本
     * @param claimed 已领取数量
     * @param directUses 不经领取令牌的直接使用次数
     */
    public record Offer(String id,String name,String description,String template,String campusId,LocalDate startDate,LocalDate endDate,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal discountAmount,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal thresholdAmount,
            int total,int maxPerUser,List<String> courseIds,boolean published,long version,long claimed,long directUses) { }
    /** 券配置中允许选择的课程或校区。
     * @param id 候选资源公开标识
     * @param name 候选显示名称
     */
    public record Choice(String id,String name) { }
    /** 优惠券配置可用课程及校区的范围查询结果。
     * @param courses 当前页可选课程
     * @param campuses 可选校区
     * @param total 匹配课程总数
     * @param page 从1开始的页码
     * @param size 课程页大小
     */
    public record Options(List<Choice> courses,List<Choice> campuses,long total,int page,int size) { }
    /** 优惠券分页结果。
     * @param items 当前页优惠券
     * @param total 匹配券总数
     * @param page 从1开始的页码
     * @param pageSize 页大小
     */
    public record Page(List<Offer> items,long total,int page,int pageSize) { }
}
