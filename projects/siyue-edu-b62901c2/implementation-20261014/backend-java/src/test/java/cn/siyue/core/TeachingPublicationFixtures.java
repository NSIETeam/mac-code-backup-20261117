package cn.siyue.core;

import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit synthetic pre-existing publication used by commerce-only fixtures. */
final class TeachingPublicationFixtures {
    private TeachingPublicationFixtures() { }
    static void published(JdbcTemplate jdbc,long course) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM core_lesson WHERE course_id=?",Integer.class,course)<1)throw new IllegalStateException("Fixture needs teaching lessons");
        jdbc.update("INSERT INTO core_course_publication(course_id,course_version,curriculum_version,name,course_type,subject,grade,price,published_at) SELECT id,version,curriculum_version,name,course_type,subject,grade,price,UTC_TIMESTAMP(3) FROM core_course WHERE id=?",course);
        long pub=jdbc.queryForObject("SELECT id FROM core_course_publication WHERE course_id=? ORDER BY course_version DESC LIMIT 1",Long.class,course);
        jdbc.update("INSERT INTO core_course_publication_lesson(publication_id,lesson_public_id,sequence_no,title) SELECT ?,public_id,sequence_no,title FROM core_lesson WHERE course_id=? AND deleted=0",pub,course);
        jdbc.update("UPDATE core_course SET teaching_published=1 WHERE id=?",course);
    }
}
