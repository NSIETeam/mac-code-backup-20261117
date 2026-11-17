package cn.siyue.core;

import static cn.siyue.core.CrmProfileValues.*;
import cn.siyue.core.CrmProfileDtos.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Versioned customer profile edits and explicit historical lead conversion. */
@Repository
public class CrmCustomerProfileRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    /** Wires encrypted customer profile persistence and lookup indexes.
     * @param jdbc transaction-bound database operations
     * @param cipher keyring for profile fields and keyed identity/search indexes
     */
    public CrmCustomerProfileRepository(JdbcTemplate jdbc,PiiCipher cipher) {this.jdbc=jdbc;this.cipher=cipher;}
    /** Reads one customer profile through customer read relationships, without granting edit rights.
     * @param actor caller whose current customer read scope is applied
     * @param publicId customer public identifier
     * @return decrypted profile and active phone contacts, including version and profile state
     * @throws BusinessException if the customer identifier is invalid, missing or out of read scope
     */
    public Customer profile(Actor actor,String publicId) {
        long id=customerId(actor,publicId,false);
        return jdbc.query("SELECT c.public_id,c.display_name_cipher,c.grade,c.note_cipher,c.profile_status,c.version,"
            + "cp.public_id campus,p.public_id owner FROM core_customer c LEFT JOIN core_campus cp ON cp.id=c.campus_id "
            + "LEFT JOIN core_principal p ON p.id=c.owner_id WHERE c.id=?",(rs,row)->new Customer(rs.getString(1),
                decrypted(rs.getString(2)),rs.getString(3),decrypted(rs.getString(4)),rs.getString(7),rs.getString(8),
                rs.getString(5),rs.getLong(6),contacts(id)),id).get(0);
    }
    /** Reads the lead fields needed to select or create a matching customer profile.
     * @param actor caller whose lead read relationships are applied
     * @param id public lead identifier
     * @return lead name, phone, grade, linked customer identifier and lead version
     * @throws BusinessException if the identifier is invalid or the lead is outside read scope
     */
    public LeadProfile leadProfile(Actor actor,String id) {
        Lead source=lead(actor,id,false);String customer=source.customer()==null?null:jdbc.queryForObject("SELECT public_id FROM core_customer WHERE id=?",String.class,source.customer());
        return new LeadProfile(id,source.name(),source.phone(),source.grade(),customer,source.version());
    }
    /** Locks a lead using the customer-link mutation predicate before new work or replay.
     * @param actor caller whose write scope and applicable unowned-registrar exception are checked
     * @param publicId lead public identifier
     * @throws BusinessException if the lead is invalid, missing or unavailable for customer linking
     */
    public void authorizeLead(Actor actor,String publicId) {lead(actor,publicId,true);}
    /** Requires profile mutation scope before a retained retry receipt can be disclosed. */
    void authorizeWrite(Actor actor,String publicId) {customerId(actor,publicId,true);}
    /** Updates an active customer profile with optimistic version and exact patch-key validation.
     * @param actor caller already authorized for profile editing; campus changes also require lead.assign
     * @param publicId customer public identifier
     * @param input allowed profile/contact changes and expected customer version
     * @return updated profile after read visibility is rechecked; phone/name indexes are updated with their fields
     * @throws BusinessException if mutation scope, profile state, version, campus, contact data or patch keys are invalid
     */
    public Customer update(Actor actor,String publicId,Patch input) {
        Map<String,Object> patch=input.patch();keys(patch,Set.of("name","grade","note","campusId","contacts"));
        long customer=customerId(actor,publicId,true);Customer old=profile(actor,publicId);active(old);version(old.version(),input.expectedVersion());
        String name=patch.containsKey("name")?text(patch.get("name"),100,true):old.name();
        String grade=patch.containsKey("grade")?text(patch.get("grade"),50,false):old.grade();
        String note=patch.containsKey("note")?text(patch.get("note"),5000,false):old.note();
        Long campus=patch.containsKey("campusId")?campus(actor,patch.get("campusId")):nullableCampus(customer);
        jdbc.update("UPDATE core_customer SET display_name_cipher=?,grade=?,note_cipher=?,campus_id=?,version=version+1,"
            + "updated_at=UTC_TIMESTAMP(3) WHERE id=?",cipher.encrypt(name),grade,encrypted(note),campus,customer);
        if(!old.name().equals(name)) indexName(customer,name);
        if(patch.containsKey("contacts")) replaceContacts(customer,patch.get("contacts"),old.contacts());
        customerId(actor,publicId,false);return profile(actor,publicId);
    }
    private List<Contact> contacts(long customer) {
        return jdbc.query("SELECT public_id,contact_name_cipher,source_relationship_cipher,value_cipher,is_primary "
            + "FROM core_customer_contact WHERE customer_id=? AND channel='phone' AND deleted=0 ORDER BY id",
            (rs,row)->new Contact(rs.getString(1),decrypted(rs.getString(2)),decrypted(rs.getString(3)),
                decrypted(rs.getString(4)),rs.getBoolean(5)),customer);
    }
    private void replaceContacts(long customer,Object raw,List<Contact> previous) {
        if(!(raw instanceof List<?> list)||list.isEmpty()||list.size()>10) throw invalid("请填写1至10个联系方式");
        List<Contact> next=new ArrayList<>();Set<String> phones=new HashSet<>(),ids=new HashSet<>();
        for(Object item:(List<?>)raw) {
            Contact contact=parseContact(item,previous);
            if(!phones.add(contact.phone())||!ids.add(contact.id())) throw invalid("联系方式手机号或编号重复");
            next.add(contact);
        }
        if(next.stream().filter(Contact::primary).count()>1) throw invalid("只能设置一个主要联系方式");
        if(next.stream().noneMatch(Contact::primary)) {Contact first=next.get(0);next.set(0,new Contact(first.id(),first.name(),first.relationship(),first.phone(),true));}
        jdbc.update("UPDATE core_customer_contact_index ci JOIN core_customer_contact cc ON cc.id=ci.contact_id "
            + "SET ci.deleted=1,ci.updated_at=UTC_TIMESTAMP(3) WHERE cc.customer_id=? AND cc.channel='phone'",customer);
        jdbc.update("UPDATE core_customer_contact SET deleted=1,updated_at=UTC_TIMESTAMP(3) WHERE customer_id=? AND channel='phone'",customer);
        for(Contact contact:next) saveContact(customer,contact);
    }
    private Contact parseContact(Object item,List<Contact> previous) {
        if(!(item instanceof Map<?,?> source)) throw invalid("联系方式无效");
        Map<String,Object> value=new java.util.LinkedHashMap<>();
        source.forEach((key,entry)->{if(!(key instanceof String)) throw invalid("联系方式字段无效");value.put((String)key,entry);});
        keys(value,Set.of("id","name","relationship","phone","primary"));String phone=phone(value.get("phone"));
        String selected=value.get("id")==null?null:id(value.get("id"));
        Contact prior=previous.stream().filter(contact->selected==null?phone(contact.phone()).equals(phone):contact.id().equals(selected)).findFirst().orElse(null);
        if(selected!=null&&prior==null) throw invalid("不能指定不存在的联系方式编号");
        String contactId=prior==null?"CONTACT_"+UUID.randomUUID():prior.id();
        if(value.containsKey("primary")&&!(value.get("primary") instanceof Boolean)) throw invalid("主要联系方式选项无效");
        boolean primary=value.containsKey("primary")?(Boolean)value.get("primary"):prior!=null&&prior.primary();
        String relationship=text(value.get("relationship"),50,false);
        if(relationship==null||relationship.isBlank()) relationship="监护人";
        return new Contact(contactId,text(value.get("name"),100,false),relationship,phone,primary);
    }
    private void saveContact(long customer,Contact contact) {
        jdbc.update("INSERT INTO core_customer_contact(public_id,customer_id,person_role,channel,value_cipher,is_primary,contact_name_cipher,source_relationship_cipher) "
            + "VALUES(?,?,'guardian','phone',?,?,?,?) ON DUPLICATE KEY UPDATE value_cipher=VALUES(value_cipher),"
            + "is_primary=VALUES(is_primary),contact_name_cipher=VALUES(contact_name_cipher),source_relationship_cipher=VALUES(source_relationship_cipher),deleted=0,updated_at=UTC_TIMESTAMP(3)",
            contact.id(),customer,cipher.encrypt(contact.phone()),contact.primary(),encrypted(contact.name()),encrypted(contact.relationship()));
        long internal=jdbc.queryForObject("SELECT id FROM core_customer_contact WHERE public_id=?",Long.class,contact.id());
        Map<String,String> valueSearch=SavedViewSearchNgrams.encode(cipher,contact.phone());
        Map<String,String> nameSearch=contact.name()==null?Map.of():SavedViewSearchNgrams.encode(cipher,contact.name());
        cipher.blindIndexes("phone",contact.phone()).forEach((key,hash)->jdbc.update("INSERT INTO core_customer_contact_index "
            + "(contact_id,key_id,value_hash,value_search_ngrams,name_search_ngrams) VALUES(?,?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE value_hash=VALUES(value_hash),value_search_ngrams=VALUES(value_search_ngrams),"
            + "name_search_ngrams=VALUES(name_search_ngrams),deleted=0,updated_at=UTC_TIMESTAMP(3)",
            internal,key,hash,valueSearch.get(key),nameSearch.get(key)));
    }
    private void indexName(long customer,String name) {
        jdbc.update("UPDATE core_customer_name_index SET deleted=1,updated_at=UTC_TIMESTAMP(3) WHERE customer_id=?",customer);
        Map<String,String> search=SavedViewSearchNgrams.encode(cipher,name);
        cipher.blindIndexes("customer_name",name.trim().replaceAll("\\s+"," ")).forEach((key,hash)->jdbc.update("INSERT INTO core_customer_name_index(customer_id,key_id,value_hash,search_ngrams) "
            + "VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE value_hash=VALUES(value_hash),search_ngrams=VALUES(search_ngrams),deleted=0,updated_at=UTC_TIMESTAMP(3)",customer,key,hash,search.get(key)));
    }
    /** Links a locked lead to a matching customer or creates a customer from its recorded identity.
     * @param actor caller authorized by the service; lead and destination customer mutation scope are checked here
     * @param input source lead, optional destination customer and expected lead version
     * @return customer/link receipt; an already linked active customer may be returned without a new write
     * @throws BusinessException if scope, version, identity matching, active profile or existing student/customer links conflict
     */
    public Link fromLead(Actor actor,FromLead input) {
        Lead source=lead(actor,input.leadId(),true);version(source.version(),input.expectedVersion());
        String requested=input.customerId()==null?null:id(input.customerId());
        if(source.customer()!=null) {
            String existing=jdbc.queryForObject("SELECT public_id FROM core_customer WHERE id=?",String.class,source.customer());
            if(requested!=null&&!requested.equals(existing)) throw new BusinessException(ErrorCode.VERSION_CONFLICT,"线索已关联另一客户，请先处理客户合并");
            Customer result=profile(actor,existing);active(result);if(requested==null) return new Link(result,input.leadId(),source.version(),false);
        }
        boolean created=requested==null;long customer;
        if(created) {requested=createFromLead(actor,source);customer=customerId(actor,requested,true);linkStudent(actor,source,customer);}
        else {customer=customerId(actor,requested,true);Customer selected=profile(actor,requested);active(selected);
            if(source.name()==null||!StudentNameIndexRepository.normalize(source.name()).equals(StudentNameIndexRepository.normalize(selected.name()))
                    ||source.phone()==null||selected.contacts().stream().noneMatch(contact->phone(contact.phone()).equals(phone(source.phone())))) throw invalid("客户姓名或手机号与线索不一致，请先核对");}
        jdbc.update("UPDATE core_crm_lead SET customer_id=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",customer,source.id());
        jdbc.update("INSERT INTO core_crm_lead_event(public_id,lead_id,actor_id,event_type,occurred_at,detail_cipher) "
            + "VALUES(?,?,?,'customer_linked',UTC_TIMESTAMP(3),?)","LEADEVT_"+UUID.randomUUID(),source.id(),actor.id(),cipher.encrypt(requested));
        return new Link(profile(actor,requested),input.leadId(),source.version()+1,created);
    }
    private String createFromLead(Actor actor,Lead lead) {
        String name=text(lead.name(),100,true),phone=phone(lead.phone()),publicId="CUS_"+UUID.randomUUID();
        jdbc.update("INSERT INTO core_customer(public_id,campus_id,owner_id,display_name_cipher,grade,note_cipher,source_created_by_id) VALUES(?,?,?,?,?,?,?)",
            publicId,lead.campus(),lead.owner(),cipher.encrypt(name),lead.grade(),encrypted(lead.note()),actor.id());
        long customer=jdbc.queryForObject("SELECT id FROM core_customer WHERE public_id=?",Long.class,publicId);indexName(customer,name);
        saveContact(customer,new Contact("CONTACT_"+UUID.randomUUID(),lead.guardian(),"监护人",phone,true));return publicId;
    }
    private void linkStudent(Actor actor,Lead lead,long customer) {
        if(!actor.permissions().contains("student.read")) return;
        ScopeSql.Clause scope=ScopeSql.studentWrite(actor);List<Object> args=new ArrayList<>();args.add(lead.student());args.add(lead.id());args.addAll(scope.args());
        List<Long> students=jdbc.query("SELECT s.id FROM core_student s WHERE s.deleted=0 AND (s.id=? OR s.origin_lead_id=?) AND ("
            + scope.sql()+") ORDER BY s.id FOR UPDATE",(rs,row)->rs.getLong(1),args.toArray());
        for(long student:students) {
            Long old=jdbc.queryForObject("SELECT customer_id FROM core_student WHERE id=?",Long.class,student);
            if(old!=null&&old!=customer||jdbc.queryForObject("SELECT COUNT(*) FROM core_customer_student WHERE student_id=? AND customer_id<>? AND deleted=0",Long.class,student,customer)>0) throw new BusinessException(ErrorCode.VERSION_CONFLICT,"学生已关联其它客户，请人工核对");
            jdbc.update("UPDATE core_student SET customer_id=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",customer,student);
            jdbc.update("INSERT INTO core_customer_student(public_id,customer_id,student_id,relationship) VALUES(?,?,?,'unspecified')","CUSSTU_"+UUID.randomUUID(),customer,student);
        }
    }
    /** Locked source lead identity and existing customer/student links used only during profile conversion. */
    private record Lead(long id,Long customer,Long campus,Long owner,Long student,String name,String phone,
            String grade,String guardian,String note,long version) { }
    private Lead lead(Actor actor,String publicId,boolean lock) {
        ScopeSql.Clause scope=lock?leadLinkScope(actor):ScopeSql.leadReads(actor);List<Object> args=new ArrayList<>(List.of(id(publicId)));args.addAll(scope.args());
        return jdbc.query("SELECT l.id,l.customer_id,l.campus_id,l.owner_id,l.student_id,l.name_cipher,"
            + "COALESCE(l.contact_phone_cipher,l.phone_cipher),l.grade,l.guardian_name_cipher,l.profile_note_cipher,l.version "
            + "FROM core_crm_lead l WHERE l.public_id=? AND l.deleted=0 AND ("+scope.sql()+")"+(lock?" FOR UPDATE":""),
            (rs,row)->new Lead(rs.getLong(1),rs.getObject(2,Long.class),rs.getObject(3,Long.class),rs.getObject(4,Long.class),rs.getObject(5,Long.class),
                decrypted(rs.getString(6)),decrypted(rs.getString(7)),rs.getString(8),decrypted(rs.getString(9)),decrypted(rs.getString(10)),rs.getLong(11)),args.toArray())
            .stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND,"线索不存在或不在授权范围"));
    }
    private ScopeSql.Clause leadLinkScope(Actor actor) {
        var write=ScopeSql.leadWrites(actor);
        if(!actor.permissions().contains("lead.create")||!Set.of("own","assigned").contains(actor.scope()))return write;
        List<Object> args=new ArrayList<>(write.args());args.add(actor.id());
        return new ScopeSql.Clause("("+write.sql()+" OR (l.owner_id IS NULL AND l.registrar_id=?))",args);
    }
    private long customerId(Actor actor,String publicId,boolean lock) {
        ScopeSql.Clause scope=lock?ScopeSql.customers(actor):ScopeSql.customerReads(actor);List<Object> args=new ArrayList<>(List.of(id(publicId)));args.addAll(scope.args());
        return jdbc.query("SELECT c.id FROM core_customer c WHERE c.public_id=? AND c.deleted=0 AND ("+scope.sql()+")"+(lock?" FOR UPDATE":""),
            (rs,row)->rs.getLong(1),args.toArray()).stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND,"客户不存在或不在授权范围"));
    }
    private Long campus(Actor actor,Object value) {
        actor.require("lead.assign");if(value==null) {if(actor.scope().equals("campus")) throw invalid("请选择授权校区");return null;}
        long campus=jdbc.query("SELECT id FROM core_campus WHERE public_id=? AND active=1 AND deleted=0 FOR UPDATE",(rs,row)->rs.getLong(1),id(value))
            .stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND,"校区不存在或已停用"));
        if(!actor.scope().equals("all")&&!actor.campusIds().contains(campus)) throw new BusinessException(ErrorCode.FORBIDDEN,"校区不在授权范围");return campus;
    }
    private Long nullableCampus(long customer) {return jdbc.queryForObject("SELECT campus_id FROM core_customer WHERE id=?",Long.class,customer);}
    private void active(Customer customer) {if(!Objects.equals(customer.status(),"active")) throw new BusinessException(ErrorCode.VERSION_CONFLICT,"客户已合并或归档");}
    private String phone(Object value) {String result=text(value,50,true).replaceAll("[\\s()\\-]","");if(!result.matches("\\+?[0-9]{6,20}")) throw invalid("手机号无效");return result;}
    private String encrypted(String value) {return value==null?null:cipher.encrypt(value);}
    private String decrypted(String value) {return value==null?null:cipher.decrypt(value);}
}
