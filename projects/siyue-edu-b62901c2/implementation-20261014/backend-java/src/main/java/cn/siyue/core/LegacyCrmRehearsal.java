package cn.siyue.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Offline, bounded import of legacy household and CRM facts. */
public final class LegacyCrmRehearsal {
    private static String leadIntent(JsonNode row) {
        return switch (row.path("intent").asText("")) {
            case "高意向", "high" -> "high";
            case "中意向", "medium" -> "medium";
            case "低意向", "low" -> "low";
            default -> null;
        };
    }
    private static final Set<String> LOOKUP_TABLES=Set.of("core_principal","core_campus","core_student",
        "core_customer","core_activity","core_crm_order","core_crm_lead");
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final StudentIdentityMutationLock identityLocks;
    private final LegacyMigrationPhaseService transaction;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Uses the application cipher and transaction manager; this class is not a web bean. */
    public LegacyCrmRehearsal(JdbcTemplate jdbc,PiiCipher cipher,PlatformTransactionManager manager) {
        this(jdbc,cipher,LegacyMigrationPhaseTransactions.create(manager));
    }

    /** Shares the CLI annotation proxy; SQL, source validation and phase row limits remain local. */
    LegacyCrmRehearsal(JdbcTemplate jdbc,PiiCipher cipher,LegacyMigrationPhaseService phases) {
        this.jdbc=jdbc;this.cipher=cipher;this.identityLocks=new StudentIdentityMutationLock(jdbc,cipher);
        this.transaction=phases;
    }

    /** Imports customers, campaign activities, touchpoints, leads and opportunities once. */
    public int importCrm(JsonNode envelope) {
        return importCrm(envelope, null);
    }

    /** Candidate-only entry point; existing CRM rehearsal behavior is unchanged. */
    int importCrm(JsonNode envelope, LegacyMigrationTargetAuthorization authorization) {
        String database=jdbc.queryForObject("SELECT DATABASE()",String.class);
        LegacyMigrationTargetAuthorization.requireRehearsalOrCandidate(database,jdbc,envelope,authorization);
        if(!"siyue.legacy-business/v1".equals(envelope.path("schema").asText())) throw invalid("快照格式不支持");
        if(!envelope.path("revision").isIntegralNumber()) throw invalid("快照版本无效");
        JsonNode state=envelope.path("state");
        ObjectNode payload=JsonNodeFactory.instance.objectNode();
        for(String key:List.of("customers","campaigns","touchpoints","leads","opportunities")) {
            payload.set(key,state.path(key).deepCopy());
        }
        String source=payload.toString(),hash=LegacyCatalogRehearsal.hash(source);
        int rows=count(payload);
        return transaction.execute(() ->{
            requireCatalog(envelope.path("revision").longValue(),LegacyCatalogRehearsal.hash(state.toString()));
            requireWriteBudget(payload);
            List<Checkpoint> previous=jdbc.query("SELECT source_revision,source_hash,imported_rows FROM core_migration_checkpoint WHERE phase='crm_households_v1' FOR UPDATE",
                (rs,n)->new Checkpoint(rs.getLong(1),rs.getString(2),rs.getInt(3)));
            if(!previous.isEmpty()) {
                Checkpoint old=previous.get(0);
                if(old.revision()!=envelope.path("revision").longValue()||!old.hash().equals(hash)) throw invalid("CRM来源变化，禁止覆盖已导入历史");
                return old.rows();
            }
            jdbc.update("INSERT INTO core_migration_checkpoint(phase,source_revision,source_hash,source_cipher,imported_rows) VALUES('crm_households_v1',?,?,?,?)",
                envelope.path("revision").longValue(),hash,cipher.encrypt(source),rows);
            for(JsonNode customer:array(payload,"customers")) customer(customer);
            for(JsonNode campaign:array(payload,"campaigns")) campaign(campaign);
            for(JsonNode lead:array(payload,"leads")) lead(lead);
            for(JsonNode touchpoint:array(payload,"touchpoints")) touchpoint(touchpoint);
            for(JsonNode opportunity:array(payload,"opportunities")) opportunity(opportunity);
            reconcile(payload);
            return rows;
        });
    }

