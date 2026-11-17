package cn.siyue.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Offline initial-catalog migration rehearsal; not a bean or a remotely callable endpoint. */
public final class LegacyCatalogRehearsal {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final StudentIdentityMutationLock identityLocks;
    private final LegacyMigrationPhaseService transaction;
    private static final Set<String> TABLES=Set.of("core_campus","core_course","core_lesson","core_class",
        "core_principal","core_student");

    /** Uses the same application encryption and a bounded local transaction. */
    public LegacyCatalogRehearsal(JdbcTemplate jdbc,PiiCipher cipher,PlatformTransactionManager manager) {
        this(jdbc,cipher,LegacyMigrationPhaseTransactions.create(manager));
    }

    /** Shares the CLI annotation proxy; SQL, source validation and phase row limits remain local. */
    LegacyCatalogRehearsal(JdbcTemplate jdbc,PiiCipher cipher,LegacyMigrationPhaseService phases) {
        this.jdbc=jdbc;this.cipher=cipher;this.identityLocks=new StudentIdentityMutationLock(jdbc,cipher);
        this.transaction=phases;
    }

    /** Imports an unchanged catalog once; different sources or pre-existing IDs fail without overwrites. */
    public int importCatalog(JsonNode envelope) {
        return importCatalog(envelope, null);
    }

