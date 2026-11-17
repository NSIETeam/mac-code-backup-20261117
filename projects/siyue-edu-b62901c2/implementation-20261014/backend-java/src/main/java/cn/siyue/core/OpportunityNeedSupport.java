package cn.siyue.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared demand identity rules; it never grants customer or academic write access. */
final class OpportunityNeedSupport {
    private OpportunityNeedSupport() { }
    static void intake(Actor actor) { CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.intake", "crm.customer.read"); }
    static int page(Integer value) { int result=value==null?1:value; if(result<1||result>100000)invalid("分页无效"); return result; }
    static int size(Integer value) { int result=value==null?20:value; if(result<1||result>100)invalid("分页大小无效"); return result; }
    static String clean(String value) { return value==null||value.isBlank()?null:value.trim(); }
    static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_INPUT,message); }
    static void stale() { throw new BusinessException(ErrorCode.PREVIEW_STALE,"资料、来源或需求已变化，请重新核对"); }
    static void missing() { throw new BusinessException(ErrorCode.NOT_FOUND,"记录不存在或不在当前授权范围"); }
    static void key(String key) {
        if(key==null||!key.matches("[A-Za-z0-9._:-]{16,128}"))invalid("幂等键格式无效");
    }
    static ScopeSql.Clause scope(Actor actor) {
        return switch(actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1",List.of());
            case "campus" -> new ScopeSql.Clause("o.campus_id IN (SELECT campus_id FROM core_principal_campus WHERE principal_id=? AND deleted=0)",List.of(actor.id()));
            case "own","assigned" -> new ScopeSql.Clause("o.owner_id=?",List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN,"账号范围无效");
        };
    }
    static List<Object> args(Object first,ScopeSql.Clause scope) {
        List<Object> values=new ArrayList<>(); values.add(first); values.addAll(scope.args()); return values;
    }
    static byte[] legacyKey(Long student,String subject,String term) {
        return digest((student==null?"household":student.toString())+"\n"+subject.toLowerCase(Locale.ROOT)+"\n"+term.toLowerCase(Locale.ROOT));
    }
    static byte[] demandKey(IdentityKey identity) {
        StringBuilder key=new StringBuilder("v2:");
        for(String value:List.of(identity.student()==null?"household":identity.student().toString(),
                identity.department().toString(),identity.project()==null?"not_used":identity.project().toString(),
                identity.subject().toLowerCase(Locale.ROOT),identity.term().toLowerCase(Locale.ROOT))) {
            key.append(value.length()).append(':').append(value);
        }
        return digest(key.toString());
    }
    static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    record IdentityKey(Long student,Long department,Long project,String subject,String term) { }
}
