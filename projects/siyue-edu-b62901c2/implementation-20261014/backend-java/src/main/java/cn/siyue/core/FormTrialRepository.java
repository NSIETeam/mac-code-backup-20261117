package cn.siyue.core;

import cn.siyue.core.FormTrialDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Relational trial slots, immutable publication snapshots and encrypted trial reservations. */
@Repository
public class FormTrialRepository {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final ObjectMapper mapper;
    private record Config(long id, boolean enabled, String campusField, String gradeField,
            String subjectField, String nameField, String phoneField) { }
    private record Slot(long id, String key, long classId, long sessionId, int capacity, long campusId,
            String campusPublicId, String campus, String course, String grade, String subject,
            Instant start, Instant end, String status) { }

    public FormTrialRepository(JdbcTemplate jdbc, PiiCipher cipher, ObjectMapper mapper) {
        this.jdbc=jdbc; this.cipher=cipher; this.mapper=mapper;
    }

    public SlotPage options(Actor actor,CrmDtos.MarketingForm form,int page,int size) {
        ScopeSql.Clause scope=ScopeSql.classes(actor);List<Object> args=new ArrayList<>(List.of(form.campusId()));args.addAll(scope.args());
        String from=" FROM core_session s JOIN core_class c ON c.id=s.class_id JOIN core_course co ON co.id=c.course_id "
            + "JOIN core_campus cp ON cp.id=s.campus_id WHERE cp.public_id=? AND c.active=1 AND c.deleted=0 AND s.deleted=0 "
            + "AND s.status='scheduled' AND s.starts_at>UTC_TIMESTAMP(3) AND cp.active=1 AND cp.deleted=0 "
            + "AND co.active=1 AND co.deleted=0 AND ("+scope.sql()+")";
        long total=jdbc.queryForObject("SELECT COUNT(s.id)"+from,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
        var seats=new SeatAvailabilityRepository(jdbc,cipher);
        List<SlotChoice> rows=jdbc.query("SELECT c.public_id,c.name,s.public_id,co.name,co.grade,co.subject,s.starts_at,s.ends_at,s.id,c.id"+from
            + " ORDER BY s.starts_at,s.id LIMIT ? OFFSET ?",(rs,n)->new SlotChoice(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getString(6),rs.getTimestamp(7).toInstant(),rs.getTimestamp(8).toInstant(),seats.sessionRemaining(rs.getLong(10),rs.getLong(9))),args.toArray());
        return new SlotPage(rows,total,page,size);
    }
    public long lockForm(String formId) {
        return jdbc.queryForObject("SELECT id FROM core_crm_form WHERE public_id=? AND deleted=0 FOR UPDATE",Long.class,formId);
    }
    public long formId(String publicId) {
        return jdbc.queryForObject("SELECT id FROM core_crm_form WHERE public_id=? AND deleted=0",Long.class,publicId);
    }
    public void advanceFormVersion(String formId) {
        jdbc.update("UPDATE core_crm_form SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE public_id=?",formId);
    }

    public Configuration configuration(long formId, long revision) {
        Config config=config(formId,revision);
        if(config==null) return null;
        List<SlotInput> slots=jdbc.query("SELECT t.slot_key,c.public_id class_public_id,s.public_id session_public_id,t.capacity "
            + "FROM core_form_trial_slot t JOIN core_class c ON c.id=t.class_id JOIN core_session s ON s.id=t.session_id "
            + "WHERE t.config_id=? AND t.deleted=0 ORDER BY t.sequence_no,t.id",(rs,n)->new SlotInput(rs.getString(1),
                rs.getString(2),rs.getString(3),rs.getInt(4)),config.id());
        return new Configuration(config.enabled(),config.campusField(),config.gradeField(),config.subjectField(),
            config.nameField(),config.phoneField(),slots);
    }

    public void saveDraft(Actor actor, CrmDtos.MarketingForm form, Configuration input) {
        if(input==null) {
            jdbc.update("UPDATE core_form_trial_config SET enabled=0,updated_at=UTC_TIMESTAMP(3) WHERE form_id=? AND revision_no=0",
                formId(form.id()));return;
        }
        validateFields(form,input);
        long formId=jdbc.queryForObject("SELECT id FROM core_crm_form WHERE public_id=? FOR UPDATE",Long.class,form.id());
        List<Slot> resolved=new ArrayList<>();
        for(SlotInput slot:input.slots()) resolved.add(resolveSlot(actor,form,slot));
        Config old=config(formId,0);
        if(old==null) jdbc.update("INSERT INTO core_form_trial_config(public_id,form_id,revision_no,enabled,campus_field_id,"
            + "grade_field_id,subject_field_id,name_field_id,phone_field_id) VALUES(?,?,0,?,?,?,?,?,?)",
            "TRCFG_"+UUID.randomUUID(),formId,input.enabled(),input.campusFieldId(),input.gradeFieldId(),
            input.subjectFieldId(),input.nameFieldId(),input.phoneFieldId());
        else jdbc.update("UPDATE core_form_trial_config SET enabled=?,campus_field_id=?,grade_field_id=?,"
            + "subject_field_id=?,name_field_id=?,phone_field_id=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
            input.enabled(),input.campusFieldId(),input.gradeFieldId(),input.subjectFieldId(),input.nameFieldId(),input.phoneFieldId(),old.id());
        long configId=config(formId,0).id();
        jdbc.update("UPDATE core_form_trial_slot SET deleted=1,updated_at=UTC_TIMESTAMP(3) WHERE config_id=?",configId);
        for(int n=0;n<input.slots().size();n++) {
            SlotInput inputSlot=input.slots().get(n); Slot slot=resolved.get(n);
            jdbc.update("INSERT INTO core_form_trial_slot(public_id,config_id,slot_key,class_id,session_id,capacity,sequence_no) "
                + "VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE class_id=VALUES(class_id),session_id=VALUES(session_id),"
                + "capacity=VALUES(capacity),sequence_no=VALUES(sequence_no),deleted=0,updated_at=UTC_TIMESTAMP(3)",
                "TRSLOT_"+UUID.randomUUID(),configId,inputSlot.id(),slot.classId(),slot.sessionId(),inputSlot.capacity(),n);
        }
    }

    public void publish(Actor actor, CrmDtos.MarketingForm form, long revision) {
        long formId=jdbc.queryForObject("SELECT id FROM core_crm_form WHERE public_id=?",Long.class,form.id());
        Configuration draft=configuration(formId,0);
        if(draft==null||!draft.enabled()) return;
        validateFields(form,draft);
        for(SlotInput slot:draft.slots()) resolveSlot(actor,form,slot);
        jdbc.update("INSERT INTO core_form_trial_config(public_id,form_id,revision_no,enabled,campus_field_id,grade_field_id,"
            + "subject_field_id,name_field_id,phone_field_id) SELECT ?,form_id,?,enabled,campus_field_id,grade_field_id,"
            + "subject_field_id,name_field_id,phone_field_id FROM core_form_trial_config WHERE form_id=? AND revision_no=0",
            "TRCFG_"+UUID.randomUUID(),revision,formId);
        long published=config(formId,revision).id(), draftId=config(formId,0).id();
        jdbc.update("INSERT INTO core_form_trial_slot(public_id,config_id,slot_key,class_id,session_id,capacity,sequence_no) "
            + "SELECT CONCAT('TRSLOT_',UUID()),?,slot_key,class_id,session_id,capacity,sequence_no "
            + "FROM core_form_trial_slot WHERE config_id=? AND deleted=0",published,draftId);
    }

    public PublicTrial publicTrial(CrmFormRepository.PublicDefinition form) {
        Config config=config(form.formRowId(),form.revision());
        if(config==null||!config.enabled()) return null;
        return new PublicTrial(true,config.campusField(),config.gradeField(),config.subjectField(),config.nameField(),
            config.phoneField(),available(form,config,null));
    }

    public BookingReceipt book(CrmFormRepository.PublicDefinition form, String submissionId,
            Map<String,Object> answers, String slotKey) {
        if(slotKey==null) return null;
        BookingReceipt existing=receipt(submissionId);
        if(existing!=null) return existing;
        Config config=config(form.formRowId(),form.revision());
        if(config==null||!config.enabled()) throw invalid("此表单没有可用试课时段");
        Slot slot=slots(config.id()).stream().filter(value->value.key().equals(slotKey)).findFirst()
            .orElseThrow(()->invalid("试课时段不存在"));
        lockSlot(slot);
        Slot refreshed=slots(config.id()).stream().filter(value->value.key().equals(slotKey)).findFirst()
            .orElseThrow(()->invalid("试课时段已变化"));
        if(refreshed.campusId()!=slot.campusId()||refreshed.classId()!=slot.classId()) throw invalid("试课校区或班级已变化，请重试");
        slot=refreshed;
        PublicSlot publicSlot=available(form,config,answers).stream().filter(value->value.id().equals(slotKey)).findFirst()
            .orElseThrow(()->new BusinessException(ErrorCode.ALREADY_EXISTS,"试课时段不匹配、已结束或名额已满"));
        String name=answer(answers,config.nameField()), phone=answer(answers,config.phoneField());
        if(name.isBlank()||name.length()>100||phone.isBlank()||phone.length()>32) throw invalid("试课预约需要学生姓名和家长电话");
        String id="TRIAL_"+UUID.randomUUID();
        int inserted=jdbc.update("INSERT INTO core_trial_booking(public_id,form_id,revision_id,submission_id,slot_id,class_id,session_id,"
            + "campus_id,name_cipher,phone_cipher,grade,subject,slot_snapshot_cipher,booked_at,publisher_id) "
            + "SELECT ?,?,?,sub.id,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),r.published_by "
            + "FROM core_crm_form_submission sub JOIN core_crm_form_revision r ON r.id=sub.revision_id "
            + "WHERE sub.public_id=? AND sub.form_id=? AND sub.revision_id=?",
            id,form.formRowId(),form.revisionId(),slot.id(),slot.classId(),slot.sessionId(),slot.campusId(),
            cipher.encrypt(name),cipher.encrypt(phone),answer(answers,config.gradeField()),answer(answers,config.subjectField()),
            cipher.encrypt(json(publicSlot)),submissionId,form.formRowId(),form.revisionId());
        if(inserted!=1) throw invalid("试课提交关联无效");
        return new BookingReceipt(id,"confirmed",publicSlot);
    }

    public BookingReceipt receipt(String submissionId) {
        return jdbc.query("SELECT b.public_id,b.status,b.slot_snapshot_cipher FROM core_trial_booking b "
            + "JOIN core_crm_form_submission s ON s.id=b.submission_id WHERE s.public_id=? AND b.deleted=0",
            (rs,n)->new BookingReceipt(rs.getString(1),rs.getString(2),readSlot(rs.getString(3))),submissionId)
            .stream().findFirst().orElse(null);
    }

    private List<PublicSlot> available(CrmFormRepository.PublicDefinition form, Config config, Map<String,Object> answers) {
        List<PublicSlot> result=new ArrayList<>();
        SeatAvailabilityRepository seats=new SeatAvailabilityRepository(jdbc,cipher);
        for(Slot slot:slots(config.id())) {
            if(!slot.status().equals("scheduled")||!slot.start().isAfter(Instant.now())) continue;
            if(answers!=null&&!matches(form,config,slot,answers)) continue;
            SeatAvailabilityRepository.SessionSeats capacity=seats.sessions(slot.classId()).stream()
                .filter(value->value.id()==slot.sessionId()).findFirst().orElseThrow();
            int remaining=Math.min(slot.capacity()-slotBooked(form.formRowId(),slot.key(),slot.sessionId()),
                capacity.capacity()+capacity.virtualSeats()-capacity.roster()-seats.trialOccupied(slot.sessionId(),seats.roster(slot.sessionId())));
            if(remaining<=0) continue;
            var start=slot.start().atZone(SHANGHAI);var end=slot.end().atZone(SHANGHAI);
            String time=start.format(DateTimeFormatter.ofPattern("HH:mm"))+"–"+end.format(DateTimeFormatter.ofPattern("HH:mm"));
            result.add(new PublicSlot(slot.key(),slot.campusPublicId(),slot.campus(),slot.course(),AcademicGradeNormalizer.normalize(slot.grade()),slot.subject(),
                start.toLocalDate(),time,remaining));
        }
        return List.copyOf(result);
    }

    private int slotBooked(long formId,String slotKey,long sessionId) {
        List<String> identities=jdbc.query("SELECT b.name_cipher,b.phone_cipher FROM core_trial_booking b "
            + "JOIN core_form_trial_slot s ON s.id=b.slot_id WHERE b.form_id=? AND s.slot_key=? AND b.session_id=? "
            + "AND b.status='confirmed' AND b.deleted=0",(rs,n)->SeatAvailabilityRepository.identity(
                cipher.decrypt(rs.getString(1)),cipher.decrypt(rs.getString(2))),formId,slotKey,sessionId);
        return new HashSet<>(identities).size();
    }

    private boolean matches(CrmFormRepository.PublicDefinition form, Config config, Slot slot, Map<String,Object> answers) {
        String campus=config.campusField()==null?form.campusId():answer(answers,config.campusField());
        return (slot.campusPublicId().equals(campus)||slot.campus().equals(campus))
            && grade(slot.grade()).equals(grade(answer(answers,config.gradeField())))
            && slot.subject().equals(answer(answers,config.subjectField()));
    }

    private Slot resolveSlot(Actor actor,CrmDtos.MarketingForm form,SlotInput input) {
        ScopeSql.Clause scope=ScopeSql.classes(actor);List<Object> args=new ArrayList<>(List.of(input.classId(),input.sessionId()));
        args.addAll(scope.args());
        List<Long> classes=jdbc.query("SELECT c.id FROM core_class c JOIN core_session s ON s.class_id=c.id "
            + "JOIN core_campus cp ON cp.id=c.campus_id JOIN core_course co ON co.id=c.course_id "
            + "WHERE c.public_id=? AND s.public_id=? AND c.active=1 AND c.deleted=0 AND s.deleted=0 "
            + "AND s.status='scheduled' AND s.starts_at>UTC_TIMESTAMP(3) AND cp.active=1 AND cp.deleted=0 "
            + "AND co.active=1 AND co.deleted=0 AND ("+scope.sql()+")",(rs,n)->rs.getLong(1),args.toArray());
        if(classes.size()!=1) throw invalid("试课时段不存在、已结束或不在授权范围");
        Slot slot=jdbc.query(slotSelect()+" WHERE s.public_id=?",this::mapSlot,input.sessionId()).get(0);
        if(!form.campusId().equals(slot.campusPublicId())) throw invalid("试课时段必须属于表单校区");
        return slot;
    }

    private void validateFields(CrmDtos.MarketingForm form, Configuration input) {
        if(input==null||input.slots()==null||input.slots().isEmpty()||input.slots().size()>100) throw invalid("试课配置无效");
        Set<String> keys=new HashSet<>();for(SlotInput slot:input.slots()) {
            if(slot==null||slot.capacity()<1||slot.capacity()>1000||!keys.add(slot.id())) throw invalid("试课时段重复或容量无效");
        }
        requiredField(form,input.gradeFieldId(),Set.of("single","dropdown","text"));
        requiredField(form,input.subjectFieldId(),Set.of("single","dropdown","text"));
        requiredField(form,input.nameFieldId(),Set.of("name","text"));requiredField(form,input.phoneFieldId(),Set.of("phone"));
        if(input.campusFieldId()!=null) requiredField(form,input.campusFieldId(),Set.of("single","dropdown"));
    }

    private void requiredField(CrmDtos.MarketingForm form,String id,Set<String> types) {
        if(form.fields().stream().noneMatch(field->field.id().equals(id)&&field.required()&&types.contains(field.type())))
            throw invalid("试课字段必须关联表单中的必填兼容字段");
    }

    private Config config(long formId,long revision) {
        return jdbc.query("SELECT id,enabled,campus_field_id,grade_field_id,subject_field_id,name_field_id,phone_field_id "
            + "FROM core_form_trial_config WHERE form_id=? AND revision_no=? AND deleted=0",(rs,n)->new Config(rs.getLong(1),
                rs.getBoolean(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)),formId,revision)
            .stream().findFirst().orElse(null);
    }

    private List<Slot> slots(long configId) {
        return jdbc.query(slotSelect().replace("0 slot_row_id,'' slot_key,0 slot_capacity", "t.id slot_row_id,t.slot_key,t.capacity slot_capacity")
            + " JOIN core_form_trial_slot t ON t.session_id=s.id AND t.class_id=c.id "
            + "WHERE t.config_id=? AND t.deleted=0 AND c.active=1 AND c.deleted=0 AND s.deleted=0 "
            + "AND co.active=1 AND co.deleted=0 AND cp.active=1 AND cp.deleted=0 ORDER BY t.sequence_no,t.id",this::mapSlot,configId);
    }

    private String slotSelect() {
        return "SELECT 0 slot_row_id,'' slot_key,0 slot_capacity,c.id class_id,s.id session_id,c.campus_id,"
            + "cp.public_id campus_public_id,cp.name_cipher,co.name course_name,co.grade,co.subject,s.starts_at,s.ends_at,s.status "
            + "FROM core_session s JOIN core_class c ON c.id=s.class_id JOIN core_course co ON co.id=c.course_id "
            + "JOIN core_campus cp ON cp.id=c.campus_id";
    }

    private Slot mapSlot(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
        return new Slot(rs.getLong("slot_row_id"),rs.getString("slot_key"),rs.getLong("class_id"),rs.getLong("session_id"),
            rs.getInt("slot_capacity"),rs.getLong("campus_id"),rs.getString("campus_public_id"),cipher.decrypt(rs.getString("name_cipher")),
            rs.getString("course_name"),rs.getString("grade"),rs.getString("subject"),rs.getTimestamp("starts_at").toInstant(),
            rs.getTimestamp("ends_at").toInstant(),rs.getString("status"));
    }

    private void lockSlot(Slot slot) {
        jdbc.queryForObject("SELECT id FROM core_campus WHERE id=? FOR UPDATE",Long.class,slot.campusId());
        jdbc.queryForObject("SELECT id FROM core_class WHERE id=? FOR UPDATE",Long.class,slot.classId());
        jdbc.queryForObject("SELECT id FROM core_session WHERE id=? FOR UPDATE",Long.class,slot.sessionId());
    }
    private String answer(Map<String,Object> answers,String id) { Object value=answers.get(id);return value instanceof String text?text.trim():""; }
    private String grade(String value) { return AcademicGradeNormalizer.normalize(value); }
    private String json(Object value) { try{return mapper.writeValueAsString(value);}catch(JsonProcessingException error){throw new IllegalStateException(error);} }
    private PublicSlot readSlot(String encrypted) { try{return mapper.readValue(cipher.decrypt(encrypted),PublicSlot.class);}catch(JsonProcessingException error){throw new IllegalStateException(error);} }
    private BusinessException invalid(String text) { return new BusinessException(ErrorCode.INVALID_INPUT,text); }
}