    /** Candidate-only entry point; ordinary rehearsal calls remain restricted to *_test schemas. */
    int importCatalog(JsonNode envelope, LegacyMigrationTargetAuthorization authorization) {
        String database=jdbc.queryForObject("SELECT DATABASE()",String.class);
        LegacyMigrationTargetAuthorization.requireRehearsalOrCandidate(database,jdbc,envelope,authorization);
        if(!"siyue.legacy-business/v1".equals(envelope.path("schema").asText())) throw invalid("快照格式不支持");
        JsonNode state=envelope.path("state");
        LegacyCatalogSourcePreflight.requireMappedHistory(state);
        int count=countRows(state);
        if(count+array(state,"students").size()*(1+3*cipher.blindIndexes("student_name","").size())>=999) throw invalid("单次导入必须少于1000行，需先拆分迁移阶段");
        long revision=envelope.path("revision").longValue();
        if(!envelope.path("revision").isIntegralNumber()||revision<0) throw invalid("快照版本无效");
        String source=state.toString(),hash=hash(source);
        return transaction.execute(() ->{
            var previous=jdbc.query("SELECT source_revision,source_hash,imported_rows FROM core_migration_checkpoint WHERE phase='academic_catalog_v1' FOR UPDATE",
                (rs,row)->new Checkpoint(rs.getLong(1),rs.getString(2),rs.getInt(3)));
            if(!previous.isEmpty()) {
                Checkpoint checkpoint=previous.get(0);
                if(checkpoint.revision()!=revision||!checkpoint.hash().equals(hash)) throw invalid("迁移来源已变化，禁止覆盖原快照");
                return checkpoint.rows();
            }
            jdbc.update("INSERT INTO core_migration_checkpoint(phase,source_revision,source_hash,source_cipher,imported_rows) VALUES('academic_catalog_v1',?,?,?,?)",
                revision,hash,cipher.encrypt(source),count);
            for(JsonNode row:array(state,"campuses")) campus(row);
            for(JsonNode row:LegacyStaffProfileImport.rows(state)) LegacyStaffProfileImport.insert(jdbc,cipher,row);
            for(JsonNode row:array(state,"courses")) course(row);
            for(JsonNode row:array(state,"classes")) classroom(row);
            for(JsonNode row:array(state,"students")) student(row);
            return count;
        });
    }
    private record Checkpoint(long revision,String hash,int rows) { }
    private int countRows(JsonNode state) {
        int count=LegacyStaffProfileImport.rows(state).size()+array(state,"campuses").size()+array(state,"courses").size()+array(state,"classes").size()+array(state,"students").size();
        for(JsonNode c:array(state,"courses")) count=Math.addExact(count,array(c,"lessons").size()+LegacyCoursePublicationImport.rows(c));
        for(JsonNode c:array(state,"classes")) count=Math.addExact(count,array(c,"schedule").size());
        return count;
    }
    private void campus(JsonNode row) {
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher,active,created_at,updated_at) VALUES(?,?,?,?,?)",
            text(row,"id"),cipher.encrypt(text(row,"name")),active(row),date(row,"createdAt"),date(row,"updatedAt"));
    }
    private void course(JsonNode row) {
        String type=text(row,"courseType");
        if(!Set.of("onsite","live").contains(type)) throw invalid("未知课程类型");
        jdbc.update("""
            INSERT INTO core_course(public_id,campus_id,name,course_type,grade,subject,price,version,active,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,text(row,"id"),optionalId("core_campus",row,"campusId"),text(row,"name"),type,
            text(row,"grade"),text(row,"subject"),money(row,"priceCents"),positive(row,"version"),active(row),
            date(row,"createdAt"),date(row,"updatedAt"));
        long course=id("core_course",text(row,"id"));
        for(JsonNode lesson:array(row,"lessons")) jdbc.update("""
            INSERT INTO core_lesson(public_id,course_id,sequence_no,title,created_at,updated_at) VALUES(?,?,?,?,?,?)
            """,text(lesson,"id"),course,positive(lesson,"sequence"),text(lesson,"title"),date(row,"createdAt"),date(row,"updatedAt"));
        LegacyCoursePublicationImport.insert(jdbc,course,row);
    }
    private void classroom(JsonNode row) {
        jdbc.update("""
            INSERT INTO core_class(public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes,version,active,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """,text(row,"id"),id("core_course",text(row,"courseId")),id("core_campus",text(row,"campusId")),
            id("core_principal",text(row,"teacherId")),text(row,"name"),text(row,"room"),positive(row,"capacity"),
            positive(row,"durationMinutes"),positive(row,"version"),active(row),date(row,"createdAt"),date(row,"updatedAt"));
        for(JsonNode session:array(row,"schedule")) session(row,session);
    }
    private void student(JsonNode row) {
        if(!active(row)) throw invalid("停用学员需要补充归档映射");
        String name=text(row,"name"),phone=row.hasNonNull("phone")?text(row,"phone"):null;
        if(phone!=null) identityLocks.lock(name,phone);
        Long owner=optionalId("core_principal",row,"ownerId"),assignee=optionalId("core_principal",row,"assigneeId");
        Long campus=optionalId("core_campus",row,"campusId");
        jdbc.update("""
            INSERT INTO core_student(public_id,name_cipher,phone_cipher,grade,campus_id,owner_id,assignee_id,version,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """,text(row,"id"),cipher.encrypt(name),phone==null?null:cipher.encrypt(phone),
            row.hasNonNull("grade")?text(row,"grade"):null,campus,owner,assignee,
            positive(row,"version"),date(row,"createdAt"),date(row,"updatedAt"));
        new StudentNameIndexRepository(jdbc,cipher).replace(text(row,"id"),name);
        if(phone!=null) {
            long student=id("core_student",text(row,"id"));
            for(var entry:cipher.exactBlindIndexes("student_name",
                    StudentIdentityMutationLock.identityKey(name,phone)).entrySet()) {
                jdbc.update("INSERT INTO core_student_identity_index(student_id,key_id,value_hash) VALUES(?,?,?)",
                    student,entry.getKey(),entry.getValue());
            }
        }
    }
    private void session(JsonNode klass,JsonNode row) {
        if(!"scheduled".equals(text(row,"status"))) throw invalid("课次状态需要专门迁移映射");
        if(row.hasNonNull("campusId")&&!row.get("campusId").equals(klass.get("campusId"))) throw invalid("历史课次校区不同，需补迁移映射");
        if(row.hasNonNull("room")&&!row.get("room").equals(klass.get("room"))) throw invalid("历史课次教室不同，需补迁移映射");
        long lesson=id("core_lesson",text(row,"lessonId"));
        long course=id("core_course",text(klass,"courseId"));
        if(jdbc.queryForObject("SELECT COUNT(id) FROM core_lesson WHERE id=? AND course_id=?",Integer.class,lesson,course)!=1) throw invalid("课次与课程讲次不一致");
        Timestamp[] times=times(row);
        jdbc.update("""
            INSERT INTO core_session(public_id,class_id,lesson_id,teacher_id,starts_at,ends_at,status,created_at,updated_at)
            VALUES(?,?,?,?,?,?,'scheduled',?,?)
            """,text(row,"id"),id("core_class",text(klass,"id")),lesson,
            id("core_principal",text(row.hasNonNull("teacherId")?row:klass,"teacherId")),times[0],times[1],
            date(klass,"createdAt"),date(klass,"updatedAt"));
    }
    private Timestamp[] times(JsonNode row) {
        var matcher=Pattern.compile("(\\d{2}):(\\d{2})[–—-](\\d{2}):(\\d{2})").matcher(text(row,"time"));
        if(!matcher.matches()) throw invalid("课次时间格式无效");
        int sh=Integer.parseInt(matcher.group(1)),sm=Integer.parseInt(matcher.group(2));
        int eh=Integer.parseInt(matcher.group(3)),em=Integer.parseInt(matcher.group(4));
        if(sh>23||sm>59||eh>24||em>59||(eh==24&&em!=0)) throw invalid("课次时钟无效");
        int start=sh*60+sm,end=eh*60+em;
        if(end<=start) throw invalid("课次结束必须晚于开始");
        var day=LocalDate.parse(text(row,"date")).atStartOfDay(ZoneId.of("Asia/Shanghai"));
        return new Timestamp[]{Timestamp.from(day.plusMinutes(start).toInstant()),Timestamp.from(day.plusMinutes(end).toInstant())};
    }
    private long id(String table,String publicId) {
        if(!TABLES.contains(table)) throw invalid("未知目标表");
        var ids=jdbc.query("SELECT id FROM "+table+" WHERE public_id=? AND deleted=0",(rs,row)->rs.getLong(1),publicId);
        if(ids.size()!=1) throw invalid("关联记录未迁移，禁止生成占位业务记录");
        return ids.get(0);
    }
    private Long optionalId(String table,JsonNode row,String field) {
        return row.hasNonNull(field)&&!row.path(field).asText().isBlank()?id(table,text(row,field)):null;
    }
    static JsonNode array(JsonNode row,String key) {
        JsonNode value=row.path(key);if(!value.isArray()) throw invalid("迁移集合格式无效："+key);return value;
    }
    static String text(JsonNode row,String key) {
        JsonNode value=row.path(key);if(!value.isTextual()||value.asText().isBlank()) throw invalid("迁移必填字段无效："+key);return value.asText();
    }
    static long positive(JsonNode row,String key) {
        JsonNode value=row.path(key);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<1) throw invalid("迁移正整数字段无效："+key);return value.longValue();
    }
    static BigDecimal money(JsonNode row,String key) {
        JsonNode value=row.path(key);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0) throw invalid("迁移金额必须为整数分");return BigDecimal.valueOf(value.longValue(),2);
    }
    private static boolean active(JsonNode row) {
        if(!row.path("active").isBoolean()) throw invalid("启停状态缺失");return row.get("active").booleanValue();
    }
    static Timestamp date(JsonNode row,String key) { return Timestamp.from(Instant.parse(text(row,key))); }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable",e); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
