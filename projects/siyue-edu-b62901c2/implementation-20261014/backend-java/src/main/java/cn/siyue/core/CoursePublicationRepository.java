package cn.siyue.core;

import cn.siyue.core.CoursePublicationDtos.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Normalized, append-only teaching versions; caller holds the course lock. */
@Repository
public class CoursePublicationRepository {
    private final JdbcTemplate jdbc;
    private final CoursePublicationCache cache;
    public CoursePublicationRepository(JdbcTemplate jdbc,CoursePublicationCache cache) { this.jdbc=jdbc;this.cache=cache; }
    public record Reference(String id,long version,boolean published,Long publicationId) { }
    public Reference reference(AcademicCatalogRepository.CourseStatusRow current) {
        boolean published=Boolean.TRUE.equals(jdbc.queryForObject("SELECT teaching_published FROM core_course WHERE id=?",Boolean.class,current.id()));
        List<Long> ids=jdbc.query("SELECT id FROM core_course_publication WHERE course_id=? ORDER BY course_version DESC LIMIT 1",(rs,row)->rs.getLong(1),current.id());
        return new Reference(current.publicId(),current.version(),published,ids.isEmpty()?null:ids.get(0));
    }
    public View view(AcademicCatalogRepository.CourseStatusRow current) {
        Reference reference=reference(current);
        return new View(reference.id(),reference.version(),reference.published(),reference.publicationId()==null?null:snapshot(reference.publicationId()));
    }
    public Snapshot snapshot(long id) {return cache.get(id,()->loadSnapshot(id));}
    private Snapshot loadSnapshot(long id) {
        List<Lesson> lessons=jdbc.query("SELECT lesson_public_id,sequence_no,title,duration_minutes FROM core_course_publication_lesson WHERE publication_id=? ORDER BY sequence_no",
            (rs,row)->new Lesson(rs.getString(1),rs.getInt(2),rs.getString(3),rs.getObject(4,Integer.class)),id);
        return jdbc.queryForObject("SELECT course_version,curriculum_version,name,course_type,subject,grade,price,published_at FROM core_course_publication WHERE id=?",
            (rs,row)->new Snapshot(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getBigDecimal(7),rs.getTimestamp(8).toInstant(),lessons),id);
    }
    public View publish(Actor actor,AcademicCatalogRepository.CourseStatusRow current,Input input) {
        if(current.version()!=input.expectedVersion())throw new BusinessException(ErrorCode.VERSION_CONFLICT,"课程版本已变化，请刷新后发布");
        if(!current.active())throw new BusinessException(ErrorCode.INVALID_INPUT,"课程已停用");
        View before=view(current);
        if(before.published())throw new BusinessException(ErrorCode.ALREADY_EXISTS,"当前教学版本已发布");
        Integer lessons=jdbc.queryForObject("SELECT COUNT(*) FROM core_lesson WHERE course_id=? AND deleted=0",Integer.class,current.id());
        if(lessons==null||lessons<1||lessons>200)throw new BusinessException(ErrorCode.INVALID_INPUT,"请设置有效教学讲次");
        jdbc.update("UPDATE core_course SET teaching_published=1,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND version=?",current.id(),current.version());
        jdbc.update("INSERT INTO core_course_publication(course_id,course_version,curriculum_version,name,course_type,subject,grade,price,published_at,publisher_id) "
            +"SELECT id,version,curriculum_version,name,course_type,subject,grade,price,UTC_TIMESTAMP(3),? FROM core_course WHERE id=?",actor.id(),current.id());
        Long publication=jdbc.queryForObject("SELECT id FROM core_course_publication WHERE course_id=? AND course_version=?",Long.class,current.id(),current.version()+1);
        jdbc.update("INSERT INTO core_course_publication_lesson(publication_id,lesson_public_id,sequence_no,title) SELECT ?,public_id,sequence_no,title FROM core_lesson WHERE course_id=? AND deleted=0 ORDER BY sequence_no",publication,current.id());
        return new View(current.publicId(),current.version()+1,true,snapshot(publication));
    }
}
