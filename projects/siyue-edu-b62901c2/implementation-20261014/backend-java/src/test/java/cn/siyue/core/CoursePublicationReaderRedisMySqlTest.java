package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import cn.siyue.core.CoursePublicationDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real authenticated Redis + dedicated MySQL: cache hits never replace current authorization. */
@EnabledIfEnvironmentVariables({
    @EnabledIfEnvironmentVariable(named="SIYUE_TEST_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/siyue_[A-Za-z0-9_]+_test([?].*)?"),
    @EnabledIfEnvironmentVariable(named="SIYUE_TEST_REDIS_PORT",matches="56379")})
class CoursePublicationReaderRedisMySqlTest {
    private JdbcTemplate jdbc;
    private CoursePublicationReader reader;
    private Actor actor;
    private String course;
    private long courseId;
    private long publicationId;
    private String namespace;
    private LettuceConnectionFactory redisFactory;
    private StringRedisTemplate redis;
    private final AtomicInteger loads=new AtomicInteger();
    private final AtomicInteger remoteCalls=new AtomicInteger();
    private final AtomicReference<Runnable> afterRead=new AtomicReference<>();

    @BeforeEach void prepare() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("SIYUE_TEST_MYSQL_URL"),System.getenv("SIYUE_TEST_MYSQL_USER"),System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(source).cleanDisabled(true).load().migrate();jdbc=new JdbcTemplate(source);
        String tag=UUID.randomUUID().toString(),principal="READER_"+tag;course="COURSE_"+tag;namespace="reader_"+tag.replace("-","");
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES(?,'https://reader.invalid',?,'all')",principal,principal);
        long principalId=jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id=?",Long.class,principal);
        for(String grant:List.of("course.read","course.publish"))jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,?)",principalId,grant);
        var access=new AccessRepository(jdbc) {
            @Override public Actor lockActive(long id) {
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                    "Current authorization must run through the transactional service proxy");
                return super.lockActive(id);
            }
        };actor=access.resolve("https://reader.invalid",principal);
        jdbc.update("INSERT INTO core_course(public_id,name,course_type,grade,subject,price) VALUES(?,'合成教学版本','onsite','一年级','数学',100)",course);
        courseId=jdbc.queryForObject("SELECT id FROM core_course WHERE public_id=?",Long.class,course);
        jdbc.update("INSERT INTO core_lesson(public_id,course_id,sequence_no,title) VALUES(?,?,1,'合成第一讲')","LESSON_"+tag,courseId);
        var mapper=new ObjectMapper().findAndRegisterModules();var cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var catalog=new AcademicCatalogRepository(jdbc,cipher);var manager=new DataSourceTransactionManager(source);
        var disabled=new CoursePublicationCache(null,mapper,new PublicationCacheProperties(false,null,30));
        var service=new CoursePublicationService(access,catalog,new CoursePublicationRepository(jdbc,disabled),new MutationRepository(jdbc,cipher),mapper);
        var proxy=new ProxyFactory(service);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        MDC.put("traceId",tag.replace("-",""));((CoursePublicationService)proxy.getProxy()).publish(actor,course,tag,new Input(1));
        publicationId=jdbc.queryForObject("SELECT id FROM core_course_publication WHERE course_id=?",Long.class,courseId);
        connectRedis();
        var observedCache=new CoursePublicationCache(redis,mapper,new PublicationCacheProperties(true,namespace,30)) {
            @Override public Snapshot get(long id,java.util.function.Supplier<Snapshot> loader) {
                Snapshot value=super.get(id,()->{loads.incrementAndGet();return loader.get();});
                Runnable hook=afterRead.getAndSet(null);if(hook!=null)hook.run();return value;
            }
        };
        reader=CoursePublicationReaderTestSupport.create(access,catalog,new CoursePublicationRepository(jdbc,observedCache),manager);
    }
    private void connectRedis() {
        String password=System.getenv("SIYUE_TEST_REDIS_PASSWORD");assertNotNull(password);assertTrue(password.length()>=32);
        var configuration=new RedisStandaloneConfiguration("127.0.0.1",56379);configuration.setPassword(RedisPassword.of(password));
        redisFactory=new LettuceConnectionFactory(configuration,LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        redisFactory.afterPropertiesSet();redisFactory.start();
        redis=new StringRedisTemplate(redisFactory) {
            @Override public <T> T execute(RedisCallback<T> action,boolean exposeConnection,boolean pipeline) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Redis must not be accessed inside the authorization transaction");
                remoteCalls.incrementAndGet();return super.execute(action,exposeConnection,pipeline);
            }
        };redis.afterPropertiesSet();
        try(var connection=redisFactory.getConnection()){assertTrue(connection.serverCommands().info("server").getProperty("redis_version").startsWith("7."));}
    }
    @AfterEach void cleanup() {
        try{if(redis!=null)redis.delete("siyue:publication:v1:"+namespace+":"+publicationId);}
        finally{if(redisFactory!=null)redisFactory.destroy();MDC.remove("traceId");}
    }
    @Test void realCacheHitSkipsSnapshotLoadButFreshRevocationBlocksBeforeRedis() {
        View first=reader.get(actor,course);assertEquals(1,loads.get());
        int previous=remoteCalls.get();assertEquals(first,reader.get(actor,course));assertTrue(remoteCalls.get()>previous);assertEquals(1,loads.get());
        assertTrue(redis.getExpire("siyue:publication:v1:"+namespace+":"+publicationId)>0);
        jdbc.update("UPDATE core_principal_permission SET deleted=1 WHERE principal_id=?",actor.id());previous=remoteCalls.get();
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,()->reader.get(actor,course)).code());assertEquals(previous,remoteCalls.get());
    }
    @Test void revocationAfterRealCacheHitIsRecheckedBeforeReturningSnapshot() {
        reader.get(actor,course);int previous=remoteCalls.get();
        afterRead.set(()->jdbc.update("UPDATE core_principal_permission SET deleted=1 WHERE principal_id=?",actor.id()));
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,()->reader.get(actor,course)).code());
        assertTrue(remoteCalls.get()>previous);assertEquals(1,loads.get());
    }
    @Test void mutableVersionChangingAfterRealCacheHitReturnsConflict() {
        reader.get(actor,course);int previous=remoteCalls.get();
        afterRead.set(()->jdbc.update("UPDATE core_course SET teaching_published=0,version=version+1 WHERE id=?",courseId));
        assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(BusinessException.class,()->reader.get(actor,course)).code());
        assertTrue(remoteCalls.get()>previous);assertEquals(1,loads.get());
        View draft=reader.get(actor,course);assertEquals(3,draft.version());assertFalse(draft.published());assertEquals(2,draft.publishedSnapshot().courseVersion());
    }
}