    private record Checkpoint(long revision,String hash,int rows) { }
    private void requireCatalog(long revision,String hash) {
        Integer count=jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='academic_catalog_v1' AND source_revision=? AND source_hash=? AND deleted=0",Integer.class,revision,hash);
        if(count==null||count!=1) throw invalid("必须先导入同revision目录快照");
    }
    private int count(JsonNode source) {
        int count=0;
        for(String key:List.of("customers","campaigns","touchpoints","leads","opportunities")) count=Math.addExact(count,array(source,key).size());
        for(JsonNode customer:array(source,"customers")) {
            ArrayNode contacts=array(customer,"contacts");count=Math.addExact(count,contacts.size());
            if(customer.hasNonNull("studentId")) count=Math.addExact(count,1);
            if(customer.hasNonNull("phone")&&!containsPhone(contacts,customer.path("phone").asText())) count=Math.addExact(count,1);
        }
        for(JsonNode campaign:array(source,"campaigns")) count=Math.addExact(count,array(campaign,"participantIds").size()+array(campaign,"promoterIds").size());
        for(JsonNode lead:array(source,"leads")) count=Math.addExact(count,collectors(lead).size());
        if(count>=1000) throw invalid("CRM迁移阶段超过单事务行数限制");return count;
    }
    private void requireWriteBudget(JsonNode source) {
        // Counts checkpoint, every CRM DML row and keyed search-index row before the first write.
        int writes = 1;
        int keys = cipher.blindIndexes("saved_search", "").size();
        writes = Math.addExact(writes, customerWriteBound(source, keys));
        writes = Math.addExact(writes, campaignWriteBound(source));
        writes = Math.addExact(writes, leadWriteBound(source, keys));
        writes = Math.addExact(writes, array(source, "touchpoints").size());
        writes = Math.addExact(writes, array(source, "opportunities").size());
        writes = Math.addExact(writes, studentOriginWriteBound());
        if (writes >= 1000) { throw invalid("CRM迁移阶段超过单事务行数限制"); }
    }
    private int customerWriteBound(JsonNode source, int keys) {
        int writes = 0;
        for (JsonNode customer : array(source, "customers")) {
            ArrayNode contacts = array(customer, "contacts");
            writes = Math.addExact(writes, 2 + keys);
            writes = Math.addExact(writes, Math.multiplyExact(contacts.size(), 2 + keys));
            if (customer.hasNonNull("phone") && !containsPhone(contacts, customer.path("phone").asText())) {
                writes = Math.addExact(writes, 2 + keys);
            }
            if (customer.hasNonNull("studentId")) { writes = Math.addExact(writes, 2); }
        }
        return writes;
    }
    private int campaignWriteBound(JsonNode source) {
        int writes = 0;
        for (JsonNode campaign : array(source, "campaigns")) {
            writes = Math.addExact(writes, 2);
            writes = Math.addExact(writes, array(campaign, "participantIds").size());
            writes = Math.addExact(writes, array(campaign, "promoterIds").size());
        }
        return writes;
    }
    private int leadWriteBound(JsonNode source, int keys) {
        int writes = 0;
        for (JsonNode lead : array(source, "leads")) {
            writes = Math.addExact(writes, 3 + collectors(lead).size());
            for (String field : List.of("name", "phone", "contactPhone", "guardian")) {
                if (optionalText(lead, field) != null) { writes = Math.addExact(writes, keys); }
            }
            JsonNode history = lead.path("decisionHistory");
            if (history.isArray()) { writes = Math.addExact(writes, history.size()); }
        }
        return writes;
    }
    private int studentOriginWriteBound() {
        int writes = 0;
        for (JsonNode student : array(academicCatalogSnapshot(), "students")) {
            if (optionalText(student, "originLeadId") != null) { writes = Math.addExact(writes, 3); }
        }
        return writes;
    }
    private void customer(JsonNode row) {
        Long owner=optionalId("core_principal",row,"ownerId");Long campus=optionalId("core_campus",row,"campusId");
        Long creator=optionalId("core_principal",row,"createdBy");
        long customerId;
        try {
            jdbc.update("INSERT INTO core_customer(public_id,campus_id,owner_id,source_created_by_id,display_name_cipher,source_profile_cipher,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                text(row,"id"),campus,owner,creator,cipher.encrypt(text(row,"name")),cipher.encrypt(row.toString()),positive(row,"version"),date(row,"createdAt"),date(row,"updatedAt"));
            customerId=id("core_customer",text(row,"id"));
            jdbc.update("UPDATE core_customer SET grade=?,note_cipher=?,profile_status=? WHERE id=?",optionalText(row,"grade"),
                optionalText(row,"note")==null?null:cipher.encrypt(optionalText(row,"note")),row.path("status").asText("active"),customerId);
            Map<String,byte[]> indexes=cipher.blindIndexes("customer_name",text(row,"name").trim().replaceAll("\\s+"," "));
            Map<String,String> searchIndexes=SavedViewSearchNgrams.encode(cipher,text(row,"name"));
            for(Map.Entry<String,byte[]> entry:indexes.entrySet()) jdbc.update("INSERT INTO core_customer_name_index(customer_id,key_id,value_hash,search_ngrams) VALUES(?,?,?,?)",
                customerId,entry.getKey(),entry.getValue(),searchIndexes.get(entry.getKey()));
            ArrayNode contacts=(ArrayNode)array(row,"contacts");
            for(JsonNode contact:contacts) contact(customerId,contact);
            if(row.hasNonNull("phone")&&!containsPhone(contacts,row.path("phone").asText())) {
                insertContact(customerId,new ContactSource(text(row,"id")+"-primary-phone","other",null,text(row,"phone"),contacts.isEmpty()));
            }
            if(row.hasNonNull("studentId")) {
                long student=id("core_student",text(row,"studentId"));
                int assigned=jdbc.update("UPDATE core_student SET customer_id=? WHERE id=? AND "
                    + "(customer_id IS NULL OR customer_id=?)",customerId,student,customerId);
                if(assigned!=1) throw invalid("历史学生关联到多个客户，须先人工核对");
                jdbc.update("INSERT INTO core_customer_student(public_id,customer_id,student_id,relationship,created_at,updated_at) VALUES(?,?,?,'unspecified',?,?)",
                    text(row,"id")+"-student-link",customerId,student,date(row,"createdAt"),date(row,"updatedAt"));
            }
        } catch(RuntimeException e) { throw e; }
    }
    private void contact(long customerId,JsonNode row) {
        insertContact(customerId,new ContactSource(text(row,"id"),"other",row.hasNonNull("relationship")?text(row,"relationship"):null,
            text(row,"phone"),row.path("primary").asBoolean(false)));
        String name=optionalText(row,"name");
        jdbc.update("UPDATE core_customer_contact SET contact_name_cipher=? WHERE public_id=?",
            name==null?null:cipher.encrypt(name),text(row,"id"));
        if(name!=null) {
            Map<String,String> indexes=SavedViewSearchNgrams.encode(cipher,name);
            indexes.forEach((key,value)->jdbc.update("UPDATE core_customer_contact_index ci "
                + "JOIN core_customer_contact cc ON cc.id=ci.contact_id SET ci.name_search_ngrams=? "
                + "WHERE cc.public_id=? AND ci.key_id=?",value,text(row,"id"),key));
        }
    }
    private record ContactSource(String publicId,String role,String relationship,String phone,boolean primary) { }
    private void insertContact(long customerId,ContactSource source) {
        jdbc.update("INSERT INTO core_customer_contact(public_id,customer_id,person_role,source_relationship_cipher,channel,value_cipher,is_primary) VALUES(?,?,?,?,'phone',?,?)",
            source.publicId(),customerId,source.role(),source.relationship()==null?null:cipher.encrypt(source.relationship()),cipher.encrypt(source.phone()),source.primary());
        long contactId=id("core_customer_contact",source.publicId());
        Map<String,String> searchIndexes=SavedViewSearchNgrams.encode(cipher,source.phone());
        for(Map.Entry<String,byte[]> entry:cipher.blindIndexes("phone",source.phone()).entrySet()) jdbc.update("INSERT INTO core_customer_contact_index(contact_id,key_id,value_hash,value_search_ngrams) VALUES(?,?,?,?)",
            contactId,entry.getKey(),entry.getValue(),searchIndexes.get(entry.getKey()));
    }
    private void campaign(JsonNode row) {
        String status=switch(text(row,"status")) {case "published"->"active";case "draft"->"draft";case "closed"->"closed";default->throw invalid("营销活动状态未映射");};
        boolean missingSchedule=!row.hasNonNull("start")||!row.hasNonNull("end");
        Timestamp starts=optionalDate(row,"start"),ends=optionalDate(row,"end");
        if(starts!=null&&ends!=null&&ends.before(starts)) throw invalid("营销活动结束时间必须晚于开始时间");
        Long campus=optionalId("core_campus",row,"campusId");long owner=id("core_principal",text(row,"ownerId"));
        jdbc.update("INSERT INTO core_activity(public_id,campus_id,owner_id,source_created_by_id,title,activity_type,status,starts_at,ends_at,legacy_schedule_missing,description_cipher,version,created_at,updated_at) VALUES(?,?,?,? ,?,'campaign',?,?,?,?,?,?,?,?)",
            text(row,"id"),campus,owner,optionalId("core_principal",row,"createdBy"),text(row,"name"),status,starts,ends,missingSchedule,
            row.hasNonNull("description")?cipher.encrypt(text(row,"description")):null,positive(row,"version"),date(row,"createdAt"),date(row,"updatedAt"));
        long activityId=id("core_activity",text(row,"id"));
        jdbc.update("UPDATE core_activity SET channel=?,budget_minor=?,target_count=? WHERE id=?",optionalText(row,"channel"),
            row.hasNonNull("budgetMinor")?row.get("budgetMinor").longValue():null,row.hasNonNull("targetCount")?row.get("targetCount").intValue():null,activityId);
        for(JsonNode member:array(row,"participantIds")) member(activityId,member.asText(),"participant");
        for(JsonNode member:array(row,"promoterIds")) member(activityId,member.asText(),"promoter");
    }
    private void member(long activityId,String principalPublicId,String role) {
        jdbc.update("INSERT INTO core_activity_member(activity_id,principal_id,member_role) VALUES(?,?,?)",activityId,id("core_principal",principalPublicId),role);
    }
    private void touchpoint(JsonNode row) {
        String source=text(row,"source"),channel=text(row,"channel");
        String sourceType=switch(source) {case "phone"->"phone";case "form"->"online_form";case "referral"->"referral";case "manual","模拟演示"->"other";default->"other";};
        String targetChannel=switch(channel) {case "phone"->"phone";case "wechat"->"wecom";case "visit"->"in_person";case "manual","模拟演示"->"import";case "text"->"text";case "form"->"form";default->throw invalid("客户触点渠道未映射");};
        Long activity=optionalId("core_activity",row,"campaignId");Long student=optionalId("core_student",row,"studentId");
        jdbc.update("INSERT INTO core_customer_touchpoint(public_id,customer_id,activity_id,student_id,actor_id,owner_id,source,channel,summary_cipher,occurred_at,created_at,updated_at,source_label,lead_id,campus_id,subject) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            text(row,"id"),id("core_customer",text(row,"customerId")),activity,student,id("core_principal",text(row,"createdBy")),optionalId("core_principal",row,"ownerId"),sourceType,targetChannel,cipher.encrypt(row.path("note").asText("")),date(row,"at"),date(row,"createdAt"),date(row,"updatedAt"),sourceLabel(row),optionalId("core_crm_lead",row,"leadId"),optionalId("core_campus",row,"campusId"),optionalText(row,"subject"));
    }
    private void lead(JsonNode row) {
        LeadState state = new LeadState(leadStatus(row), leadSource(row));
        LeadRelations relations = new LeadRelations(optionalId("core_campus",row,"campusId"),
            optionalId("core_student",row,"studentId"), optionalId("core_principal",row,"ownerId"),
            optionalId("core_principal",row,"assigneeId"), optionalId("core_principal",row,"createdBy"));
        LeadIdentity identity = new LeadIdentity(optionalText(row,"name"), optionalText(row,"phone"),
            optionalText(row,"contactPhone"));
        String contactPhone = leadPhone(row);
        if(identity.name()!=null&&contactPhone!=null) identityLocks.lock(identity.name(),contactPhone);
        insertLead(row, state, relations, identity);
    }
    private List<String> collectors(JsonNode row) {
        JsonNode value=row.path("collectedByIds");if(value.isMissingNode()||value.isNull())return List.of();
        if(!value.isArray())throw invalid("线索采集人员必须是数组");
        java.util.LinkedHashSet<String> ids=new java.util.LinkedHashSet<>();
        for(JsonNode item:value) {
            if(!item.isTextual()||item.asText().isBlank()||item.asText().length()>120||!ids.add(item.asText()))
                throw invalid("线索采集人员无效或重复");
        }
        return List.copyOf(ids);
    }
    private String leadStatus(JsonNode row) {
        return switch(text(row,"stage")) {
            case "新线索", "未跟进"->"new";case "跟进中", "待报名"->"contacting";
            case "已转化", "已报名"->"qualified";case "已流失"->"archived";
            case "无需求"->"no_need";case "无法联系"->"unreachable";case "无效"->"invalid_contact";
            case "已归档"->"archived";default->throw invalid("线索阶段未映射");
        };
    }
    private String leadSource(JsonNode row) {
        return switch(text(row,"source")) {
            case "线上表单"->"online_form";case "前台登记"->"front_desk";case "电话"->"phone";
            case "转介绍"->"referral";default->"other";
        };
    }
    /** Keeps the original selector for exact routing; reporting classification is stored separately. */
    private String sourceLabel(JsonNode row) {
        String value = text(row, "source");
        if (value.length() > 120) { throw invalid("历史来源超过长度限制"); }
        return value;
    }
    private String leadPhone(JsonNode row) {
        String phone=optionalText(row,"contactPhone");
        return phone==null?optionalText(row,"phone"):phone;
    }
    private record LeadState(String status, String source) { }
    private record LeadRelations(Long campus, Long student, Long owner, Long assignee, Long creator) { }
    private record LeadIdentity(String name, String phone, String contactPhone) { }
    private void insertLead(JsonNode row, LeadState state, LeadRelations relations, LeadIdentity identity) {
        String decisionStatus = leadDecisionStatus(row);
        Long decisionBy = optionalId("core_principal", row, "decisionById");
        Timestamp decisionDeadline = optionalDate(row, "decisionDeadline");
        Timestamp decisionTimedOutAt = optionalDate(row, "decisionTimedOutAt");
        jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,campus_id,activity_id,student_id,owner_id,assignee_id,decision_status,decision_by_id,decision_deadline,decision_timed_out_at,source_created_by_id,source,subject,term,title,status,detail_cipher,name_cipher,phone_cipher,contact_phone_cipher,next_follow_at,version,created_at,updated_at,source_label) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            text(row,"id"),optionalId("core_customer",row,"customerId"),relations.campus(),optionalId("core_activity",row,"campaignId"),relations.student(),
            relations.owner(),relations.assignee(),decisionStatus,decisionBy,decisionDeadline,decisionTimedOutAt,
            relations.creator(),state.source(),optionalText(row,"subject"),
            null,"历史导入线索",state.status(),cipher.encrypt(row.toString()),
            identity.name()==null?null:cipher.encrypt(identity.name()),identity.phone()==null?null:cipher.encrypt(identity.phone()),
            identity.contactPhone()==null?null:cipher.encrypt(identity.contactPhone()),
            optionalDate(row,"nextFollowAt"),positive(row,"version"),date(row,"createdAt"),date(row,"updatedAt"),sourceLabel(row));
        long leadId = id("core_crm_lead", text(row, "id"));
        jdbc.update("UPDATE core_crm_lead SET legacy_stage=?,legacy_status=?,assigned_at=?,"
            + "assignment_follow_at=?,next_action=?,registrar_id=?,intent=? WHERE id=?",
            text(row, "stage"), optionalText(row, "status") == null ? "new" : optionalText(row, "status"),
            optionalDate(row, "assignedAt"), optionalDate(row, "assignmentFollowAt"),
            optionalText(row, "next") == null ? "未约定" : optionalText(row, "next"), optionalId("core_principal",row,"registrarId"), leadIntent(row), leadId);
        for(String collector:collectors(row)) jdbc.update("INSERT INTO core_crm_lead_collector(lead_id,principal_id,created_at,updated_at) VALUES(?,?,?,?)",
            leadId,id("core_principal",collector),date(row,"createdAt"),date(row,"updatedAt"));
        jdbc.update("UPDATE core_crm_lead SET grade=?,guardian_name_cipher=?,profile_note_cipher=? WHERE id=?",optionalText(row,"grade"),
            optionalText(row,"guardian")==null?null:cipher.encrypt(optionalText(row,"guardian")),
            optionalText(row,"note")==null?null:cipher.encrypt(optionalText(row,"note")),leadId);
        SavedViewLeadSearch.save(jdbc, cipher, leadId, new SavedViewLeadSearch.Fields(identity.name(),
            identity.phone(), identity.contactPhone(), optionalText(row, "guardian")));
        JsonNode history = row.path("decisionHistory");
        if (history.isArray()) {
            for (int index = 0; index < history.size(); index++) {
                importLeadDecisionHistory(leadId, history.get(index), index, row);
            }
        }
    }
    private String leadDecisionStatus(JsonNode row) {
        String status = optionalText(row, "decisionStatus");
        if (status == null) return "public";
        if (!Set.of("pending", "retained", "transferred", "timed_out", "public", "reclaimed").contains(status)) {
            throw invalid("线索决策状态未映射");
        }
        return status;
    }
    private void importLeadDecisionHistory(long leadId, JsonNode item, int ordinal, JsonNode lead) {
        String kind = optionalText(item, "kind");
        String value = optionalText(item, "decision");
        String decision = value == null ? switch (kind == null ? "" : kind) {
            case "created" -> leadDecisionStatus(lead);
            case "decision_timeout" -> "timeout";
            default -> "source_snapshot";
        } : value;
        if (!Set.of("pending", "public", "retained", "transferred", "timed_out", "reclaimed",
                "retain", "transfer", "release", "timeout", "claim", "reclaim").contains(decision)) {
            throw invalid("线索决策历史动作未映射");
        }
        String actorPublicId = optionalText(item, "actorId");
        String ownerPublicId = optionalText(item, "ownerId");
        Long actor = actorPublicId == null ? null : id("core_principal", actorPublicId);
        Long owner = ownerPublicId == null ? null : id("core_principal", ownerPublicId);
        Timestamp occurredAt = optionalDate(item, "at");
        if (occurredAt == null) occurredAt = date(lead, "createdAt");
        Timestamp deadline = optionalDate(item, "decisionDeadline");
        jdbc.update("""
            INSERT INTO core_crm_lead_decision_event
                (public_id,lead_id,actor_id,decision,owner_id,decision_deadline,occurred_at,source_ordinal,snapshot_cipher)
            VALUES (?,?,?,?,?,?,?,?,?)
            """, text(lead, "id") + "_decision_" + ordinal, leadId, actor, decision, owner, deadline,
            occurredAt, ordinal, cipher.encrypt(item.toString()));
    }
    private void opportunity(JsonNode row) {
        String stage=text(row,"stage");if(!Set.of("qualified","appointment","trial","negotiation","won","closed").contains(stage)) throw invalid("商机阶段未映射");
        long customer=id("core_customer",text(row,"customerId")),owner=id("core_principal",text(row,"ownerId"));
        long campus=id("core_campus",text(row,"campusId"));Long student=optionalId("core_student",row,"studentId");
        Long activity=optionalId("core_activity",row,"campaignId");Long createdBy=optionalId("core_principal",row,"createdBy");
        Long wonOrder=null;JsonNode orders=array(row,"orderIds");if(!orders.isEmpty()) {if(orders.size()!=1||!"won".equals(stage)) throw invalid("商机订单关联需先迁移订单事实");wonOrder=id("core_crm_order",orders.get(0).asText());}
        String subject=text(row,"subject"),term=text(row,"term"),title=text(row,"title");
        byte[] needKey=OpportunityNeedSupport.demandKey(new OpportunityNeedSupport.IdentityKey(student,campus,null,subject,term));
        jdbc.update("INSERT INTO core_opportunity(public_id,customer_id,campus_id,owner_id,source_created_by_id,activity_id,student_id,title,subject,term,need_key,stage,detail_cipher,next_follow_at,won_order_id,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            text(row,"id"),customer,campus,owner,createdBy,activity,student,title,subject,term,needKey,stage,cipher.encrypt(row.toString()),optionalDate(row,"nextFollowAt"),wonOrder,positive(row,"version"),date(row,"createdAt"),date(row,"updatedAt"));
    }
    private void reconcile(JsonNode source) {
        reconcileCollections(source);
        reconcileCustomerContacts(source);reconcileLeadReaders(source);
        reconcileStudentOrigins(source);
    }
    private void reconcileCollections(JsonNode source) {
        for(String key:List.of("customers","campaigns","touchpoints","leads","opportunities")) {
            HashSet<String> ids=new HashSet<>();
            for(JsonNode row:array(source,key)) {
                String publicId=text(row,"id");if(!ids.add(publicId)) throw invalid("CRM源ID重复："+key);
                if(id(keyTable(key),publicId)<1) throw invalid("CRM来源ID对账失败");
            }
        }
    }
    private void reconcileLeadReaders(JsonNode source) {
        for(JsonNode lead:array(source,"leads")) {
            long leadId=id("core_crm_lead",text(lead,"id"));
            Long registrar=jdbc.queryForObject("SELECT registrar_id FROM core_crm_lead WHERE id=?",Long.class,leadId);
            if(!java.util.Objects.equals(registrar,optionalId("core_principal",lead,"registrarId")))throw invalid("线索登记关系对账失败");
            List<String> actual=jdbc.query("SELECT p.public_id FROM core_crm_lead_collector lc JOIN core_principal p ON p.id=lc.principal_id WHERE lc.lead_id=? AND lc.deleted=0",
                (rs,n)->rs.getString(1),leadId);
            if(!Set.copyOf(actual).equals(Set.copyOf(collectors(lead))))throw invalid("线索采集关系对账失败");
        }
    }
    private void reconcileCustomerContacts(JsonNode source) {
        for(JsonNode customer:array(source,"customers")) {
            for(JsonNode contact:array(customer,"contacts")) id("core_customer_contact",text(contact,"id"));
            if(customer.hasNonNull("studentId")) id("core_customer_student",text(customer,"id")+"-student-link");
        }
    }
    private void reconcileStudentOrigins(JsonNode source) {
        JsonNode academic = academicCatalogSnapshot();
        for(JsonNode student:array(academic,"students")) {
            String leadPublicId=optionalText(student,"originLeadId");
            if(leadPublicId==null) continue;
            reconcileStudentOrigin(student, leadPublicId);
        }
    }
    private void reconcileStudentOrigin(JsonNode student, String leadPublicId) {
        long studentId=id("core_student",text(student,"id")),leadId=id("core_crm_lead",leadPublicId);
        int leadRows=jdbc.update("UPDATE core_crm_lead SET student_id=? WHERE id=? AND "
            + "(student_id IS NULL OR student_id=?)",studentId,leadId,studentId);
        Long customerId=jdbc.queryForObject("SELECT customer_id FROM core_crm_lead WHERE id=?",Long.class,leadId);
        int studentRows=jdbc.update("UPDATE core_student SET origin_lead_id=?,customer_id=COALESCE(customer_id,?) "
            + "WHERE id=? AND (origin_lead_id IS NULL OR origin_lead_id=?) "
            + "AND (customer_id IS NULL OR ? IS NULL OR customer_id=?)",
            leadId,customerId,studentId,leadId,customerId,customerId);
        if(leadRows!=1||studentRows!=1) throw invalid("历史学生与来源线索关联冲突，须先人工核对");
        if(customerId!=null) insertStudentCustomerLink(student, customerId, studentId);
    }
    private void insertStudentCustomerLink(JsonNode student, Long customerId, long studentId) {
        jdbc.update("""
            INSERT INTO core_customer_student(public_id,customer_id,student_id,relationship)
            SELECT ?,?,?, 'unspecified' WHERE NOT EXISTS (SELECT id FROM core_customer_student
                WHERE customer_id=? AND student_id=? AND deleted=0)
            """, "CUSSTU_"+text(student,"id")+"-legacy",customerId,studentId,customerId,studentId);
    }
    private JsonNode academicCatalogSnapshot() {
        String encrypted=jdbc.queryForObject("SELECT source_cipher FROM core_migration_checkpoint "
            + "WHERE phase='academic_catalog_v1' AND deleted=0",String.class);
        try { return mapper.readTree(cipher.decrypt(encrypted)); }
        catch(Exception error) { throw invalid("教务学生迁移快照不可读取"); }
    }
    private String keyTable(String collection) {return switch(collection) {case "customers"->"core_customer";case "campaigns"->"core_activity";case "touchpoints"->"core_customer_touchpoint";case "leads"->"core_crm_lead";case "opportunities"->"core_opportunity";default->throw invalid("未知CRM集合");};}
    private long id(String table,String publicId) {
        if(!LOOKUP_TABLES.contains(table)&&!Set.of("core_customer_contact","core_customer_student","core_customer_touchpoint","core_crm_lead","core_opportunity").contains(table)) throw invalid("未知迁移关系目标");
        List<Long> ids=jdbc.query("SELECT id FROM "+table+" WHERE public_id=? AND deleted=0",(rs,n)->rs.getLong(1),publicId);
        if(ids.size()!=1) throw invalid("CRM关联未迁移或ID不唯一");return ids.get(0);
    }
    private Long optionalId(String table,JsonNode row,String field) {return row.hasNonNull(field)&&!row.path(field).asText().isBlank()?id(table,text(row,field)):null;}
    private static ArrayNode array(JsonNode row,String key) {JsonNode value=row.path(key);if(!value.isArray()) throw invalid("迁移集合格式无效："+key);return (ArrayNode)value;}
    private static boolean containsPhone(ArrayNode contacts,String value) {
        for(JsonNode contact:contacts) if(value.equals(contact.path("phone").asText())) return true;
        return false;
    }
    private static String text(JsonNode row,String key) {JsonNode value=row.path(key);if(!value.isTextual()||value.asText().isBlank()) throw invalid("迁移必填字段无效："+key);return value.asText();}
    private static String optionalText(JsonNode row,String key) {JsonNode value=row.path(key);return value.isTextual()&&!value.asText().isBlank()?value.asText():null;}
    private static long positive(JsonNode row,String key) {JsonNode value=row.path(key);if(!value.isIntegralNumber()||value.longValue()<1) throw invalid("迁移正整数字段无效："+key);return value.longValue();}
    private static Timestamp date(JsonNode row,String key) {
        String value=text(row,key);
        try {return Timestamp.from(Instant.parse(value));}
        catch(java.time.format.DateTimeParseException ignored) {return Timestamp.from(LocalDate.parse(value).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());}
    }
    private static Timestamp optionalDate(JsonNode row,String key) {return row.hasNonNull(key)?date(row,key):null;}
    private static IllegalArgumentException invalid(String message) {return new IllegalArgumentException(message);}
}
