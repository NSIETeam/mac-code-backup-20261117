package cn.siyue.core;

import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Preserves Node submission publisher boundaries for callers using original marketing grants.
 * Published revision authors are immutable and imported revisions validate their original publisher.
 * The old superadmin exception needs both a trusted source-role checkpoint and current administration
 * authority; a newly created Java administrator is not treated as an original superadmin.
 */
final class LegacyFormSubmissionAccess {
    private LegacyFormSubmissionAccess() { }

    /** Returns the additional submission predicate, without replacing the form's existing scope. */
    static ScopeSql.Clause visibility(JdbcTemplate jdbc, Actor actor, boolean review) {
        Set<String> modern=review
            ?Set.of("crm.form.read","crm.form.submission.read","crm.form.submission.review")
            :Set.of("crm.form.read","crm.form.submission.read");
        if(actor.permissions().containsAll(modern)||originalAdministrator(jdbc,actor))
            return new ScopeSql.Clause("1=1",List.of());
        String publisher="EXISTS (SELECT pub.id FROM core_crm_form_revision pub "
            +"WHERE pub.id=s.revision_id AND pub.published_by=? AND pub.deleted=0)";
        if(review)return new ScopeSql.Clause(publisher,List.of(actor.id()));
        return new ScopeSql.Clause("("+publisher+" OR (s.status='approved' AND (f.owner_id=? OR EXISTS ("
            +"SELECT current_pub.id FROM core_crm_form_revision current_pub WHERE current_pub.form_id=f.id "
            +"AND current_pub.revision_no=f.current_revision AND current_pub.published_by=? AND current_pub.deleted=0) "
            +"OR EXISTS (SELECT settings.id FROM core_form_collection_settings settings WHERE settings.form_id=f.id "
            +"AND settings.owner_id=? AND settings.deleted=0))))",List.of(actor.id(),actor.id(),actor.id(),actor.id()));
    }

    private static boolean originalAdministrator(JdbcTemplate jdbc,Actor actor) {
        if(!actor.permissions().contains("system.access.manage"))return false;
        Integer count=jdbc.queryForObject("SELECT COUNT(id) FROM core_legacy_identity_migration_checkpoint "
            +"WHERE principal_id=? AND deleted=0 AND JSON_CONTAINS(source_role_ids,JSON_QUOTE('superadmin'))",
            Integer.class,actor.id());
        return count!=null&&count==1;
    }
}
