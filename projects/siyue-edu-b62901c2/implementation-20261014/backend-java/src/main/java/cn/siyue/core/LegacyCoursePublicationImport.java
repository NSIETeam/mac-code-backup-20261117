package cn.siyue.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/** Preserves the source's last teaching publication, including edited drafts. */
public final class LegacyCoursePublicationImport {
    private LegacyCoursePublicationImport() { }
    public static int rows(JsonNode course) {
        JsonNode snapshot=course.path("publishedSnapshot");
        return snapshot.isObject()?1+snapshot.path("lessons").size():0;
    }
    public static void insert(JdbcTemplate jdbc,long courseId,JsonNode course) {
        long curriculum=course.path("curriculumVersion").asLong(1);
        if(curriculum<1)throw invalid();
        JsonNode snapshot=course.path("publishedSnapshot");boolean published=course.path("published").asBoolean(false);
        jdbc.update("UPDATE core_course SET curriculum_version=?,teaching_published=? WHERE id=?",curriculum,published,courseId);
        if(!snapshot.isObject()) {if(published)throw invalid();return;}
        long version=course.path("publishedVersion").asLong(0),snapshotCurriculum=snapshot.path("curriculumVersion").asLong(1);
        if(version<1||version>course.path("version").asLong()||snapshotCurriculum<1||!snapshot.path("lessons").isArray()||snapshot.path("lessons").isEmpty())throw invalid();
        if(!snapshot.path("priceCents").canConvertToLong()||snapshot.path("priceCents").longValue()<0)throw invalid();
        Timestamp at;try {at=Timestamp.from(Instant.parse(course.path("publishedAt").asText()));}catch(RuntimeException error){throw invalid();}
        jdbc.update("INSERT INTO core_course_publication(course_id,course_version,curriculum_version,name,course_type,subject,grade,price,published_at) VALUES(?,?,?,?,?,?,?,?,?)",
            courseId,version,snapshotCurriculum,text(snapshot,"name"),text(snapshot,"courseType"),text(snapshot,"subject"),text(snapshot,"grade"),BigDecimal.valueOf(snapshot.path("priceCents").longValue(),2),at);
        Long publication=jdbc.queryForObject("SELECT id FROM core_course_publication WHERE course_id=? AND course_version=?",Long.class,courseId,version);
        for(JsonNode lesson:snapshot.path("lessons")) {
            if(lesson.path("sequence").asInt()<1)throw invalid();
            jdbc.update("INSERT INTO core_course_publication_lesson(publication_id,lesson_public_id,sequence_no,title,duration_minutes) VALUES(?,?,?,?,?)",
                publication,text(lesson,"id"),lesson.path("sequence").intValue(),text(lesson,"title"),lesson.hasNonNull("durationMinutes")?lesson.path("durationMinutes").intValue():null);
        }
    }
    private static String text(JsonNode row,String key) {String value=row.path(key).asText();if(value.isBlank())throw invalid();return value;}
    private static BusinessException invalid() {return new BusinessException(ErrorCode.INVALID_INPUT,"历史教学发布版本不完整，不能重建或覆盖来源快照");}
}
