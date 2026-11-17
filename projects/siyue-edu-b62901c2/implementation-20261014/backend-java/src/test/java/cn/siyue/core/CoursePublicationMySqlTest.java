package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import cn.siyue.core.CoursePublicationDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Dedicated real SQL proof for immutable teaching versions and authority changes. */
@EnabledIfEnvironmentVariable(named="SIYUE_TEST_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/siyue_[A-Za-z0-9_]+_test([?].*)?")
class CoursePublicationMySqlTest {
    JdbcTemplate jdbc;AcademicCatalogRepository catalog;CoursePublicationService service;Actor actor;
    AccessRepository access;TransactionTemplate tx;String course;long courseId;ObjectMapper mapper;
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource(System.getenv("SIYUE_TEST_MYSQL_URL"),System.getenv("SIYUE_TEST_MYSQL_USER"),System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(source).cleanDisabled(true).load().migrate();jdbc=new JdbcTemplate(source);
        String suffix=UUID.randomUUID().toString(),principal="CP_"+suffix;course="COURSE_"+suffix;
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES(?,'https://course.invalid',?,'all')",principal,principal);
        long id=jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id=?",Long.class,principal);
        for(String grant:List.of("course.publish","course.read","course.write"))jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,?)",id,grant);
        access=new AccessRepository(jdbc) {
            @Override public Actor lockActive(long id) {
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                    "Current authorization must run through the transactional service proxy");
                return super.lockActive(id);
            }
        };actor=access.resolve("https://course.invalid",principal);
        var cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));catalog=new AcademicCatalogRepository(jdbc,cipher);
        mapper=new ObjectMapper().findAndRegisterModules();var manager=new DataSourceTransactionManager(source);tx=new TransactionTemplate(manager);
        var plain=new CoursePublicationService(access,catalog,new CoursePublicationRepository(jdbc,new CoursePublicationCache(null,mapper,new PublicationCacheProperties(false,null,60))),new MutationRepository(jdbc,cipher),mapper);
        var proxy=new ProxyFactory(plain);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));service=(CoursePublicationService)proxy.getProxy();
        jdbc.update("INSERT INTO core_course(public_id,name,course_type,grade,subject,price) VALUES(?,'原教学课程','onsite','一年级','数学',100.00)",course);
        courseId=jdbc.queryForObject("SELECT id FROM core_course WHERE public_id=?",Long.class,course);
        jdbc.update("INSERT INTO core_lesson(public_id,course_id,sequence_no,title) VALUES(?,?,1,'原第一讲')","L_"+suffix,courseId);
        MDC.put("traceId",UUID.randomUUID().toString().replace("-",""));
    }
    @AfterEach void done(){MDC.remove("traceId");}
    String key(){return UUID.randomUUID().toString();}
    @Test void publishEditAndRepublishKeepEarlierSnapshotImmutable() throws Exception {
        String retry=key();View first=service.publish(actor,course,retry,new Input(1));assertEquals(2,first.version());assertTrue(first.published());
        assertEquals(first,service.publish(actor,course,retry,new Input(1)));
        assertEquals(ErrorCode.ALREADY_EXISTS,assertThrows(BusinessException.class,()->service.publish(actor,course,key(),new Input(2))).code());
        assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(BusinessException.class,()->service.publish(actor,course,key(),new Input(1))).code());
        assertEquals(new BigDecimal("100.00"),first.publishedSnapshot().price());
        tx.execute(status->catalog.updateCourse(catalog.lockCourseStatus(actor,course),new AcademicDtos.CourseUpdateInput("新草稿",null,2,List.of(new AcademicDtos.CourseLessonUpdate("新第一讲")),new BigDecimal("110.01"))));
        View draft=service.get(actor,course);assertFalse(draft.published());assertEquals(first.publishedSnapshot(),draft.publishedSnapshot());assertEquals(3,draft.version());
        View next=service.publish(actor,course,key(),new Input(3));assertEquals(4,next.version());assertEquals(new BigDecimal("110.01"),next.publishedSnapshot().price());assertEquals(2,next.publishedSnapshot().curriculumVersion());assertEquals("新第一讲",next.publishedSnapshot().lessons().get(0).title());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM core_course_publication WHERE course_id=?",Integer.class,courseId));
        long pub=jdbc.queryForObject("SELECT id FROM core_course_publication WHERE course_id=? AND course_version=2",Long.class,courseId);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE core_course_publication SET name='篡改' WHERE id=?",pub));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM core_course_publication_lesson WHERE publication_id=?",pub));
    }
    @Test void removingAndReaddingLessonPreservesDeletedRowAndPublishedHistory() throws Exception {
        String removed="REMOVED_"+UUID.randomUUID();
        jdbc.update("INSERT INTO core_lesson(public_id,course_id,sequence_no,title) VALUES(?,?,2,'历史第二讲')",removed,courseId);
        View original=service.publish(actor,course,key(),new Input(1));
        tx.execute(status->catalog.updateCourse(catalog.lockCourseStatus(actor,course),new AcademicDtos.CourseUpdateInput(
            "缩短草稿",null,2,List.of(new AcademicDtos.CourseLessonUpdate("原第一讲")))));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_lesson WHERE public_id=? AND deleted=1 AND title='历史第二讲'",Integer.class,removed));
        assertEquals(original.publishedSnapshot(),service.get(actor,course).publishedSnapshot());
        tx.execute(status->catalog.updateCourse(catalog.lockCourseStatus(actor,course),new AcademicDtos.CourseUpdateInput(
            "重加草稿",null,3,List.of(new AcademicDtos.CourseLessonUpdate("原第一讲"),new AcademicDtos.CourseLessonUpdate("新第二讲")))));
        String replacement=jdbc.queryForObject("SELECT public_id FROM core_lesson WHERE course_id=? AND sequence_no=2 AND deleted=0",String.class,courseId);
        assertNotEquals(removed,replacement);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(id) FROM core_lesson WHERE course_id=? AND sequence_no=2",Integer.class,courseId));
        View next=service.publish(actor,course,key(),new Input(4));
        assertEquals("新第二讲",next.publishedSnapshot().lessons().get(1).title());
        assertEquals("历史第二讲",original.publishedSnapshot().lessons().get(1).title());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
            "INSERT INTO core_lesson(public_id,course_id,sequence_no,title) VALUES(?,?,2,'重复当前讲次')","DUP_"+UUID.randomUUID(),courseId));
    }
    @Test void exactPermissionRevocationAndScopeApplyBeforeIdempotentReplay() throws Exception {
        String retry=key();service.publish(actor,course,retry,new Input(1));
        jdbc.update("UPDATE core_principal_permission SET deleted=1 WHERE principal_id=? AND permission_code='course.publish'",actor.id());
        BusinessException denied=assertThrows(BusinessException.class,()->service.publish(actor,course,retry,new Input(1)));assertEquals(ErrorCode.FORBIDDEN,denied.code());
        jdbc.update("UPDATE core_principal_permission SET deleted=0 WHERE principal_id=? AND permission_code='course.publish'",actor.id());
        jdbc.update("UPDATE core_principal SET scope_type='campus' WHERE id=?",actor.id());
        assertThrows(BusinessException.class,()->service.publish(actor,course,retry,new Input(1)));
        jdbc.update("UPDATE core_principal SET scope_type='all',active=0 WHERE id=?",actor.id());
        assertThrows(BusinessException.class,()->service.publish(actor,course,retry,new Input(1)));
    }
    @Test void concurrentReplayCreatesOneVersionAndAuditFailureRollsBack() throws Exception {
        jdbc.execute("CREATE TRIGGER course_publication_test_failure BEFORE INSERT ON core_audit FOR EACH ROW BEGIN IF NEW.action='academic.course.publish' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic'; END IF; END");
        try{assertThrows(RuntimeException.class,()->service.publish(actor,course,key(),new Input(1)));}finally{jdbc.execute("DROP TRIGGER course_publication_test_failure");}
        assertFalse(service.get(actor,course).published());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM core_course_publication WHERE course_id=?",Integer.class,courseId));
        String retry=key();var pool=Executors.newFixedThreadPool(4);var gate=new CountDownLatch(1);
        try{var calls=new java.util.ArrayList<Future<View>>();for(int i=0;i<4;i++)calls.add(pool.submit(()->{gate.await();MDC.put("traceId",UUID.randomUUID().toString().replace("-",""));try{return service.publish(actor,course,retry,new Input(1));}finally{MDC.remove("traceId");}}));gate.countDown();for(var call:calls)assertEquals(2,call.get(15,TimeUnit.SECONDS).version());}
        finally{pool.shutdownNow();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM core_course_publication WHERE course_id=?",Integer.class,courseId));
    }
    @Test void storefrontCannotPublishDraftTeachingAndOldListingBecomesUnavailable() throws Exception {
        var cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));String campus="CAMP_"+UUID.randomUUID(),klass="CLASS_"+UUID.randomUUID();
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES(?,?)",campus,cipher.encrypt("合成校区"));
        long cp=jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id=?",Long.class,campus);
        jdbc.update("INSERT INTO core_class(public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes) VALUES(?,?,?,?,?,'A',20,60)",klass,courseId,cp,actor.id(),"合成班级");
        var repo=new CommerceRepository(jdbc);var input=new CommerceDtos.ProductInput(course,"合成商品",List.of(klass),true,null);
        assertThrows(BusinessException.class,()->tx.execute(status->repo.saveProduct(actor,null,input)));
        service.publish(actor,course,key(),new Input(1));var product=tx.execute(status->repo.saveProduct(actor,null,input));
        jdbc.update("UPDATE core_commerce_product SET storefront_title='合成展示',storefront_grade='一年级',storefront_subject='数学',storefront_course_type='onsite',storefront_price=100,storefront_course_version=2 WHERE public_id=?",product.id());
        var publicCatalog=new PublicStoreCatalogRepository(jdbc,cipher,mapper);
        assertTrue(publicCatalog.catalog().products().stream().filter(row->row.id().equals(course)).findFirst().orElseThrow().availableForApplication());
        tx.execute(status->catalog.updateCourse(catalog.lockCourseStatus(actor,course),new AcademicDtos.CourseUpdateInput("新草稿",null,2,List.of(new AcademicDtos.CourseLessonUpdate("原第一讲")))));
        assertFalse(publicCatalog.catalog().products().stream().filter(row->row.id().equals(course)).findFirst().orElseThrow().availableForApplication());
    }
    @Test void cachedReadRunsOutsideTransactionAndRechecksAuthorityAfterLoading() throws Exception {
        service.publish(actor,course,key(),new Input(1));
        var cache=new CoursePublicationCache(null,mapper,new PublicationCacheProperties(false,null,60)) {
            @Override public Snapshot get(long id,java.util.function.Supplier<Snapshot> loader) {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                Snapshot result=loader.get();
                jdbc.update("UPDATE core_principal_permission SET deleted=1 WHERE principal_id=? AND permission_code IN ('course.read','course.publish')",actor.id());
                return result;
            }
        };
        var reader=CoursePublicationReaderTestSupport.create(access,catalog,new CoursePublicationRepository(jdbc,cache),tx.getTransactionManager());
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,()->reader.get(actor,course)).code());
    }
    @Test void currentReferenceChangingDuringCachedReadReturnsConflict() throws Exception {
        service.publish(actor,course,key(),new Input(1));
        var cache=new CoursePublicationCache(null,mapper,new PublicationCacheProperties(false,null,60)) {
            @Override public Snapshot get(long id,java.util.function.Supplier<Snapshot> loader) {
                Snapshot result=loader.get();jdbc.update("UPDATE core_course SET version=version+1,teaching_published=0 WHERE id=?",courseId);return result;
            }
        };
        var reader=CoursePublicationReaderTestSupport.create(access,catalog,new CoursePublicationRepository(jdbc,cache),tx.getTransactionManager());
        assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(BusinessException.class,()->reader.get(actor,course)).code());
    }
    @Test void historicalEditedDraftImportsItsPreviousPublicationWithoutRewritingIt() throws Exception {
        var row=mapper.readTree("{\"version\":4,\"curriculumVersion\":2,\"published\":false,\"publishedVersion\":2,\"publishedAt\":\"2026-10-01T00:00:00Z\",\"publishedSnapshot\":{\"name\":\"历史已发布名称\",\"courseType\":\"onsite\",\"subject\":\"数学\",\"grade\":\"一年级\",\"priceCents\":9900,\"curriculumVersion\":1,\"lessons\":[{\"id\":\"legacy-lesson\",\"sequence\":1,\"title\":\"历史讲次\",\"durationMinutes\":60}]}}");
        jdbc.update("UPDATE core_course SET version=4 WHERE id=?",courseId);tx.execute(status->{LegacyCoursePublicationImport.insert(jdbc,courseId,row);return null;});
        View view=service.get(actor,course);assertFalse(view.published());assertEquals(4,view.version());assertEquals("历史已发布名称",view.publishedSnapshot().name());assertEquals(60,view.publishedSnapshot().lessons().get(0).durationMinutes());assertEquals(2,LegacyCoursePublicationImport.rows(row));
    }
}
