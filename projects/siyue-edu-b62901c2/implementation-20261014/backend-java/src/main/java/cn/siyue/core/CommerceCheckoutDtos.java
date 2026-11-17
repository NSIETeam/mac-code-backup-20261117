package cn.siyue.core;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 员工结账、受理与公开商城共享的数据契约；身份权限和金额一致性由各入口服务校验。 */
public final class CommerceCheckoutDtos {
    private CommerceCheckoutDtos() { }

    /** 结账中的一个学员身份输入；已有学员和待核验新学员由服务端分别校验。
     * @param studentId 已有学员公开标识，草稿身份可省略
     * @param name 草稿或核验用学员姓名
     * @param phone 核验手机号，提供时须匹配中国大陆手机格式
     * @param grade 学员年级文本
     */
    public record BuyerInput(@Size(max=120) String studentId, @Size(max=80) String name,
            @Pattern(regexp="\\s*1[3-9][0-9]{9}\\s*") String phone,
            @Size(max=40) String grade) { }
    /** 将一个购买人索引绑定到班级，并可选择该行优惠券。
     * @param buyerIndex 购买人在buyers列表中的零基索引
     * @param classId 目标班级公开标识
     * @param couponId 可选优惠券公开标识，资格及叠加规则由服务端校验
     */
    public record LineInput(@Min(0) @Max(49) int buyerIndex,
            @NotBlank @Size(max=120) String classId,@Pattern(regexp="[A-Za-z0-9_-]{1,120}") String couponId) {
        /** 创建不使用优惠券的班级购买明细；业务校验在服务端提交阶段执行。
         * @param buyerIndex 购买人的零基索引
         * @param classId 班级公开标识
         */
        public LineInput(int buyerIndex,String classId) {this(buyerIndex,classId,null);}
    }
    /** 创建标准、拼团或联报结账请求；报价摘要用于识别展示后变化的价格和条件。
     * @param type standard、group或joint
     * @param promotionId 所选营销活动公开标识，标准购买可省略
     * @param buyers 1至50个购买人身份输入
     * @param items 1至100个购买人和班级组合
     * @param expectedQuoteHash 可选的64字符报价摘要；实际格式及当前报价由服务端复核
     */
    public record CreateInput(@NotBlank @Pattern(regexp="standard|group|joint") String type,
            @Size(max=120) String promotionId, @NotEmpty @Size(max=50) List<@NotNull @Valid BuyerInput> buyers,
            @NotEmpty @Size(max=100) List<@NotNull @Valid LineInput> items,
            @Size(min=64,max=64) String expectedQuoteHash) { }
    /** 员工受理申请时对一个购买人作出的已有学员身份选择。
     * @param buyerIndex 申请购买人列表中的零基索引
     * @param studentId 选定的已有学员公开标识
     */
    public record AcceptStudent(int buyerIndex, @NotBlank @Size(max=120) String studentId) { }
    /** 员工受理结账申请的身份映射及乐观锁版本；未映射草稿按业务规则处理。
     * @param students 购买人到已有学员的可选映射列表
     * @param expectedVersion 当前结账版本，必须大于零
     */
    public record AcceptInput(@Size(max=50) List<@NotNull @Valid AcceptStudent> students,
            @NotNull @Min(1) Long expectedVersion) { }
    /** 员工核对线下现金或银行实收的请求；金额还须等于订单汇总剩余应收。
     * @param amount 本次实收金额，以元计
     * @param method cash现金或bank银行
     * @param reference 本次收款参考号，非空且最多150字符
     * @param expectedVersion 当前结账版本
     */
    public record ConfirmReceiptInput(@NotNull @Min(0) BigDecimal amount,
            @NotBlank @Pattern(regexp="cash|bank") String method,
            @NotBlank @Size(max=150) String reference,
            @NotNull @Min(1) Long expectedVersion) { }
    /** 冻结报价中的一条班级明细；金额均以元计。
     * @param lineIndex 购买明细列表中的零基索引
     * @param buyerIndex 对应购买人的零基索引
     * @param classId 班级公开标识
     * @param courseId 课程公开标识
     * @param campusId 校区公开标识
     * @param baseAmount 优惠前金额
     * @param couponDiscount 该行优惠券减免
     * @param promotionDiscount 该行活动减免
     * @param amount 扣除适用优惠后的金额
     */
    public record QuoteLine(int lineIndex, int buyerIndex, String classId, String courseId,
            String campusId, BigDecimal baseAmount, BigDecimal couponDiscount,
            BigDecimal promotionDiscount, BigDecimal amount) { }
    /** 结账状态与冻结金额回执；员工访问受数据范围限制，公开读取绑定已验签访客身份。
     * @param id 结账公开标识
     * @param type standard、group或joint
     * @param status 当前结账状态
     * @param buyerCount 购买人数
     * @param promotionId 关联活动公开标识，可为空
     * @param quoteHash 创建时冻结的报价摘要
     * @param baseAmount 优惠前总额，以元计
     * @param discountAmount 总优惠金额，以元计
     * @param totalAmount 优惠后总额，以元计
     * @param expiresAt 结账到期的UTC时间点
     * @param version 结账行版本
     * @param lines 冻结班级报价明细
     * @param studentIds 受理后关联的学员公开标识
     * @param orderIds 受理后关联的订单公开标识
     * @param replayed 本回执是否来自已完成幂等请求的重放
     */
    public record CheckoutReceipt(String id, String type, String status, int buyerCount,
            String promotionId, String quoteHash, BigDecimal baseAmount, BigDecimal discountAmount,
            BigDecimal totalAmount, Instant expiresAt, long version, List<QuoteLine> lines,
            List<String> studentIds, List<String> orderIds, boolean replayed) { }
    /** 员工受理申请时可见的候选学员简要身份。
     * @param id 学员公开标识
     * @param name 解密后的学员姓名
     * @param grade 学员年级
     */
    public record IntakeStudent(String id,String name,String grade) { }
    /** 待受理购买人及按身份信息和员工学员范围查得的匹配候选。
     * @param buyerIndex 购买人的零基索引
     * @param name 申请记录的学员姓名
     * @param phone 申请记录的核验手机号
     * @param grade 申请记录的年级
     * @param selectedStudentId 已选学员公开标识，可为空
     * @param matches 当前员工范围内的匹配学员
     */
    public record IntakeBuyer(int buyerIndex,String name,String phone,String grade,String selectedStudentId,
            List<IntakeStudent> matches) { }
    /** 结账受理界面需要的回执和逐购买人身份候选。
     * @param checkout 当前结账回执
     * @param buyers 购买人身份及匹配结果
     */
    public record IntakeDetail(CheckoutReceipt checkout,List<IntakeBuyer> buyers) { }
    /** 员工范围内的结账分页结果。
     * @param items 当前页结账回执
     * @param total 匹配结账总数
     * @param page 从1开始的页码
     * @param size 每页条数
     */
    public record CheckoutList(List<CheckoutReceipt> items, long total, int page, int size) { }
    /** 商城公开班级课表的一节课，兼顾UTC时间点及上海时区显示。
     * @param id 课次公开标识
     * @param startsAt UTC开始时间点
     * @param endsAt UTC结束时间点
     * @param date 上海时区的开课日期
     * @param time 上海时区开始至结束的HH:mm显示文本
     * @param room 教室显示名称
     * @param status 课次当前状态
     */
    public record PublicStoreSession(String id, java.time.Instant startsAt, java.time.Instant endsAt,
            java.time.LocalDate date, String time, String room, String status) { }
    /** 商城公开的可售班级及按当前席位规则计算的余位投影。
     * @param id 班级公开标识
     * @param courseId 所属课程公开标识
     * @param name 班级名称
     * @param campusId 校区公开标识
     * @param campusName 校区显示名称
     * @param durationMinutes 每课时分钟数
     * @param remainingSeats 当前余位估计，实际受理仍在事务内重验
     * @param schedule 公开课表
     */
    public record PublicStoreClass(String id, String courseId, String name, String campusId, String campusName,
            int durationMinutes, int remainingSeats, List<PublicStoreSession> schedule) { }
    /** 商城课程展示快照及当前是否允许提交申请；展示版本与教学、售价不一致时不可申请。
     * @param id 课程公开标识，用作商城产品标识
     * @param name 已发布商城标题
     * @param title 已发布商城标题
     * @param grade 商城展示年级
     * @param subject 商城展示学科
     * @param courseType 商城展示课程类型
     * @param priceCents 展示价格，以分计
     * @param version 商城快照对应课程版本，缺少时为0
     * @param availableForApplication 当前是否满足提交申请的发布条件
     * @param unavailableReason 不可申请时的说明，可申请时为空
     * @param image 展示图片地址
     * @param description 公开课程说明
     * @param classIds 关联有效班级公开标识
     * @param classes 关联班级及其课表和余位
     */
    public record PublicStoreProduct(String id, String name, String title, String grade, String subject,
            String courseType, long priceCents, long version, boolean availableForApplication,
            String unavailableReason, String image, String description, List<String> classIds,
            List<PublicStoreClass> classes) { }
    /** 公开商城当前有效的拼团或联报活动及适用范围。
     * @param id 营销活动公开标识
     * @param type group或joint
     * @param name 活动名称
     * @param startDate 生效日期
     * @param endDate 截止日期
     * @param discount 活动优惠金额，以元计
     * @param threshold 活动金额门槛，以元计
     * @param groupSize 拼团人数条件
     * @param validHours 结账有效小时数
     * @param stackCoupon 是否允许叠加优惠券
     * @param courseIds 活动覆盖的课程公开标识
     * @param classIds 活动覆盖的班级公开标识
     * @param description 活动说明
     * @param enrollmentMode 整团登记或同一学生联报的公开办理说明
     */
    public record PublicStorePromotion(String id, String type, String name, LocalDate startDate,
            LocalDate endDate, BigDecimal discount, BigDecimal threshold, Integer groupSize,
            Integer validHours, boolean stackCoupon, List<String> courseIds, List<String> classIds,
            String description, String enrollmentMode) { }
    /** 匿名商城目录及明确的收款办理能力标记，不表示已接入线上支付。
     * @param products 已上架课程展示及申请可用性
     * @param promotions 当前有效公开活动
     * @param decor 已保存的商城装修文档
     * @param paymentProviderReady 是否配置线上支付能力，当前目录返回false
     * @param checkoutMode 结账办理模式，当前为manual_verified_receipt
     * @param groupMode 拼团办理模式，当前为complete_group_registration
     */
    public record PublicStoreCatalog(List<PublicStoreProduct> products,
            List<PublicStorePromotion> promotions, Map<String,Object> decor,
            boolean paymentProviderReady, String checkoutMode, String groupMode) { }
    /** 公开申请提交前的价格核对结果；不预留席位、不产生收款。
     * @param quoteHash 绑定报价内容及访客身份的摘要
     * @param type 购买类型
     * @param baseAmount 优惠前总额，以元计
     * @param discountAmount 总优惠金额，以元计
     * @param totalAmount 优惠后总额，以元计
     * @param lines 逐购买人班级报价
     * @param paymentProviderReady 是否具备线上支付能力
     */
    public record PublicStoreQuote(String quoteHash, String type, BigDecimal baseAmount,
            BigDecimal discountAmount, BigDecimal totalAmount, List<QuoteLine> lines,
            boolean paymentProviderReady) { }
}
