package cn.siyue.core;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned, one-to-one mappings from explicitly reviewed Node permissions to Java grants. */
public final class LegacyPermissionMappingCatalog {
    public static final String VERSION = "siyue-permission-map-v1";
    private static final Map<String, String> MAPPINGS = Map.ofEntries(
        Map.entry("account.update", "account.update"), Map.entry("audit.read", "audit.read"),
        Map.entry("audit.query", "audit.query"),
        Map.entry("class.batchCreate", "class.batchCreate"), Map.entry("class.create", "class.create"),
        Map.entry("class.read", "class.read"), Map.entry("class.relocate", "class.relocate"),
        Map.entry("consumption.read", "consumption.read"),
        Map.entry("course.create", "course.create"), Map.entry("course.read", "course.read"),
        Map.entry("course.update", "course.update"), Map.entry("course.publish", "course.publish"),
        Map.entry("enrollment.batchCreate", "enrollment.batchCreate"),
        Map.entry("enrollment.batchTransfer", "enrollment.batchTransfer"),
        Map.entry("enrollment.create", "enrollment.create"), Map.entry("enrollment.transfer", "enrollment.transfer"),
        Map.entry("enrollment.withdraw", "enrollment.withdraw"),
        Map.entry("lead.assign", "lead.assign"), Map.entry("lead.conversion.read", "lead.conversion.read"),
        Map.entry("leadPolicy.preview", "leadPolicy.preview"), Map.entry("leadPolicy.publish", "leadPolicy.publish"),
        Map.entry("dashboard.read", "dashboard.read"), Map.entry("lead.read", "lead.read"),
        Map.entry("lead.create", "lead.create"),
        Map.entry("lead.follow", "lead.follow"), Map.entry("lead.release", "lead.release"),
        Map.entry("lead.transfer", "lead.transfer"), Map.entry("order.paymentLink", "order.paymentLink"),
        Map.entry("order.read", "order.read"), Map.entry("order.confirmReceipt", "order.confirmReceipt"),
        Map.entry("promotion.create", "promotion.create"),
        Map.entry("promotion.publish", "promotion.publish"), Map.entry("product.publish", "product.publish"),
        Map.entry("report.read", "report.read"),
        Map.entry("refund.read", "refund.read"), Map.entry("refund.approve", "refund.approve"),
        Map.entry("refund.submit", "refund.submit"),
        Map.entry("sales.activity.read", "sales.activity.read"),
        Map.entry("staff.read", "staff.read"), Map.entry("staff.archive", "staff.archive"),
        Map.entry("teacher.read", "teacher.read"), Map.entry("teacher.archive", "teacher.archive"),
        Map.entry("teacher.metrics.read", "teacher.metrics.read"),
        Map.entry("session.attendance", "session.attendance"), Map.entry("session.move", "session.move"),
        Map.entry("session.record", "session.record"),
        Map.entry("session.reschedule.request", "session.reschedule.request"), Map.entry("settings.update", "settings.update"),
        Map.entry("session.substitute", "session.substitute"), Map.entry("schedule.read", "schedule.read"),
        Map.entry("store.design", "store.design"), Map.entry("store.read", "store.read"),
        Map.entry("student.communicate", "student.communicate"), Map.entry("student.create", "student.create"),
        Map.entry("student.archive", "student.archive"), Map.entry("student.read", "student.read"),
        Map.entry("student.update", "student.update"), Map.entry("settings.read", "settings.read")
    );

    private LegacyPermissionMappingCatalog() { }

    /** 按已审核的一对一表映射旧权限；未知或不可委派码不会降级为宽权限。
     * @param sourceCodes 非null且不重复的旧权限码列表，元素不得为null
     * @return 按目标权限码排序的不可修改映射结果
     * @throws BusinessException 列表为空引用、含重复、未知或不可委派权限时
     * @throws NullPointerException 列表元素为null并触发不可变映射表查询时
     */
    public static List<String> map(List<String> sourceCodes) {
        if (sourceCodes == null || new HashSet<>(sourceCodes).size() != sourceCodes.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "旧权限列表含重复项");
        }
        return sourceCodes.stream().map(code -> {
            String target = MAPPINGS.get(code);
            if (target == null || !AccessPermissionCatalog.isAssignable(target)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "当前版本权限映射不支持某项旧权限");
            }
            return target;
        }).sorted().toList();
    }

    /** 检查映射目录是否仍保持目标唯一性。
     * @return 没有两个旧权限映射到同一目标码时为true
     */
    public static boolean uniqueTargets() { return new HashSet<>(MAPPINGS.values()).size() == MAPPINGS.size(); }

    /** 检查单个旧权限码是否存在已审核映射；此方法不作调用者授权。
     * @param sourceCode 待检查的非null旧权限码
     * @return 存在显式映射时为true
     * @throws NullPointerException 旧权限码为null时
     */
    public static boolean supports(String sourceCode) { return MAPPINGS.containsKey(sourceCode); }

    /** 提供当前映射版本支持的全部旧权限码。
     * @return 不可修改的旧权限键集合
     */
    public static Set<String> sourceCodes() { return MAPPINGS.keySet(); }
}
