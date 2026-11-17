package cn.siyue.core;

/** Builds fixed receiver qualification predicates from current direct and active-position grants.
 * Explicit denials override both sources, matching AccessRepository; no caller input becomes SQL.
 * Password-change readiness is checked by each receiver query independently of account active state.
 */
final class CrmPrincipalEligibility {
    private CrmPrincipalEligibility() { }

    /** Matches the principal alias p against one compile-time permission, including denial overrides. */
    static String permission(String code) {
        if (!java.util.Set.of("crm.task.read", "crm.task.write", "crm.opportunity.read",
                "crm.opportunity.write", "crm.ticket.write", "crm.lead.follow", "lead.follow", "lead.create").contains(code)) {
            throw new IllegalArgumentException("Unknown receiver permission");
        }
        return "(NOT EXISTS (SELECT d.id FROM core_principal_denied_permission d WHERE d.principal_id=p.id"
            + " AND d.deleted=0 AND d.permission_code='" + code + "') AND (EXISTS ("
            + "SELECT g.id FROM core_principal_permission g WHERE g.principal_id=p.id AND g.deleted=0"
            + " AND g.permission_code='" + code + "') OR EXISTS (SELECT l.id FROM core_principal_position l"
            + " JOIN core_access_position pos ON pos.id=l.position_id AND pos.deleted=0 AND pos.active=1"
            + " JOIN core_access_position_permission pp ON pp.position_id=pos.id AND pp.deleted=0"
            + " WHERE l.principal_id=p.id AND l.deleted=0 AND pp.permission_code='" + code + "')))";
    }

    /** Matches either the existing Java task capability or the original type-specific receiving capability. */
    static String task(String type) {
        String legacy = switch (type) {
            case "marketing" -> permission("lead.follow");
            case "service" -> permission("lead.create");
            default -> "1=0";
        };
        return "((" + permission("crm.task.read") + " AND " + permission("crm.task.write") + ") OR " + legacy + ")";
    }
}
