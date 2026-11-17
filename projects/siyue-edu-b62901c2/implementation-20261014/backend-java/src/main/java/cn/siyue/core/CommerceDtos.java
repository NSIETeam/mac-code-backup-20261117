package cn.siyue.core;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Persistent class-level product and promotion contracts. */
public final class CommerceDtos {
    private CommerceDtos() { }
    /** 营销活动选择的课程与其适用班级；课程归属和可见范围由服务端复核。
     * @param courseId 课程公开标识
     * @param classIds 该课程下所选班级的公开标识，至少一项
     */
    public record Selection(@NotBlank @Size(max=120) String courseId,
            @NotEmpty @Size(max=5000) List<@NotBlank @Size(max=120) String> classIds) { }
    /** 按课程维护班级级商品的输入，发布标记不等同教学版本发布。
     * @param courseId 商品对应的课程公开标识
     * @param name 商品名称
     * @param classIds 商品适用的班级公开标识
     * @param published 请求的商城发布状态
     * @param expectedVersion 更新已有商品时的预期行版本，新建可省略
     */
    public record ProductInput(@NotBlank @Size(max=120) String courseId,
            @NotBlank @Size(max=100) String name,
            @NotEmpty @Size(max=5000) List<@NotBlank @Size(max=120) String> classIds,
            boolean published, @Min(1) Long expectedVersion) { }
    /** 依据课程版本上下架；省略班级列表时由服务端选择当前符合条件且在范围内的班级。
     * @param classIds 显式选择的班级公开标识，提供时不可为空列表
     * @param expectedVersion 当前课程行版本，防止按过期课程内容发布
     */
    public record PublicationInput(@Size(min=1,max=1000) List<@NotBlank @Size(max=120) String> classIds,
            @NotNull @Min(1) Long expectedVersion) { }
    /** 员工端商品及当前班级选择和发布状态。
     * @param id 商品公开标识
     * @param courseId 关联课程公开标识
     * @param name 商品名称
     * @param classIds 商品当前生效的班级公开标识
     * @param published 商城上架标记
     * @param version 商品行版本
     */
    public record Product(String id,String courseId,String name,List<String> classIds,
            boolean published,long version) { }
    /** 拼团或联报活动配置；日期、成员类别和课程组合另经业务校验。
     * @param type 活动类型group或joint
     * @param name 活动名称
     * @param description 活动说明，可省略
     * @param startDate 有效期起始日期
     * @param endDate 有效期结束日期
     * @param discountAmount 优惠金额，以元计，最多两位小数
     * @param groupSize 拼团人数条件，可为空
     * @param validHours 活动订单有效小时数，可为空
     * @param stackCoupon 是否允许与优惠券叠加
     * @param participants 允许参与的学员类别
     * @param selections 适用课程及各自班级
     * @param published 请求的活动发布状态
     * @param expectedVersion 更新时的预期活动版本，新建可省略
     */
    public record PromotionInput(@NotBlank @Pattern(regexp="group|joint") String type,
            @NotBlank @Size(max=100) String name,@Size(max=2000) String description,
            @NotNull LocalDate startDate,@NotNull LocalDate endDate,
            @NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2) BigDecimal discountAmount,
            @Min(2) @Max(1000) Integer groupSize,@Min(1) @Max(8760) Integer validHours,
            boolean stackCoupon,@NotEmpty @Size(max=3) List<@Pattern(regexp="在读学员|潜在学员|历史学员") String> participants,
            @NotEmpty @Size(max=20) List<@NotNull @Valid Selection> selections,
            boolean published,@Min(1) Long expectedVersion) { }
    /** 员工端营销活动详情，优惠金额按JSON字符串返回以保留精度。
     * @param id 活动公开标识
     * @param type group拼团或joint联报
     * @param name 活动名称
     * @param description 活动说明
     * @param startDate 有效期起始日期
     * @param endDate 有效期结束日期
     * @param discountAmount 以元计的优惠金额
     * @param groupSize 拼团人数条件
     * @param validHours 订单有效小时数
     * @param stackCoupon 能否叠加优惠券
     * @param participants 允许参与的学员类别
     * @param selections 已保存的课程和班级组合
     * @param published 活动发布标记
     * @param version 活动行版本
     */
    public record Promotion(String id,String type,String name,String description,LocalDate startDate,LocalDate endDate,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal discountAmount,
            Integer groupSize,Integer validHours,boolean stackCoupon,List<String> participants,
            List<Selection> selections,boolean published,long version) { }
    /** 按员工数据范围筛选后的商品分页。
     * @param items 当前页商品
     * @param total 匹配商品总数
     * @param page 从1开始的页码
     * @param pageSize 每页条数
     */
    public record ProductPage(List<Product> items,int total,int page,int pageSize) { }
    /** 按当前查询条件返回的营销活动分页。
     * @param items 当前页活动
     * @param total 匹配活动总数
     * @param page 从1开始的页码
     * @param pageSize 每页条数
     */
    public record PromotionPage(List<Promotion> items,int total,int page,int pageSize) { }
    /** 商品或活动课程选择器中的班级选项。
     * @param id 班级公开标识
     * @param courseId 所属课程公开标识
     * @param name 班级名称
     * @param phase 班级期次描述
     * @param room 教室显示名称
     */
    public record ClassChoice(String id,String courseId,String name,String phase,String room) { }
    /** 班级选择器的范围受限分页结果。
     * @param items 当前页可选班级
     * @param total 匹配班级总数
     * @param page 从1开始的页码
     * @param pageSize 每页条数
     */
    public record ClassPage(List<ClassChoice> items,int total,int page,int pageSize) { }
}
