package cn.siyue.core;

import java.util.List;

/** Evaluates the original Node CRM grants at request time without adding persistent permissions.
 * Resource alternatives match business-scrm-query.cjs; mutation callers name their exact Node operation grant.
 * This helper never replaces repository scope checks or the write path's locked-principal recheck.
 */
final class CrmLegacyAccess {
    private CrmLegacyAccess() { }

    /** Returns whether the current actor may read this resource under its Java or original Node grant. */
    static boolean canRead(Actor actor, String javaPermission) {
        return actor.permissions().contains(javaPermission) || legacyRead(actor, javaPermission);
    }

    /** Requires the Java read permission or one of the original resource-specific query alternatives. */
    static void read(Actor actor, String javaPermission) {
        if (!canRead(actor, javaPermission)) { actor.require(javaPermission); }
    }

    /** Checks only original query alternatives, preserving an existing compound Java permission requirement. */
    static boolean legacyRead(Actor actor, String javaPermission) {
        List<String> alternatives = switch (javaPermission) {
            case "crm.customer.read", "crm.touchpoint.read", "crm.opportunity.read" ->
                List.of("lead.read", "lead.create", "lead.follow");
            case "crm.activity.read" -> List.of("lead.read", "lead.create", "lead.assign");
            case "crm.lead.read" -> List.of("lead.read", "lead.create", "lead.follow", "lead.assign");
            case "crm.form.submission.read" -> List.of("lead.create", "promotion.publish", "store.design");
            case "crm.form.read" -> List.of("lead.create", "promotion.create", "promotion.publish", "store.design");
            case "crm.task.read", "crm.ticket.read" -> List.of("lead.read", "lead.follow", "lead.assign");
            default -> List.of();
        };
        return alternatives.stream().anyMatch(actor.permissions()::contains);
    }

    /** Requires an exact original operation grant or the complete pre-existing Java permission combination. */
    static void operation(Actor actor, String legacyPermission, String... javaPermissions) {
        if (!actor.permissions().contains(legacyPermission)) {
            for (String permission : javaPermissions) { actor.require(permission); }
        }
    }
}
