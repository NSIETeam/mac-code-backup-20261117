package cn.siyue.core;

import java.util.Set;

/** Canonical allowlist for employee grants used by administration and migration. */
public final class AccessPermissionCatalog {
    private static final Set<String> PERMISSIONS = Set.of(
        "account.update", "attendance.manage", "attendance.read", "audit.read", "audit.query", "dashboard.read", "staff.read", "staff.archive", "teacher.read", "teacher.archive", "teacher.metrics.read", "class.assign", "class.batchCreate", "class.create", "class.read", "class.relocate",
        "class.write", "commerce.read", "commerce.manage", "course.create", "course.read", "course.update", "course.publish", "course.write", "crm.activity.read", "crm.activity.write",
        "crm.attribution.correct.request", "crm.attribution.correct.review", "crm.customer.read",
        "crm.customer.write", "crm.customer.search.manage", "crm.customer.identity.write", "crm.form.publish", "crm.form.read", "crm.form.submission.read",
        "crm.form.submission.review", "crm.form.write", "crm.lead.assign", "crm.lead.follow",
        "lead.conversion.read", "sales.activity.read", "order.paymentLink", "order.read", "order.confirmReceipt",
        "refund.read", "refund.approve", "refund.submit", "lead.read",
        "lead.assign", "lead.create", "lead.follow", "lead.release", "lead.transfer",
        "crm.lead.policy.manage", "crm.lead.read", "crm.lead.write", "crm.opportunity.read",
        "crm.opportunity.write", "crm.opportunity.intake", "crm.opportunity.assign", "crm.opportunity.config.manage", "crm.order.read", "crm.order.write", "crm.receipt.record",
        "crm.refund.approve", "crm.refund.read", "crm.refund.record", "crm.refund.request",
        "crm.task.assign", "crm.task.manage", "crm.task.read", "crm.task.write", "crm.ticket.assign", "crm.ticket.read",
        "crm.ticket.type.manage", "crm.ticket.write", "crm.touchpoint.read", "crm.touchpoint.write",
        "store.design", "store.read", "product.publish", "leadPolicy.preview", "leadPolicy.publish", "promotion.create", "promotion.publish", "consumption.read", "schedule.read",
        "enrollment.batchCreate", "enrollment.batchTransfer", "enrollment.create", "enrollment.manage",
        "enrollment.transfer", "enrollment.withdraw", "report.read", "session.attendance", "session.move",
        "session.record", "session.reschedule.request", "session.substitute", "student.communicate",
        "settings.read", "settings.update", "student.communication.read", "student.archive", "student.create", "student.read", "student.update", "student.write",
        "system.access.manage");

    private AccessPermissionCatalog() { }

    /** 判断权限码是否属于当前Java服务声明的完整权限目录。
     * @param permission 待检查的非null权限码
     * @return 权限目录包含该精确码时为true
     * @throws NullPointerException 传入null权限码时
     */
    public static boolean contains(String permission) { return PERMISSIONS.contains(permission); }

    /** 判断普通授权编辑可否委派该权限；system.access.manage只能走独立可信路径。
     * @param permission 待检查的非null权限码
     * @return 已在目录且不是system.access.manage时为true
     * @throws NullPointerException 传入null权限码时
     */
    public static boolean isAssignable(String permission) {
        return contains(permission) && !"system.access.manage".equals(permission);
    }
}
