package cn.siyue.core;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Map;
import java.util.HexFormat;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Collection limits are checked while the form row is locked, including previous revisions. */
@Repository
public class FormCollectionRepository {
    public record Settings(Instant start,Instant end,@Min(1) @Max(1000000) int quantity,
            @Min(1) @Max(100) int maxPerIdentity,@NotBlank @Size(max=100) String source,@Size(max=120) String ownerId) { }
    private final JdbcTemplate jdbc;
    public FormCollectionRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public long formId(String publicId) {
        return jdbc.queryForObject("SELECT id FROM core_crm_form WHERE public_id=? AND deleted=0",Long.class,publicId);
    }
    public Settings read(long formId) {
        return jdbc.query("SELECT s.starts_at,s.ends_at,s.quantity_limit,s.max_per_identity,s.source_name,p.public_id "
            + "FROM core_form_collection_settings s LEFT JOIN core_principal p ON p.id=s.owner_id WHERE s.form_id=? AND s.deleted=0",
            (rs,n)->new Settings(instant(rs.getTimestamp(1)),instant(rs.getTimestamp(2)),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6)),formId)
            .stream().findFirst().orElse(new Settings(null,null,1000,1,"表单获客",null));
    }
    public void save(Actor actor,CrmDtos.MarketingForm form,Settings input) {
        long formId=formId(form.id());validate(input);
        long received=jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_form_submission WHERE form_id=? AND deleted=0",Long.class,formId);
        if(input.quantity()<received) throw invalid("收集份数不能小于已收到份数");
        Long owner=owner(actor,form.campusId(),input.ownerId());
        store(formId,input,owner);
    }
    void store(long formId,Settings input,Long owner) {
        validate(input);jdbc.update("INSERT INTO core_form_collection_settings(form_id,starts_at,ends_at,quantity_limit,max_per_identity,source_name,owner_id) "
            + "VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE starts_at=VALUES(starts_at),ends_at=VALUES(ends_at),quantity_limit=VALUES(quantity_limit),"
            + "max_per_identity=VALUES(max_per_identity),source_name=VALUES(source_name),owner_id=VALUES(owner_id),updated_at=UTC_TIMESTAMP(3)",
            formId,timestamp(input.start()),timestamp(input.end()),input.quantity(),input.maxPerIdentity(),input.source(),owner);
    }
    public void validatePublish(long formId) {
        Settings settings=read(formId);
        if(settings.ownerId()!=null) eligibleOwner(jdbc.queryForObject("SELECT c.public_id FROM core_crm_form f LEFT JOIN core_campus c ON c.id=f.campus_id WHERE f.id=?",String.class,formId),settings.ownerId());
        if(settings.end()!=null&&!settings.end().isAfter(Instant.now())) throw invalid("表单已结束，请先调整收集时间");
    }
    public byte[] admit(CrmFormRepository.PublicDefinition form,Map<String,Object> answers,String visitor) {
        Settings settings=read(form.formRowId());Instant now=Instant.now();
        if(settings.start()!=null&&settings.start().isAfter(now)) throw invalid("表单尚未开始");
        if(settings.end()!=null&&!settings.end().isAfter(now)) throw invalid("表单已结束");
        String number=form.fields().stream().filter(field->field.type().equals("phone")).map(field->answers.get(field.id()))
            .filter(value->value instanceof String&&!((String)value).isBlank()).map(Object::toString).findFirst().orElse(null);
        if(number==null&&(visitor==null||visitor.isBlank())) throw invalid("匿名无手机号表单需要有效访客会话");
        String raw=number==null?"visitor:"+visitor:"phone:"+normalizePhone(number);
        byte[] identity=HexFormat.of().parseHex(LegacyCatalogRehearsal.hash(form.id()+"|"+raw));
        long count=jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_form_submission WHERE form_id=? AND deleted=0",Long.class,form.formRowId());
        if(count>=settings.quantity()) throw invalid("表单收集份数已满");
        long used=jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_form_submission WHERE form_id=? AND identity_hash=? AND deleted=0",Long.class,form.formRowId(),identity);
        if(used>=settings.maxPerIdentity()) throw invalid("已达到该手机号或访客的填写次数上限");return identity;
    }
    private Long owner(Actor actor,String campusId,String publicId) {
        if(publicId==null||publicId.isBlank()) return null;
        if(!publicId.equals(actor.publicId())) actor.requireAny("crm.lead.assign","lead.assign");
        return eligibleOwner(campusId,publicId);
    }
    private Long eligibleOwner(String campusId,String publicId) {
        return jdbc.query("SELECT p.id FROM core_principal p WHERE p.public_id=? AND p.active=1 AND p.deleted=0 "
            + "AND p.archived_at IS NULL AND p.credential_change_required=0 AND p.scope_type<>'assigned' AND ("
            + CrmPrincipalEligibility.permission("lead.follow")+" OR "+CrmPrincipalEligibility.permission("crm.lead.follow")+") AND "
            + "(p.scope_type<>'campus' OR EXISTS (SELECT pc.id FROM core_principal_campus pc JOIN core_campus c ON c.id=pc.campus_id "
            + "WHERE pc.principal_id=p.id AND c.public_id=? AND pc.deleted=0))",(rs,n)->rs.getLong(1),publicId,campusId)
            .stream().findFirst().orElseThrow(()->invalid("指定负责人不存在或不在表单校区"));
    }
    private void validate(Settings value) {
        if(value==null||value.quantity()<1||value.quantity()>1000000||value.maxPerIdentity()<1||value.maxPerIdentity()>100
                ||value.source()==null||value.source().isBlank()||value.source().length()>100
                ||value.start()!=null&&value.end()!=null&&value.start().isAfter(value.end())) throw invalid("表单收集设置无效");
    }
    private static String normalizePhone(String phone) {return phone.replaceAll("[\\s()-]","").replaceFirst("^(?:\\+86|0086)(?=1\\d{10}$)","");}
    private static Instant instant(Timestamp value) {return value==null?null:value.toInstant();}
    private static Timestamp timestamp(Instant value) {return value==null?null:Timestamp.from(value);}
    private BusinessException invalid(String message) {return new BusinessException(ErrorCode.INVALID_INPUT,message);}
}
