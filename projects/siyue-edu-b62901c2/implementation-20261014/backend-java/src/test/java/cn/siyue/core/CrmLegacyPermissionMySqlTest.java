package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@EnabledIfEnvironmentVariable(named="SIYUE_TEST_MYSQL_URL",matches=".+")
class CrmLegacyPermissionMySqlTest {
    private JdbcTemplate jdbc;
    private PiiCipher cipher;
    private AccessRepository access;
    private MutationRepository mutations;
    private DataSourceTransactionManager manager;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();

    @BeforeEach void setUp() {
        String url=System.getenv("SIYUE_TEST_MYSQL_URL");URI uri=URI.create(url.substring(5));
        assertEquals("127.0.0.1",uri.getHost());assertTrue(uri.getPath().endsWith("_test"));
        var source=new DriverManagerDataSource(url,System.getenv("SIYUE_TEST_MYSQL_USER"),System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        var flyway=Flyway.configure().dataSource(source).cleanDisabled(false).load();flyway.clean();flyway.migrate();
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        access=new AccessRepository(jdbc);mutations=new MutationRepository(jdbc,cipher);
        for(int i=1;i<=2;i++) {
            jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES(?,?)","legacy-campus-"+i,cipher.encrypt("合成校区"+i));
            jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES(?,'https://legacy.test',?,'campus')","legacy-"+i,"subject-"+i);
            jdbc.update("INSERT INTO core_principal_campus(principal_id,campus_id) VALUES(?,?)",i,i);
            jdbc.update("INSERT INTO core_customer(public_id,campus_id,owner_id,display_name_cipher) VALUES(?,?,?,?)","customer-"+i,i,i,cipher.encrypt("合成客户"+i));
            jdbc.update("INSERT INTO core_activity(public_id,campus_id,owner_id,title,activity_type,status,starts_at,ends_at) VALUES(?,?,?,'合成活动','campaign','active',UTC_TIMESTAMP(3),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))","activity-"+i,i,i);
            jdbc.update("INSERT INTO core_opportunity(public_id,customer_id,campus_id,owner_id,title,subject,term,need_key,stage) VALUES(?,?,?,?,'合成商机','数学','合成学期',?,'qualified')","opportunity-"+i,i,i,i,new byte[32]);
            jdbc.update("INSERT INTO core_crm_task_batch(public_id,created_by,title,task_type,campus_id,due_at) VALUES(?,?,'合成批次','marketing',?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))","batch-"+i,i,i);
            jdbc.update("INSERT INTO core_crm_task(public_id,batch_id,customer_id,campus_id,owner_id,task_type,title,due_at) VALUES(?,?,?,?,?,'marketing','合成任务',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))","task-"+i,i,i,i,i);
        }
    }

    @Test void extractedActivityMembersPreserveCreationReplaySoftDeleteAndAtomicRollback() throws Exception {
        grant("crm.activity.write");grant("crm.activity.read");
        Actor caller=actor("crm.activity.write","crm.activity.read");
        var crm=proxy(new CrmService(access,new CrmRepository(jdbc,cipher),mutations,mapper));
        MDC.put("traceId","c72bef94-2928-4012-a8f7-a883962f2a36");
        try {
            var start=java.time.Instant.parse("2026-11-01T00:00:00Z");
            var input=new CrmDtos.ActivityInput("legacy-campus-1","合成成员活动","event",start,
                start.plusSeconds(3600),null,List.of("legacy-1"),List.of("legacy-1"));
            var created=crm.createActivity(caller,"membership-create-001",input);
            assertEquals(List.of("legacy-1"),created.participantIds());
            assertEquals(List.of("legacy-1"),created.promoterIds());
            assertEquals(created,crm.createActivity(caller,"membership-create-001",input));
            var removed=crm.updateActivityMembers(caller,created.id(),"membership-clear-001",
                new CrmDtos.ActivityMembersInput(List.of(),List.of("legacy-1"),1));
            assertTrue(removed.participantIds().isEmpty());assertEquals(2,removed.version());
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(id) FROM core_activity_member",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_activity_member WHERE deleted=1",Integer.class));
            assertThrows(BusinessException.class,()->crm.updateActivityMembers(caller,created.id(),"membership-cross-001",
                new CrmDtos.ActivityMembersInput(List.of("legacy-1"),List.of("legacy-2"),2)));
            var after=crm.activities(caller,new CrmActivityQuery(null,"scope","event",null,null,1,20)).items().get(0);
            assertEquals(removed,after);
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(id) FROM core_idempotency WHERE request_key='membership-cross-001'",Integer.class));
            var restored=crm.updateActivityMembers(caller,created.id(),"membership-restore-001",
                new CrmDtos.ActivityMembersInput(List.of("legacy-1"),List.of("legacy-1"),2));
            assertEquals(3,restored.version());assertEquals(List.of("legacy-1"),restored.participantIds());
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(id) FROM core_activity_member",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(id) FROM core_activity_member WHERE deleted=1",Integer.class));
            assertThrows(BusinessException.class,()->crm.updateActivityMembers(caller,created.id(),"membership-stale-001",
                new CrmDtos.ActivityMembersInput(List.of(),List.of(),2)));
        } finally {MDC.remove("traceId");}
    }

    @Test void receiverCampusExceptionsBelongOnlyToTheExactLegacyOperation() {
        jdbc.update("UPDATE core_principal SET scope_type='all' WHERE id=2");
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(2,'lead.follow')");
        for(String id:List.of("modern-owner","legacy-owner","legacy-modern-grant","wrong-operation","denied-owner"))
            jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,campus_id,owner_id,source,title,decision_status) "
                +"VALUES(?,1,1,1,'phone','合成接收边界','retained')",id);
        var repository=new LeadRepository(jdbc,cipher);
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        var input=new CrmDtos.LeadOwner("legacy-2","合成分配",1);
        Actor modern=actor("crm.lead.assign");
        assertThrows(BusinessException.class,()->tx.execute(status->repository.assign(modern,"modern-owner",input)));
        assertThrows(BusinessException.class,()->tx.execute(status->repository.assign(
            actor("crm.lead.assign","lead.transfer"),"wrong-operation",input)));
        assertEquals("legacy-2",tx.execute(status->repository.assign(actor("lead.assign"),"legacy-owner",input)).ownerId());
        jdbc.update("UPDATE core_principal_permission SET deleted=1 WHERE principal_id=2 AND permission_code='lead.follow'");
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(2,'crm.lead.follow')");
        assertThrows(BusinessException.class,()->tx.execute(status->repository.assign(actor("lead.assign"),"legacy-modern-grant",input)));
        assertThrows(BusinessException.class,()->tx.execute(status->repository.assign(modern,"modern-owner",input)));
        jdbc.update("INSERT INTO core_principal_campus(principal_id,campus_id) VALUES(2,1)");
        assertEquals("legacy-2",tx.execute(status->repository.assign(modern,"modern-owner",input)).ownerId());
        jdbc.update("INSERT INTO core_principal_denied_permission(principal_id,permission_code) VALUES(2,'crm.lead.follow')");
        assertThrows(BusinessException.class,()->tx.execute(status->repository.assign(modern,"denied-owner",input)));
        assertEquals(1,jdbc.queryForObject("SELECT version FROM core_crm_lead WHERE public_id='denied-owner'",Integer.class));
    }

    @Test void legacyReadGrantsReachRealQueriesWithoutCrossCampusDisclosure() {
        Actor reader=actor("lead.read");
        var crm=proxy(new CrmService(access,new CrmRepository(jdbc,cipher),mutations,mapper));
        assertEquals(List.of("customer-1"),crm.customers(reader,null,1,20).items().stream().map(row->row.id()).toList());
        assertEquals(1,crm.activities(reader,new CrmActivityQuery(null,null,null,null,null,1,20)).total());
        var opportunities=proxy(new OpportunityService(access,new OpportunityRepository(jdbc,cipher),mutations,mapper));
        assertEquals(List.of("opportunity-1"),opportunities.list(reader,null,null,1,20).items().stream().map(CrmDtos.Opportunity::id).toList());
        assertThrows(BusinessException.class,()->opportunities.events(reader,"opportunity-2"));
        var tasks=proxy(new CrmTaskService(access,new CrmTaskRepository(jdbc,cipher),mutations,mapper));
        assertEquals(List.of("task-1"),tasks.list(reader,null,null,1,20).items().stream().map(CrmDtos.CrmTask::id).toList());
        var tickets=proxy(new CrmServiceTicketService(access,new CrmServiceTicketRepository(jdbc,cipher),mutations,mapper));
        assertEquals(0,tickets.list(reader,null,1,20).total());
        assertThrows(BusinessException.class,()->crm.customers(actor("report.read"),null,1,20));
        assertThrows(BusinessException.class,()->tasks.list(actor("lead.create"),null,null,1,20));
        assertEquals(Set.of("lead.read"),reader.permissions());
    }

    @Test void legacyWritesPreserveCampusVersionAuditReplayAndRevocation() throws Exception {
        grant("lead.create");grant("lead.follow");Actor caller=actor("lead.create","lead.follow");
        var crm=proxy(new CrmService(access,new CrmRepository(jdbc,cipher),mutations,mapper));
        var opportunities=proxy(new OpportunityService(access,new OpportunityRepository(jdbc,cipher),mutations,mapper));
        MDC.put("traceId","c0f5e22c-1cfe-4d92-b0ce-0d96f4d32465");
        try {
            var input=new CrmDtos.CustomerInput("legacy-campus-1","合成新客户",List.of());
            var result=crm.createCustomer(caller,"legacy-real-create-001",input);
            assertEquals(result,crm.createCustomer(caller,"legacy-real-create-001",input));
            assertThrows(BusinessException.class,()->crm.createCustomer(caller,"legacy-real-cross-001",
                new CrmDtos.CustomerInput("legacy-campus-2","跨校区拒绝",List.of())));
            var follow=new CrmDtos.OpportunityFollowup("phone","合成跟进",null,1);
            var updated=opportunities.followup(caller,"opportunity-1","legacy-real-follow-001",follow);
            assertEquals(2,updated.version());
            assertEquals(updated,opportunities.followup(caller,"opportunity-1","legacy-real-follow-001",follow));
            assertThrows(BusinessException.class,()->opportunities.followup(caller,"opportunity-2","legacy-real-follow-002",follow));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_opportunity_event WHERE opportunity_id=1",Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(id) FROM core_audit WHERE actor_id=1",Integer.class));
            jdbc.update("INSERT INTO core_principal_denied_permission(principal_id,permission_code) VALUES(1,'lead.create')");
            assertThrows(BusinessException.class,()->crm.createCustomer(caller,"legacy-real-create-001",input));
            assertEquals(3,jdbc.queryForObject("SELECT COUNT(id) FROM core_customer",Integer.class));
        } finally { MDC.remove("traceId"); }
    }

    @Test void marketingExecutionRequiresBothExactLegacyGrants() throws Exception {
        grant("lead.create");var tasks=proxy(new CrmTaskService(access,new CrmTaskRepository(jdbc,cipher),mutations,mapper));
        var update=new CrmDtos.TaskUpdate(null,"in_progress",null,"合成启动",1);
        Actor creator=actor("lead.create");
        assertThrows(BusinessException.class,()->tasks.update(creator,"task-1","legacy-real-task-001",update));
        grant("lead.follow");Actor executor=actor("lead.create","lead.follow");
        MDC.put("traceId","d9dfaf8d-63d6-4c3b-9125-cb3d0161fc28");
        try {
            var result=tasks.update(executor,"task-1","legacy-real-task-001",update);
            assertEquals("in_progress",result.status());
            assertEquals(result,tasks.update(executor,"task-1","legacy-real-task-001",update));
            assertThrows(BusinessException.class,()->tasks.update(executor,"task-2","legacy-real-task-002",update));
            assertThrows(BusinessException.class,()->tasks.update(executor,"task-1","legacy-real-task-003",
                new CrmDtos.TaskUpdate(null,"cancelled",null,"合成取消",2)));
            jdbc.update("INSERT INTO core_principal_denied_permission(principal_id,permission_code) VALUES(1,'lead.follow')");
            assertThrows(BusinessException.class,()->tasks.update(executor,"task-1","legacy-real-task-001",update));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_task_event",Integer.class));
        } finally { MDC.remove("traceId"); }
    }

    @Test void receiverQualificationUsesCurrentPositionsDenialsAndPasswordReadiness() {
        jdbc.update("INSERT INTO core_access_position(public_id,name) VALUES('legacy-sales','合成销售岗位')");
        jdbc.update("INSERT INTO core_access_position_permission(position_id,permission_code) VALUES(1,'lead.follow')");
        jdbc.update("INSERT INTO core_principal_position(principal_id,position_id) VALUES(1,1)");
        var tasks=new CrmTaskAssignmentRepository(jdbc);Actor assigner=actor("lead.assign");
        assertEquals(1,tasks.requireEligible(assigner,"legacy-1",List.of(1L),"marketing",false).id());
        assertThrows(BusinessException.class,()->tasks.requireEligible(assigner,"legacy-1",List.of(1L),"service",false));
        assertThrows(BusinessException.class,()->tasks.requireEligible(assigner,"legacy-1",List.of(2L),"marketing",false));
        jdbc.update("UPDATE core_principal SET credential_change_required=1,credential_change_ticket_hash=REPEAT('a',64) WHERE id=1");
        assertThrows(BusinessException.class,()->tasks.requireEligible(assigner,"legacy-1",List.of(1L),"marketing",false));
        jdbc.update("UPDATE core_principal SET credential_change_required=0,credential_change_ticket_hash=NULL WHERE id=1");
        jdbc.update("INSERT INTO core_principal_denied_permission(principal_id,permission_code) VALUES(1,'lead.follow')");
        assertThrows(BusinessException.class,()->tasks.requireEligible(assigner,"legacy-1",List.of(1L),"marketing",false));
        jdbc.update("UPDATE core_principal_denied_permission SET deleted=1 WHERE principal_id=1");
        assertEquals(1,tasks.requireEligible(assigner,"legacy-1",List.of(1L),"marketing",false).id());
        jdbc.update("UPDATE core_access_position SET active=0 WHERE id=1");
        assertThrows(BusinessException.class,()->tasks.requireEligible(assigner,"legacy-1",List.of(1L),"marketing",false));
    }

    @Test void legacySubmissionReviewRequiresPublisherOrTrustedOriginalAdministrator() throws Exception {
        grant("lead.create");grant("system.access.manage");
        jdbc.update("UPDATE core_principal SET scope_type='all' WHERE id=2");
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(2,'lead.create')");
        var forms=new CrmFormRepository(jdbc,cipher,mapper);
        var formService=proxy(new CrmFormService(access,forms,mutations,mapper));
        var submissions=new CrmFormSubmissionRepository(jdbc,cipher,mapper,mutations);
        var review=proxy(new CrmFormSubmissionReviewService(forms,submissions,null,new CrmFormReviewCommands(access,mutations,mapper)));
        Actor publisher=access.resolve("https://legacy.test","subject-2");
        Actor admin=access.resolve("https://legacy.test","subject-1");
        MDC.put("traceId","8c48e6d7-8bd6-4a29-a2a2-8eca9d0d2455");
        try {
            var draft=formService.create(publisher,"legacy-form-create-001",new CrmDtos.MarketingFormInput(
                "legacy-campus-1","合成问卷",null,"survey",false,List.of(new CrmDtos.FormFieldInput(
                    "q","问题","text",null,false,List.of())),java.util.Map.of()));
            formService.publish(publisher,draft.id(),"legacy-form-publish-001",new CrmDtos.MarketingFormVersion(draft.version()));
            long formId=jdbc.queryForObject("SELECT id FROM core_crm_form WHERE public_id=?",Long.class,draft.id());
            long revision=jdbc.queryForObject("SELECT id FROM core_crm_form_revision WHERE form_id=?",Long.class,formId);
            for(int i=1;i<=2;i++) {
                byte[] key=new byte[32];key[0]=(byte)i;
                jdbc.update("INSERT INTO core_crm_form_submission(public_id,form_id,revision_id,request_key_hash,payload_hash,answers_cipher,submitted_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3))",
                    "submission-"+i,formId,revision,key,new byte[32],cipher.encrypt("{\"q\":\"合成回答\"}"));
            }
            assertEquals(0,review.submissions(admin,draft.id(),null,1,20).total());
            var decision=new CrmDtos.FormSubmissionReviewInput("reject","合成拒绝",null,false,1);
            assertThrows(BusinessException.class,()->review.review(admin,"submission-1","legacy-review-admin-001",decision));
            assertEquals(2,review.submissions(publisher,draft.id(),null,1,20).total());
            var first=review.review(publisher,"submission-1","legacy-review-publisher-001",decision);
            assertEquals("rejected",first.status());
            assertEquals(first,review.review(publisher,"submission-1","legacy-review-publisher-001",decision));
            jdbc.update("INSERT INTO core_legacy_identity_migration_checkpoint(legacy_user_id,principal_id,source_sha256,request_sha256,oidc_issuer,oidc_subject,actor_id,source_role_ids) VALUES('legacy-1',1,?,?,'https://legacy.test','subject-1',1,JSON_ARRAY('superadmin'))",
                "a".repeat(64),"b".repeat(64));
            assertEquals(2,review.submissions(admin,draft.id(),null,1,20).total());
            assertEquals("rejected",review.review(admin,"submission-2","legacy-review-admin-002",decision).status());
            jdbc.update("INSERT INTO core_principal_denied_permission(principal_id,permission_code) VALUES(1,'system.access.manage')");
            assertThrows(BusinessException.class,()->review.review(admin,"submission-2","legacy-review-admin-002",decision));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_form_submission_event WHERE event_type='rejected'",Integer.class));
        } finally { MDC.remove("traceId"); }
    }

    @Test void campaignLinkUsesLegacyVersionDraftFormAndWriteScope() throws Exception {
        grant("lead.create");Actor caller=actor("lead.create");
        var forms=new CrmFormRepository(jdbc,cipher,mapper);
        var service=proxy(new CrmFormService(access,forms,mutations,mapper));
        var crm=proxy(new CrmService(access,new CrmRepository(jdbc,cipher),mutations,mapper));
        jdbc.update("INSERT INTO core_activity(public_id,campus_id,owner_id,title,activity_type,status,starts_at,ends_at) "
            +"VALUES('other-campaign',1,2,'其他员工活动','campaign','active',UTC_TIMESTAMP(3),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))");
        jdbc.update("INSERT INTO core_activity(public_id,campus_id,owner_id,title,activity_type,status,starts_at,ends_at) "
            +"VALUES('non-campaign',1,1,'非营销活动','event','active',UTC_TIMESTAMP(3),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))");
        MDC.put("traceId","a289a619-5600-4468-a409-a91fc88f8dea");
        try {
            var draft=service.create(caller,"legacy-link-form-create",new CrmDtos.MarketingFormInput(
                "legacy-campus-1","草稿问卷",null,"survey",false,List.of(new CrmDtos.FormFieldInput(
                    "q","问题","text",null,false,List.of())),java.util.Map.of()));
            var input=new CrmDtos.ActivityFormLinkInput(draft.id(),1L);
            assertThrows(BusinessException.class,()->service.link(caller,"activity-1","legacy-link-no-version",new CrmDtos.ActivityFormLinkInput(draft.id())));
            assertThrows(BusinessException.class,()->service.link(caller,"other-campaign","legacy-link-other-owner",input));
            assertThrows(BusinessException.class,()->crm.closeActivity(caller,"other-campaign","legacy-close-other-owner",new CrmDtos.ActivityVersion(1)));
            assertThrows(BusinessException.class,()->crm.closeActivity(caller,"non-campaign","legacy-close-non-campaign",new CrmDtos.ActivityVersion(1)));
            var link=service.link(caller,"activity-1","legacy-link-campaign-001",input);
            assertEquals("draft",link.formStatus());
            assertEquals(link,service.link(caller,"activity-1","legacy-link-campaign-001",input));
            assertEquals(2,jdbc.queryForObject("SELECT version FROM core_activity WHERE public_id='activity-1'",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_activity_member WHERE activity_id=1 AND principal_id=1 AND member_role='participant'",Integer.class));
            assertThrows(BusinessException.class,()->service.link(caller,"activity-1","legacy-link-stale-001",input));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_activity_form",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_audit WHERE action='crm.activity.form.link'",Integer.class));
        } finally { MDC.remove("traceId"); }
    }

    @Test void campaignAndCollectionSelectionsRejectUnreadyPeopleButRetainHistoricalMembership() throws Exception {
        Actor caller=actor("lead.create","lead.assign","lead.follow");grant("lead.follow");
        var forms=new CrmFormRepository(jdbc,cipher,mapper);
        var form=forms.create(caller,new CrmDtos.MarketingFormInput("legacy-campus-1","资格问卷",null,"survey",false,
            List.of(new CrmDtos.FormFieldInput("q","问题","text",null,false,List.of())),java.util.Map.of()));
        long formId=jdbc.queryForObject("SELECT id FROM core_crm_form WHERE public_id=?",Long.class,form.id());
        var settings=new FormCollectionRepository.Settings(null,null,100,1,"合成来源","legacy-1");
        var collection=new FormCollectionRepository(jdbc);collection.save(caller,form,settings);
        var profiles=new CrmCampaignProfileRepository(jdbc,cipher);
        var campaign=profiles.create(caller,java.util.Map.of("name","合成草稿","campusId","legacy-campus-1","formIds",List.of(form.id())));
        assertEquals(1,profiles.options(caller,new CrmProfileDtos.OptionsQuery("employees","legacy-campus-1",1,20)).total());
        var published=forms.publish(caller,form.id(),form.version());
        jdbc.update("INSERT INTO core_activity_form(activity_id,form_id,linked_by) VALUES(1,?,1)",formId);
        var promotions=new CampaignPromotionRepository(jdbc,cipher);
        assertEquals(1,promotions.list(caller,"activity-1").promoters().size());
        jdbc.update("UPDATE core_principal SET credential_change_required=1,credential_change_ticket_hash=REPEAT('a',64) WHERE id=1");
        assertEquals(0,profiles.options(caller,new CrmProfileDtos.OptionsQuery("employees","legacy-campus-1",1,20)).total());
        assertEquals(0,promotions.list(caller,"activity-1").promoters().size());
        assertThrows(BusinessException.class,()->promotions.create(caller,"activity-1",new CampaignPromotionDtos.Create(form.id(),"legacy-1","合成推广",1)));
        assertThrows(BusinessException.class,()->profiles.update(caller,campaign.id(),new CrmProfileDtos.Patch(java.util.Map.of("participantIds",List.of("legacy-1")),1)));
        assertThrows(BusinessException.class,()->collection.save(caller,published,settings));
        assertThrows(BusinessException.class,()->collection.validatePublish(formId));
        // Unchanged historical/automatically linked members are not newly assigned work.
        assertEquals(List.of("legacy-1"),profiles.update(caller,campaign.id(),new CrmProfileDtos.Patch(java.util.Map.of("name","保留来源成员"),1)).participantIds());
        jdbc.update("UPDATE core_principal SET credential_change_required=0,credential_change_ticket_hash=NULL WHERE id=1");
        collection.validatePublish(formId);collection.save(caller,published,settings);
        jdbc.update("INSERT INTO core_principal_denied_permission(principal_id,permission_code) VALUES(1,'lead.follow')");
        assertThrows(BusinessException.class,()->collection.save(caller,published,settings));
        assertThrows(BusinessException.class,()->collection.validatePublish(formId));
    }

    @Test void salesStageIsSeparateVersionedOwnerControlledAndRejectsPaidConversions() throws Exception {
        grant("lead.follow");Actor caller=actor("lead.follow");
        jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,campus_id,owner_id,source,title,decision_status) "
            +"VALUES('sales-lead',1,1,1,'phone','合成销售线索','retained'),('cross-sales-lead',2,2,2,'phone','跨校区合成线索','retained')");
        var leads=proxy(new LeadService(access,new LeadRepository(jdbc,cipher),mutations,mapper));
        var next=new CrmDtos.LeadSalesStageInput("待报名","合成进展",1);
        assertThrows(BusinessException.class,()->leads.changeSalesStage(actor("crm.lead.write"),"sales-lead","legacy-sales-no-grant",next));
        assertThrows(BusinessException.class,()->leads.changeSalesStage(caller,"cross-sales-lead","legacy-sales-cross-01",next));
        jdbc.update("UPDATE core_crm_lead SET decision_status='pending' WHERE id=1");
        assertThrows(BusinessException.class,()->leads.changeSalesStage(caller,"sales-lead","legacy-sales-pending",next));
        jdbc.update("UPDATE core_crm_lead SET decision_status='retained',owner_id=2 WHERE id=1");
        assertThrows(BusinessException.class,()->leads.changeSalesStage(caller,"sales-lead","legacy-sales-not-owner",next));
        jdbc.update("UPDATE core_crm_lead SET owner_id=1 WHERE id=1");
        MDC.put("traceId","12f194ab-b08d-4898-97bc-6fe454653dc6");
        try {
            var result=leads.changeSalesStage(caller,"sales-lead","legacy-sales-stage-01",next);
            assertEquals(new CrmDtos.LeadSalesStage("sales-lead","待报名",2,null,"assigned"),result);
            assertEquals(result,leads.salesStage(caller,"sales-lead"));
            assertEquals(result,leads.changeSalesStage(caller,"sales-lead","legacy-sales-stage-01",next));
            assertEquals("new",jdbc.queryForObject("SELECT status FROM core_crm_lead WHERE id=1",String.class));
            assertThrows(BusinessException.class,()->leads.changeSalesStage(caller,"sales-lead","legacy-sales-stale-01",next));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_lead_event",Integer.class));
            var detail=mapper.readTree(cipher.decrypt(jdbc.queryForObject("SELECT detail_cipher FROM core_crm_lead_event",String.class)));
            assertEquals("销售状态更新",detail.path("kind").asText());assertEquals("待报名",detail.path("stage").asText());
            jdbc.update("UPDATE core_crm_lead SET legacy_stage='已报名' WHERE id=1");
            var follow=new CrmDtos.LeadSalesStageInput("跟进中",null,2);
            assertThrows(BusinessException.class,()->leads.changeSalesStage(caller,"sales-lead","legacy-sales-enrolled",follow));
            jdbc.update("UPDATE core_crm_lead SET legacy_stage='待报名' WHERE id=1");
            jdbc.update("UPDATE core_opportunity SET source_lead_id=1 WHERE id=1");
            jdbc.update("INSERT INTO core_crm_order(public_id,opportunity_id,customer_id,campus_id,attributed_owner_id,product_snapshot,total_amount,received_amount,status) "
                +"VALUES('sales-paid',1,1,1,1,'合成课程',100,0,'pending')");
            assertEquals(3,leads.changeSalesStage(caller,"sales-lead","legacy-sales-unpaid-01",follow).version());
            jdbc.update("UPDATE core_crm_order SET received_amount=1,status='partially_received' WHERE public_id='sales-paid'");
            assertThrows(BusinessException.class,()->leads.changeSalesStage(caller,"sales-lead","legacy-sales-paid-01",new CrmDtos.LeadSalesStageInput("已流失",null,3)));
            jdbc.update("INSERT INTO core_principal_denied_permission(principal_id,permission_code) VALUES(1,'lead.follow')");
            assertThrows(BusinessException.class,()->leads.changeSalesStage(caller,"sales-lead","legacy-sales-stage-01",next));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(id) FROM core_audit WHERE action='lead.status.update'",Integer.class));
        } finally { MDC.remove("traceId"); }
    }

    @Test void concurrentIdenticalSalesStageRequestsProduceOneEventAndReceipt() throws Exception {
        grant("lead.follow");Actor caller=actor("lead.follow");
        jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,campus_id,owner_id,source,title,decision_status) "
            +"VALUES('sales-concurrent',1,1,1,'phone','并发合成销售线索','retained')");
        var leads=proxy(new LeadService(access,new LeadRepository(jdbc,cipher),mutations,mapper));
        var executor=java.util.concurrent.Executors.newFixedThreadPool(4);
        var barrier=new java.util.concurrent.CyclicBarrier(4);
        try {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<CrmDtos.LeadSalesStage>>();
            for(int i=0;i<4;i++) futures.add(executor.submit(()->{
                MDC.put("traceId",java.util.UUID.randomUUID().toString());
                try {barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);
                    return leads.changeSalesStage(caller,"sales-concurrent","legacy-sales-concurrent",new CrmDtos.LeadSalesStageInput("已流失",null,1));
                } finally {MDC.remove("traceId");}
            }));
            for(var future:futures) assertEquals(new CrmDtos.LeadSalesStage("sales-concurrent","已流失",2,null,"cold"),future.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_lead_event",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_audit WHERE action='lead.status.update'",Integer.class));
        } finally {executor.shutdownNow();}
    }

    @Test void originalLeadReadRelationsNeverGrantOwnershipWrites() {
        jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,campus_id,owner_id,registrar_id,source,title,decision_status) VALUES "
            +"('registered',1,1,2,1,'phone','登记关系','retained'),('collected',1,1,2,NULL,'phone','采集关系','retained'),"
            +"('unrelated',2,2,2,NULL,'phone','无关系','retained'),('past-decision',1,1,2,NULL,'phone','历史决策','retained'),"
            +"('current-decision',1,1,NULL,NULL,'phone','当前决策','pending'),('timed-out',1,1,NULL,NULL,'phone','超时公海','timed_out')");
        jdbc.update("INSERT INTO core_crm_lead_collector(lead_id,principal_id) VALUES(2,1)");
        jdbc.update("UPDATE core_crm_lead SET decision_by_id=1 WHERE public_id IN ('past-decision','current-decision')");
        var leads=new LeadRepository(jdbc,cipher);
        for(String permission:List.of("lead.read","lead.create","lead.follow")) {
            Actor campus=actor(permission);
            assertEquals(5,leads.list(campus,new CrmDtos.LeadQuery(null,null,null,1,20)).total());
            assertThrows(BusinessException.class,()->leads.salesStage(campus,"unrelated"));
            Actor own=new Actor(1,"legacy-1","own",Set.of(),Set.of(permission));
            assertEquals(permission.equals("lead.follow")?5:4,leads.list(own,new CrmDtos.LeadQuery(null,null,null,1,20)).total());
            for(String readOnly:List.of("registered","collected","past-decision","timed-out"))
                assertThrows(BusinessException.class,()->leads.authorizeLegacyLead(own,readOnly));
            assertDoesNotThrow(()->leads.authorizeLegacyLead(own,"current-decision"));
            Actor assigned=new Actor(1,"legacy-1","assigned",Set.of(),Set.of(permission));
            assertEquals(4,leads.list(assigned,new CrmDtos.LeadQuery(null,null,null,1,20)).total());
        }
        Actor modernWriterWithLegacyRead=new Actor(1,"legacy-1","own",Set.of(),Set.of("crm.lead.write","lead.read"));
        assertThrows(BusinessException.class,()->leads.changeStatus(modernWriterWithLegacyRead,"registered",new CrmDtos.LeadStatus("archived","不应归档",null,1)));
        jdbc.update("UPDATE core_crm_lead_collector SET deleted=1 WHERE lead_id=2");
        Actor own=new Actor(1,"legacy-1","own",Set.of(),Set.of("lead.create"));
        assertThrows(BusinessException.class,()->leads.salesStage(own,"collected"));
    }

    @Test void customerTaskAndRegistrationReadsDoNotPermitProfileWrites() {
        jdbc.update("UPDATE core_customer SET owner_id=2 WHERE id=1");
        var crm=new CrmRepository(jdbc,cipher);var profiles=new CrmCustomerProfileRepository(jdbc,cipher);
        Actor reader=new Actor(1,"legacy-1","own",Set.of(),Set.of("lead.create"));
        assertEquals("customer-1",crm.customer(reader,"customer-1",false).id());
        assertEquals("customer-1",profiles.profile(reader,"customer-1").id());
        // The active assigned task gives visibility; it gives no customer editing authority.
        Actor follower=new Actor(1,"legacy-1","own",Set.of(),Set.of("lead.follow"));
        jdbc.update("UPDATE core_opportunity SET owner_id=2 WHERE id=1");
        var opportunities=new OpportunityRepository(jdbc,cipher);
        assertEquals(1,opportunities.list(follower,null,null,1,20).total());
        assertThrows(BusinessException.class,()->opportunities.authorize(follower,"opportunity-1"));
        assertThrows(BusinessException.class,()->profiles.update(follower,"customer-1",new CrmProfileDtos.Patch(java.util.Map.of("note","不应写入"),1)));
        jdbc.update("UPDATE core_crm_task SET status='cancelled' WHERE id=1");
        assertThrows(BusinessException.class,()->crm.customer(reader,"customer-1",false));
        jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,campus_id,owner_id,registrar_id,source,title,decision_status) "
            +"VALUES('registered-customer',1,1,2,1,'phone','登记客户关系','retained')");
        assertEquals("customer-1",profiles.profile(reader,"customer-1").id());
        assertThrows(BusinessException.class,()->profiles.update(follower,"customer-1",new CrmProfileDtos.Patch(java.util.Map.of("note","仍不可写"),1)));
        jdbc.update("UPDATE core_crm_lead SET owner_id=1 WHERE public_id='registered-customer'");
        assertEquals("合法跟进更新",profiles.update(follower,"customer-1",new CrmProfileDtos.Patch(java.util.Map.of("note","合法跟进更新"),1)).note());
        assertThrows(BusinessException.class,()->crm.customer(actor("lead.read"),"customer-2",false));
    }

    @Test void ticketCustomerReadsAndLegacyTaskCreatorDoNotBroadenTreatment() {
        for(int i=1;i<=2;i++)jdbc.update("INSERT INTO core_crm_service_ticket(public_id,customer_id,campus_id,ticket_type_id,owner_id,created_by,title_cipher,description_cipher) "
            +"VALUES(?,?,?,1,2,?,?,?)","ticket-"+i,i,i,i==1?2:1,cipher.encrypt("合成工单"),cipher.encrypt("合成描述"));
        var tickets=new CrmServiceTicketRepository(jdbc,cipher);
        Actor follower=new Actor(1,"legacy-1","own",Set.of(),Set.of("lead.follow"));
        assertEquals(2,tickets.list(follower,null,1,20).total());
        var input=new CrmDtos.ServiceTicketFollowup("合成处理",null,1);
        assertThrows(BusinessException.class,()->tickets.followup(follower,"ticket-1",input));
        assertThrows(BusinessException.class,()->tickets.followup(follower,"ticket-2",input));
        Actor modernWriter=new Actor(1,"legacy-1","own",Set.of(),Set.of("crm.ticket.write","lead.read"));
        assertThrows(BusinessException.class,()->tickets.followup(modernWriter,"ticket-1",input));
        Actor manager=new Actor(1,"legacy-1","own",Set.of(),Set.of("lead.follow","lead.assign"));
        assertEquals(2,tickets.followup(manager,"ticket-2",input).version());
        assertThrows(BusinessException.class,()->tickets.followup(manager,"ticket-1",input));
        Actor campus=actor("lead.follow","lead.assign");
        assertEquals(2,tickets.followup(campus,"ticket-1",input).version());
        assertThrows(BusinessException.class,()->tickets.authorize(campus,"ticket-2"));
        jdbc.update("UPDATE core_crm_task SET owner_id=2 WHERE id=1");
        var tasks=new CrmTaskRepository(jdbc,cipher);
        assertEquals(1,tasks.list(manager,null,null,1,20).total());
        Actor taskReader=new Actor(1,"legacy-1","own",Set.of(),Set.of("crm.task.read"));
        assertEquals(0,tasks.list(taskReader,null,null,1,20).total());
    }

    @Test void ownLeadHandoffAndReleaseReturnOnlyTheirLockedReceiptThenDenyReplayOutsideScope() throws Exception {
        jdbc.update("UPDATE core_principal SET scope_type='own' WHERE id=1");
        jdbc.update("UPDATE core_principal SET scope_type='all' WHERE id=2");
        grant("lead.assign");grant("lead.transfer");grant("lead.release");
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(2,'lead.follow')");
        jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,campus_id,owner_id,source,title,decision_status) VALUES "
            +"('own-assign',1,1,1,'phone','合成分配','retained'),('own-transfer',1,1,1,'phone','合成转交','retained'),"
            +"('own-release',1,1,1,'phone','合成释放','retained')");
        Actor own=new Actor(1,"legacy-1","own",Set.of(),Set.of("lead.assign","lead.transfer","lead.release"));
        var leads=proxy(new LeadService(access,new LeadRepository(jdbc,cipher),mutations,mapper));
        var input=new CrmDtos.LeadOwner("legacy-2","合成转交原因",1);
        MDC.put("traceId","c947ab3b-a884-497a-a7e8-1b74d053a936");
        try {
            assertEquals("legacy-2",leads.assign(own,"own-assign","own-assign-receipt-01",input).ownerId());
            assertEquals("legacy-2",leads.transfer(own,"own-transfer","own-transfer-receipt-01",input).ownerId());
            var release=new CrmDtos.LeadRelease("合成释放原因",1);
            assertNull(leads.release(own,"own-release","own-release-receipt-01",release).ownerId());
            assertThrows(BusinessException.class,()->leads.assign(own,"own-assign","own-assign-receipt-01",input));
            assertThrows(BusinessException.class,()->leads.transfer(own,"own-transfer","own-transfer-receipt-01",input));
            assertThrows(BusinessException.class,()->leads.release(own,"own-release","own-release-receipt-01",release));
            assertThrows(BusinessException.class,()->new LeadRepository(jdbc,cipher).events(own,"own-assign"));
            jdbc.update("UPDATE core_crm_lead SET owner_id=1 WHERE public_id='own-assign'");
            jdbc.update("INSERT INTO core_principal_denied_permission(principal_id,permission_code) VALUES(1,'lead.assign')");
            assertThrows(BusinessException.class,()->leads.assign(own,"own-assign","own-assign-receipt-01",input));
            assertEquals(3,jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_lead_event",Integer.class));
            assertEquals(3,jdbc.queryForObject("SELECT COUNT(id) FROM core_audit",Integer.class));
        } finally {MDC.remove("traceId");}
    }

    @Test void leadReceiversRequireEffectiveCurrentGrantReadinessAndOriginalScope() {
        jdbc.update("INSERT INTO core_access_position(public_id,name) VALUES('receiver-role','合成跟进岗位')");
        jdbc.update("INSERT INTO core_access_position_permission(position_id,permission_code) VALUES(1,'lead.follow')");
        jdbc.update("INSERT INTO core_principal_position(principal_id,position_id) VALUES(2,1)");
        jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,campus_id,owner_id,source,title,decision_status) "
            +"VALUES('receiver-lead',1,1,1,'phone','合成接收资格','retained')");
        var leads=new LeadRepository(jdbc,cipher);Actor assigner=actor("lead.assign");
        var input=new CrmDtos.LeadOwner("legacy-2","资格验证",1);
        assertThrows(BusinessException.class,()->leads.assign(assigner,"receiver-lead",input));
        jdbc.update("UPDATE core_principal SET scope_type='own' WHERE id=2");
        for(String reject:List.of("active=0","archived_at=UTC_TIMESTAMP(3),archived_by_id=1",
                "credential_change_required=1,credential_change_ticket_hash=REPEAT('a',64)","scope_type='assigned'")) {
            jdbc.update("UPDATE core_principal SET "+reject+" WHERE id=2");
            assertThrows(BusinessException.class,()->leads.assign(assigner,"receiver-lead",input));
            jdbc.update("UPDATE core_principal SET active=1,archived_at=NULL,archived_by_id=NULL,scope_type='own',credential_change_required=0,credential_change_ticket_hash=NULL WHERE id=2");
        }
        jdbc.update("UPDATE core_access_position SET active=0 WHERE id=1");
        assertThrows(BusinessException.class,()->leads.assign(assigner,"receiver-lead",input));
        jdbc.update("UPDATE core_access_position SET active=1 WHERE id=1");
        jdbc.update("INSERT INTO core_principal_denied_permission(principal_id,permission_code) VALUES(2,'lead.follow')");
        assertThrows(BusinessException.class,()->leads.assign(assigner,"receiver-lead",input));
        jdbc.update("UPDATE core_principal_denied_permission SET deleted=1 WHERE principal_id=2");
        assertEquals("legacy-2",leads.assign(assigner,"receiver-lead",input).ownerId());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_lead_event",Integer.class));
    }

    private Actor actor(String... grants) { return new Actor(1,"legacy-1","campus",Set.of(1L),Set.of(grants)); }
    private void grant(String permission) { jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(1,?)",permission); }
    @SuppressWarnings("unchecked") private <T> T proxy(T service) {
        var factory=new ProxyFactory(service);factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }
}
