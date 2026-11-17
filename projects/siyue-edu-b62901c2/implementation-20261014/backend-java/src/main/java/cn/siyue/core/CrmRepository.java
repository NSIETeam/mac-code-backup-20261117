package cn.siyue.core;

import cn.siyue.core.CrmActivityMemberships.MemberLists;
import cn.siyue.core.CrmDtos.Customer;
import cn.siyue.core.CrmDtos.CustomerContact;
import cn.siyue.core.CrmDtos.CustomerContactInput;
import cn.siyue.core.CrmDtos.CustomerInput;
import cn.siyue.core.CrmDtos.CustomerPage;
import cn.siyue.core.CrmDtos.CustomerStudent;
import cn.siyue.core.CrmDtos.CustomerStudentInput;
import cn.siyue.core.CrmDtos.CustomerSummary;
import cn.siyue.core.CrmDtos.Activity;
import cn.siyue.core.CrmDtos.ActivityInput;
import cn.siyue.core.CrmDtos.ActivityMembersInput;
import cn.siyue.core.CrmDtos.ActivityPage;
import cn.siyue.core.CrmDtos.Touchpoint;
import cn.siyue.core.CrmDtos.TouchpointInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists customer identity separately from student, lead and opportunity records. */
@Repository
public class CrmRepository {
    /** Activity scalar projection before batched membership hydration. */
    private record ActivityRow(long id, String publicId, String campusId, String ownerId,
            String title, String activityType, String status, Instant startsAt, Instant endsAt,
            String descriptionCipher, long version) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final CrmActivityMemberships memberships;
    /** Uses the same MySQL connection and versioned PII keyring as academic records.
     * @param jdbc transaction-bound MySQL access
     * @param cipher versioned encryption, masking and blind-index keyring
     */
    public CrmRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.memberships = new CrmActivityMemberships(jdbc);
    }
    /** Checks campus authorization before a customer create or idempotent replay.
     * @param actor current permission and data-scope snapshot
     * @param campusPublicId target campus public identifier
     * @throws BusinessException if the campus is missing or outside the actor scope
     */
    public void authorizeCampus(Actor actor, String campusPublicId) { campus(actor, campusPublicId, true); }
    /** Checks write scope for a household before a compound intake or idempotent replay.
     * @param actor current permission and data-scope snapshot
     * @param customerPublicId household public identifier to lock
     * @throws BusinessException if the household is absent or outside write scope
     */
    public void authorizeCustomerWrite(Actor actor, String customerPublicId) {
        customerId(actor, customerPublicId, true);
    }
    /** Creates one customer identity with encrypted contact values and key-versioned blind indexes.
     * @param actor creator and initial household owner
     * @param input validated campus, display name and optional contacts
     * @return version-one household with masked contacts and no linked students
     * @throws BusinessException if the campus is inaccessible or a contact value is invalid
     */
    public Customer createCustomer(Actor actor, CustomerInput input) {
        long campusId = campus(actor, input.campusId(), true);
        String publicId = "CUS_" + UUID.randomUUID();
        String name = normalizeName(input.displayName());
        jdbc.update("""
            INSERT INTO core_customer (public_id,campus_id,owner_id,display_name_cipher,source_created_by_id)
            VALUES (?,?,?,?,?)
            """, publicId, campusId, actor.id(), cipher.encrypt(name),actor.id());
        long customerId = customerId(publicId);
        insertIndexes("core_customer_name_index", "customer_id", customerId, "customer_name", name);
        List<CustomerContact> contacts = new ArrayList<>();
        for (CustomerContactInput contact : safeContacts(input.contacts())) {
            contacts.add(insertContact(customerId, contact));
        }
        return new Customer(publicId, input.campusId(), actor.publicId(), name, 1,
            List.copyOf(contacts), List.of());
    }
    /** Lists a bounded customer page by exact name or contact match under actor scope.
     * @param actor current customer read scope
     * @param query optional exact name, contact or public identifier
     * @param page validated one-based page
     * @param size validated page limit
     * @return scoped customer summaries and matching total
     */
    public CustomerPage customers(Actor actor, String query, int page, int size) {
        return customerPage(actor, customerSearch(actor, query), page, size);
    }
    /** Typed searches retain the same customer-unit count and bounded page predicate.
     * @param actor current customer read scope
     * @param input validated typed search and pagination
     * @return scoped summaries and count using the same search predicate
     */
    public CustomerPage customers(Actor actor, CustomerSearchDtos.Query input) {
        if ("auto".equals(input.searchType())) { return customers(actor, input.query(), input.page(), input.size()); }
        var filter = new CustomerSearchRepository(jdbc, cipher).filter(actor, input);
        return customerPage(actor, new SearchFilter(" AND (" + filter.sql() + ")", filter.args()), input.page(), input.size());
    }
    /** Runs imported saved-view substring search over customer-visible encrypted fields.
     * @param actor current customer read scope
     * @param savedView validated saved-view search definition
     * @param page validated one-based page
     * @param size validated page limit
     * @return summaries matching the indexed substring or an empty page
     */
    public CustomerPage customers(Actor actor, LegacySavedViewQuery savedView, int page, int size) {
        if (savedView.search() == null || savedView.search().isBlank()) {
            return customerPage(actor, new SearchFilter("", List.of()), page, size);
        }
        List<Long> ids = List.copyOf(SavedViewCustomerSearch.matchingIds(jdbc, cipher, actor, savedView.search()));
        if (ids.isEmpty()) { return customerPage(actor, new SearchFilter(" AND 1=0", List.of()), page, size); }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> idArgs = new ArrayList<>(ids);
        return customerPage(actor, new SearchFilter(" AND c.id IN (" + placeholders + ")", idArgs), page, size);
    }
    private CustomerPage customerPage(Actor actor, SearchFilter search, int page, int size) {
        ScopeSql.Clause scope = ScopeSql.customerReads(actor);
        List<Object> args = new ArrayList<>(scope.args());
        args.addAll(search.args());
        String where = "c.deleted = 0 AND (" + scope.sql() + ")" + search.sql();
        Long total = jdbc.queryForObject("SELECT COUNT(c.id) FROM core_customer c WHERE " + where,
            Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Math.multiplyExact((long) page - 1, size));
        List<CustomerSummary> items = jdbc.query("""
            SELECT c.public_id,cp.public_id AS campus_public_id,p.public_id AS owner_public_id,
                   c.display_name_cipher,c.version
            FROM core_customer c
            LEFT JOIN core_principal p ON p.id = c.owner_id
            LEFT JOIN core_campus cp ON cp.id = c.campus_id
            WHERE
            """ + where + " ORDER BY c.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new CustomerSummary(rs.getString("public_id"),
                rs.getString("campus_public_id"), rs.getString("owner_public_id"),
                cipher.decrypt(rs.getString("display_name_cipher")), rs.getLong("version")),
            pageArgs.toArray());
        return new CustomerPage(items, total == null ? 0 : total, page, size);
    }
    /** Returns a customer only after current actor scope is applied in the database query.
     * @param actor current customer and optional student read scopes
     * @param customerPublicId requested household identifier
     * @param includeStudents whether to include separately scoped student links
     * @return decrypted household name, masked contacts and permitted student links
     * @throws BusinessException if the household is absent or unreadable
     */
    public Customer customer(Actor actor, String customerPublicId, boolean includeStudents) {
        ScopeSql.Clause scope = ScopeSql.customerReads(actor);
        List<Object> args = new ArrayList<>(List.of(customerPublicId));
        args.addAll(scope.args());
        List<Customer> customers = jdbc.query("""
            SELECT c.id,c.public_id,cp.public_id AS campus_public_id,p.public_id AS owner_public_id,
                   c.display_name_cipher,c.version
            FROM core_customer c LEFT JOIN core_principal p ON p.id = c.owner_id
            LEFT JOIN core_campus cp ON cp.id = c.campus_id
            WHERE c.public_id = ? AND c.deleted = 0 AND (
            """ + scope.sql() + ")", (rs, row) -> new Customer(rs.getString("public_id"),
                rs.getString("campus_public_id"), rs.getString("owner_public_id"),
                cipher.decrypt(rs.getString("display_name_cipher")), rs.getLong("version"),
                List.of(), List.of()), args.toArray());
        if (customers.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不在授权范围"); }
        Customer customer = customers.get(0);
        return new Customer(customer.id(), customer.campusId(), customer.ownerId(), customer.displayName(),
            customer.version(), contacts(customerPublicId),
            includeStudents ? students(actor, customerPublicId) : List.of());
    }
    /** Verifies student visibility and customer ownership before linking a student to a family.
     * @param actor current household write and student visibility scopes
     * @param customerPublicId household public identifier
     * @param studentPublicId existing student public identifier
     * @throws BusinessException if either record is absent or outside its required scope
     */
    public void authorizeStudentLink(Actor actor, String customerPublicId, String studentPublicId) {
        customerId(actor, customerPublicId, false);
        studentId(actor, studentPublicId, false);
    }
    /** Locks a household before replay or contact mutation so primary-contact checks serialize.
     * @param actor current household write scope
     * @param customerPublicId household public identifier to lock
     * @throws BusinessException if the household is absent or unwritable
     */
    public void authorizeContactAppend(Actor actor, String customerPublicId) {
        customerId(actor, customerPublicId, true);
    }
    /** Appends an encrypted unique contact and returns only its masked value.
     * @param actor current household write scope
     * @param customerPublicId household public identifier
     * @param input validated contact channel, value, role and primary flag
     * @return appended contact with its value masked
     * @throws BusinessException if the household is inaccessible or contact/primary constraints conflict
     */
    public CustomerContact addContact(Actor actor, String customerPublicId, CustomerContactInput input) {
        long id = customerId(actor, customerPublicId, true);
        String value = normalizeContact(input.channel(), input.value());
        ensureContactAvailable(id, input.channel(), value, input.primary());
        return insertContact(id, input);
    }
    /** Requires a selected existing household to contain the exact submitted guardian phone.
     * @param actor current household write scope
     * @param customerPublicId selected household public identifier
     * @param phone guardian phone for exact blind-index comparison
     * @throws BusinessException if the household is inaccessible or does not contain this phone
     */
    public void requirePhoneMatchesCustomer(Actor actor,String customerPublicId,String phone) {
        long customer=customerId(actor,customerPublicId,false);
        Map<String,byte[]> indexes=cipher.blindIndexes("phone",phone);
        String conditions=String.join(" OR ",indexes.keySet().stream().map(key->"(ci.key_id=? AND ci.value_hash=?)").toList());
        List<Object> args=new ArrayList<>(List.of(customer));
        indexes.forEach((key,hash)->{args.add(key);args.add(hash);});
        Integer matches=jdbc.queryForObject("""
            SELECT COUNT(cc.id) FROM core_customer_contact cc
            JOIN core_customer_contact_index ci ON ci.contact_id=cc.id
            WHERE cc.customer_id=? AND cc.channel='phone' AND cc.deleted=0 AND ci.deleted=0 AND (
            """+conditions+")",Integer.class,args.toArray());
        if(matches==null||matches==0)throw new BusinessException(ErrorCode.INVALID_INPUT,"所选客户档案未登记此监护人电话，请先核对并补充联系方式");
    }
    /** Detects possible duplicate family identities in the intake campus without returning their details.
     * @param campusPublicId campus already authorized by the calling intake service
     * @param phone phone compared across active blind-index key versions
     * @return whether a non-deleted household has this phone in the campus
     */
    public boolean phoneExistsInCampus(String campusPublicId,String phone) {
        Map<String,byte[]> indexes=cipher.blindIndexes("phone",phone);
        String terms=String.join(" OR ",indexes.keySet().stream().map(key->"(ci.key_id=? AND ci.value_hash=?)").toList());
        List<Object> args=new ArrayList<>(List.of(campusPublicId));
        indexes.forEach((key,hash)->{args.add(key);args.add(hash);});
        Integer matches=jdbc.queryForObject("""
            SELECT COUNT(DISTINCT c.id) FROM core_customer c JOIN core_customer_contact cc ON cc.customer_id=c.id
            JOIN core_customer_contact_index ci ON ci.contact_id=cc.id
            JOIN core_campus cp ON cp.id=c.campus_id
            WHERE cp.public_id=? AND cp.deleted=0 AND c.deleted=0 AND cc.deleted=0 AND cc.channel='phone' AND ci.deleted=0 AND (
            """+terms+")",Integer.class,args.toArray());
        return matches!=null&&matches>0;
    }
    /** Links an existing academic student; repeated identical relations are rejected.
     * @param actor current household write and student visibility scopes
     * @param customerPublicId household public identifier
     * @param input validated student identifier and relationship
     * @return new relationship with the student name and grade
     * @throws BusinessException if either record is inaccessible or the relationship already exists
     */
    public CustomerStudent linkStudent(Actor actor, String customerPublicId, CustomerStudentInput input) {
        long customerId = customerId(actor, customerPublicId, true);
        long studentId = studentId(actor, input.studentId(), true);
        String linkId = "CUSSTU_" + UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO core_customer_student (public_id,customer_id,student_id,relationship)
                VALUES (?,?,?,?)
                """, linkId, customerId, studentId, input.relationship());
        } catch (DuplicateKeyException conflict) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "该学生关系已关联到客户档案");
        }
        return studentLink(linkId);
    }
    /** Returns the campus public id for an already-authorized household intake.
     * @param actor current household write scope
     * @param customerPublicId selected household public identifier
     * @return active campus public identifier, or null when no active campus is linked
     * @throws BusinessException if the household is absent or outside write scope
     */
    public String customerCampusPublicId(Actor actor, String customerPublicId) {
        long id = customerId(actor, customerPublicId, false);
        List<String> campuses = jdbc.query("""
            SELECT cp.public_id FROM core_customer c JOIN core_campus cp ON cp.id=c.campus_id
            WHERE c.id=? AND cp.deleted=0
            """, (rs, row) -> rs.getString(1), id);
        return campuses.isEmpty() ? null : campuses.get(0);
    }
    /** Links a just-created, actor-owned child without requiring an active enrollment or global student scope.
     * @param actor creator of the new student and current household writer
     * @param customerPublicId selected household public identifier
     * @param studentPublicId new actor-owned student public identifier
     * @param relationship validated household relationship
     * @return newly inserted student relationship
     * @throws BusinessException if ownership/scope fails or the relationship already exists
     */
    public CustomerStudent linkOwnedStudent(Actor actor, String customerPublicId,
            String studentPublicId, String relationship) {
        long customerId = customerId(actor, customerPublicId, true);
        List<Long> students = jdbc.query("""
            SELECT s.id FROM core_student s
            WHERE s.public_id = ? AND s.owner_id = ? AND s.deleted = 0 FOR UPDATE
            """, (rs, row) -> rs.getLong("id"), studentPublicId, actor.id());
        if (students.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "新建学生不存在或不属于当前接待人");
        }
        String linkId = "CUSSTU_" + UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO core_customer_student (public_id,customer_id,student_id,relationship)
                VALUES (?,?,?,?)
                """, linkId, customerId, students.get(0), relationship);
        } catch (DuplicateKeyException conflict) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "该学生关系已关联到客户档案");
        }
        return studentLink(linkId);
    }
    private List<CustomerContact> contacts(String customerPublicId) {
        return jdbc.query("""
            SELECT cc.public_id,cc.person_role,cc.channel,cc.value_cipher,cc.is_primary
            FROM core_customer_contact cc JOIN core_customer c ON c.id = cc.customer_id
            WHERE c.public_id = ? AND c.deleted = 0 AND cc.deleted = 0 ORDER BY cc.id
            """, (rs, row) -> new CustomerContact(rs.getString("public_id"),
                rs.getString("person_role"), rs.getString("channel"),
                cipher.maskedContact(rs.getString("channel"), rs.getString("value_cipher")),
                rs.getBoolean("is_primary")), customerPublicId);
    }
    private List<CustomerStudent> students(Actor actor, String customerPublicId) {
        ScopeSql.Clause studentScope = ScopeSql.students(actor, null);
        List<Object> args = new ArrayList<>();
        args.add(customerPublicId);
        args.addAll(studentScope.args());
        return jdbc.query("""
            SELECT link.public_id,st.public_id AS student_public_id,st.name_cipher,st.grade,link.relationship
            FROM core_customer_student link JOIN core_customer c ON c.id = link.customer_id
            JOIN core_student st ON st.id = link.student_id
            WHERE c.public_id = ? AND c.deleted = 0 AND link.deleted = 0 AND st.deleted = 0
              AND EXISTS (SELECT s.id FROM core_student s WHERE s.id = st.id AND (
            """ + studentScope.sql() + ")) ORDER BY link.id",
            (rs, row) -> new CustomerStudent(rs.getString("public_id"),
                rs.getString("student_public_id"), cipher.decrypt(rs.getString("name_cipher")),
                rs.getString("grade"), rs.getString("relationship")), args.toArray());
    }
    private CustomerStudent studentLink(String linkPublicId) {
        List<CustomerStudent> links = jdbc.query("""
            SELECT link.public_id,st.public_id AS student_public_id,st.name_cipher,st.grade,link.relationship
            FROM core_customer_student link JOIN core_student st ON st.id = link.student_id
            WHERE link.public_id = ? AND link.deleted = 0 AND st.deleted = 0
            """, (rs, row) -> new CustomerStudent(rs.getString("public_id"),
                rs.getString("student_public_id"), cipher.decrypt(rs.getString("name_cipher")),
                rs.getString("grade"), rs.getString("relationship")), linkPublicId);
        if (links.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "学生关系创建失败"); }
        return links.get(0);
    }
    /** Creates an active campaign record and validates every participant against its campus.
     * @param actor creator and initial activity owner
     * @param input validated activity fields and participant/promoter identifiers
     * @return draft campaign or active non-campaign activity with its current members
     * @throws BusinessException if campus/member scope fails or member identifiers repeat
     */
    public Activity createActivity(Actor actor, ActivityInput input) {
        long campusId = campus(actor, input.campusId(), true);
        String publicId = "ACT_" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_activity
                (public_id,campus_id,owner_id,title,activity_type,status,starts_at,ends_at,description_cipher)
            VALUES (?,?,?,?,?,?,?, ?,?)
            """, publicId, campusId, actor.id(), input.title().trim(), input.activityType(), input.activityType().equals("campaign")?"draft":"active",
            Timestamp.from(input.startsAt()), Timestamp.from(input.endsAt()),
            input.description() == null || input.description().isBlank() ? null : cipher.encrypt(input.description().trim()));
        long activityId = activityId(publicId);
        memberships.insert(actor, campusId, activityId, safeIds(input.participantIds()), "participant");
        memberships.insert(actor, campusId, activityId, safeIds(input.promoterIds()), "promoter");
        return activity(actor, publicId);
    }
    /** Returns activities and member identities using the same scope for count and page.
     * @param actor current activity read scope
     * @param query validated view, filters and bounded pagination
     * @return matching activities with member identifiers and a count under the same predicate
     * @throws BusinessException if an explicitly selected campus is inaccessible
     */
    public ActivityPage activities(Actor actor,CrmActivityQuery query) {
        ScopeSql.Clause filter=activityFilter(actor,query);
        String where=filter.sql();List<Object> args=filter.args();
        int page=query.page(),size=query.size();
        Long total = jdbc.queryForObject("SELECT COUNT(a.id) FROM core_activity a WHERE " + where,
            Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Math.multiplyExact((long) page - 1, size));
        List<ActivityRow> rows = jdbc.query("""
            SELECT a.id,a.public_id,cp.public_id AS campus_public_id,p.public_id AS owner_public_id,
                   a.title,a.activity_type,a.status,a.starts_at,a.ends_at,a.description_cipher,a.version
            FROM core_activity a JOIN core_campus cp ON cp.id = a.campus_id
            JOIN core_principal p ON p.id = a.owner_id
            WHERE
            """ + where + " ORDER BY a.starts_at DESC,a.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> activityRow(rs), pageArgs.toArray());
        if (rows.isEmpty()) { return new ActivityPage(List.of(), total == null ? 0 : total, page, size); }
        Map<Long, MemberLists> members = memberships.read(rows.stream().map(ActivityRow::id).toList());
        List<Activity> items = rows.stream().map(activity -> activity(activity,
            members.getOrDefault(activity.id(), new MemberLists(List.of(), List.of())))).toList();
        return new ActivityPage(items, total == null ? 0 : total, page, size);
    }
    private ScopeSql.Clause activityFilter(Actor actor,CrmActivityQuery query) {
        ScopeSql.Clause scope=ScopeSql.activities(actor);
        List<Object> args=new ArrayList<>(scope.args());
        String where="a.deleted = 0 AND ("+scope.sql()+")";
        String viewFilter=switch(query.view()) {
            case "created" -> " AND a.owner_id = ?";
            case "participating" -> " AND EXISTS (SELECT am.id FROM core_activity_member am "
                +"WHERE am.activity_id = a.id AND am.principal_id = ? AND am.deleted = 0)";
            default -> "";
        };
        where+=viewFilter;if(!viewFilter.isEmpty())args.add(actor.id());
        if(query.campusId()!=null&&!query.campusId().isBlank()) {
            where+=" AND a.campus_id = ?";args.add(campus(actor,query.campusId()));
        }
        where+=activityTextFilter(" AND a.activity_type = ?",query.activityType(),args);
        where+=activityTextFilter(" AND a.status = ?",query.status(),args);
        where+=activityTextFilter(" AND LOCATE(LOWER(?),LOWER(a.title)) > 0",query.query(),args);
        return new ScopeSql.Clause(where,List.copyOf(args));
    }
    private String activityTextFilter(String predicate,String value,List<Object> args) {
        if(value==null||value.isBlank())return "";
        args.add(value.trim());return predicate;
    }
    /** Checks activity scope before retry replay or touchpoint linkage.
     * @param actor current activity scope
     * @param activityPublicId activity public identifier
     * @throws BusinessException if the activity is absent or outside scope
     */
    public void authorizeActivity(Actor actor, String activityPublicId) {
        activityId(actor, activityPublicId, false);
    }
    /** Rechecks the exact legacy campaign type and owner/manager write boundary before replay. */
    void authorizeLegacyCampaign(Actor actor,String publicId) {
        if(actor.permissions().contains("crm.activity.write"))return;
        ScopeSql.Clause scope=ScopeSql.campaignWrites(actor);
        List<Object> args=new ArrayList<>(List.of(publicId));args.addAll(scope.args());
        Integer count=jdbc.queryForObject("SELECT COUNT(a.id) FROM core_activity a WHERE a.public_id=? "
            +"AND a.activity_type='campaign' AND a.deleted=0 AND ("+scope.sql()+")",Integer.class,args.toArray());
        if(count==null||count!=1)throw new BusinessException(ErrorCode.NOT_FOUND,"营销活动不存在或不在授权范围");
    }
    /** Replaces active memberships atomically while retaining soft-deleted membership rows.
     * @param actor current activity scope and member-management campus scope
     * @param activityPublicId active activity public identifier
     * @param input validated replacement lists and expected version
     * @return activity with its incremented version and replacement members
     * @throws BusinessException if scope, member eligibility, activity state or version check fails
     */
    public Activity replaceActivityMembers(Actor actor, String activityPublicId, ActivityMembersInput input) {
        long id = activityId(actor, activityPublicId, true);
        int changed = jdbc.update("""
            UPDATE core_activity SET version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND status='active' AND deleted=0
            """, id, input.expectedVersion());
        if (changed != 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "活动版本已变化或活动已结束"); }
        long campusId = jdbc.queryForObject("SELECT campus_id FROM core_activity WHERE id=?", Long.class, id);
        memberships.replace(actor, campusId, id, input.participantIds(), "participant");
        memberships.replace(actor, campusId, id, input.promoterIds(), "promoter");
        return activity(actor, activityPublicId);
    }
    /** Closes an activity with an optimistic version check and an actor-scope lock.
     * @param actor current activity scope
     * @param activityPublicId active activity public identifier
     * @param expectedVersion version observed by the caller
     * @return closed activity with its incremented version
     * @throws BusinessException if activity scope, active state or version check fails
     */
    public Activity closeActivity(Actor actor, String activityPublicId, long expectedVersion) {
        long id = activityId(actor, activityPublicId, true);
        int updated = jdbc.update("""
            UPDATE core_activity SET status = 'closed',version = version + 1,updated_at = UTC_TIMESTAMP(3)
            WHERE id = ? AND version = ? AND status = 'active' AND deleted = 0
            """, id, expectedVersion);
        if (updated != 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "活动版本已变化或已关闭"); }
        return activity(actor, activityPublicId);
    }
    /** Rechecks customer, activity and student scope before replaying a touchpoint request.
     * @param actor current household write, activity and student scopes
     * @param customerPublicId household receiving the touchpoint
     * @param input validated optional activity/student references
     * @throws BusinessException if scope, shared campus or existing student relationship checks fail
     */
    public void authorizeTouchpoint(Actor actor, String customerPublicId, TouchpointInput input) {
        long customerId = customerId(actor, customerPublicId, false);
        if (input.activityId() != null && !input.activityId().isBlank()) {
            long activityId = activityId(actor, input.activityId(), false);
            Long customerCampus = jdbc.queryForObject("SELECT campus_id FROM core_customer WHERE id = ?",
                Long.class, customerId);
            ActivityCampus activity = activityCampus(activityId);
            if (customerCampus == null || activity.campusId() != customerCampus) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "客户与活动必须属于同一校区");
            }
        }
        if (input.studentId() != null && !input.studentId().isBlank()) {
            long studentId = studentId(actor, input.studentId(), false);
            Integer linked = jdbc.queryForObject("""
                SELECT COUNT(link.id) FROM core_customer_student link
                WHERE link.customer_id = ? AND link.student_id = ? AND link.deleted = 0
                """, Integer.class, customerId, studentId);
            if (linked == null || linked == 0) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "学生尚未关联到该客户档案");
            }
        }
    }
    /** Appends a single immutable, encrypted customer touchpoint without invoking a provider.
     * @param actor current writer and touchpoint author
     * @param customerPublicId household public identifier to lock
     * @param input validated summary, source, channel, time and optional references
     * @return inserted touchpoint with occurrence time truncated to milliseconds
     * @throws BusinessException if scope, shared campus or student relationship checks fail
     */
    public Touchpoint appendTouchpoint(Actor actor, String customerPublicId, TouchpointInput input) {
        long customerId = customerId(actor, customerPublicId, true);
        Long customerCampus = jdbc.queryForObject("SELECT campus_id FROM core_customer WHERE id = ?",
            Long.class, customerId);
        Long activityId = null;
        String activityPublicId = null;
        if (input.activityId() != null && !input.activityId().isBlank()) {
            activityId = activityId(actor, input.activityId(), true);
            ActivityCampus activity = activityCampus(activityId);
            if (customerCampus == null || activity.campusId() != customerCampus) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "客户与活动必须属于同一校区");
            }
            activityPublicId = input.activityId();
        }
        Long studentId = null;
        String studentPublicId = null;
        if (input.studentId() != null && !input.studentId().isBlank()) {
            studentId = studentId(actor, input.studentId(), true);
            Integer linked = jdbc.queryForObject("""
                SELECT COUNT(link.id) FROM core_customer_student link
                WHERE link.customer_id = ? AND link.student_id = ? AND link.deleted = 0
                """, Integer.class, customerId, studentId);
            if (linked == null || linked == 0) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "学生尚未关联到该客户档案");
            }
            studentPublicId = input.studentId();
        }
        String publicId = "TOUCH_" + UUID.randomUUID();
        Instant occurredAt = input.occurredAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        jdbc.update("""
            INSERT INTO core_customer_touchpoint
                (public_id,customer_id,activity_id,student_id,actor_id,source,channel,summary_cipher,occurred_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """, publicId, customerId, activityId, studentId, actor.id(), input.source(), input.channel(),
            cipher.encrypt(input.summary().trim()), Timestamp.from(occurredAt));
        return new Touchpoint(publicId, customerPublicId, activityPublicId, studentPublicId,
            actor.publicId(), input.source(), input.channel(), input.summary().trim(), occurredAt);
    }
    /** Returns recent customer history; related records are included only in current authorized scope.
     * @param actor current household read scope and related-resource permissions
     * @param customerPublicId household public identifier
     * @return at most 100 recent touchpoints with inaccessible related identifiers omitted
     * @throws BusinessException if the household is absent or unreadable
     */
    public List<Touchpoint> touchpoints(Actor actor, String customerPublicId) {
        long customerId = readableCustomerId(actor, customerPublicId);
        boolean showStudents = actor.permissions().contains("student.read");
        boolean showActivities = CrmLegacyAccess.canRead(actor, "crm.activity.read");
        ScopeSql.Clause studentScope = showStudents ? ScopeSql.students(actor, null)
            : new ScopeSql.Clause("1 = 0", List.of());
        ScopeSql.Clause activityScope = showActivities ? ScopeSql.activities(actor)
            : new ScopeSql.Clause("1 = 0", List.of());
        List<Object> args = new ArrayList<>(activityScope.args());
        args.addAll(studentScope.args());
        args.add(customerId);
        String sql = "SELECT t.public_id,t.activity_id,"
            + "(SELECT a.public_id FROM core_activity a WHERE a.id=t.activity_id AND a.deleted=0 AND ("
            + activityScope.sql() + ")) AS activity_public_id,"
            + "(SELECT s.public_id FROM core_student s WHERE s.id=t.student_id AND ("
            + studentScope.sql() + ")) AS student_public_id,"
            + "p.public_id AS actor_public_id,t.source,t.channel,t.summary_cipher,t.occurred_at "
            + "FROM core_customer_touchpoint t JOIN core_principal p ON p.id=t.actor_id "
            + "WHERE t.customer_id=? AND t.deleted=0 ORDER BY t.occurred_at DESC,t.id DESC LIMIT 100";
        return jdbc.query(sql, (rs, row) -> new Touchpoint(rs.getString("public_id"), customerPublicId,
            rs.getString("activity_public_id"), rs.getString("student_public_id"), rs.getString("actor_public_id"),
            rs.getString("source"), rs.getString("channel"), cipher.decrypt(rs.getString("summary_cipher")),
            rs.getTimestamp("occurred_at").toInstant()), args.toArray());
    }
    private Activity activity(Actor actor, String publicId) {
        long id = activityId(actor, publicId, false);
        List<ActivityRow> rows = jdbc.query("""
            SELECT a.id,a.public_id,cp.public_id AS campus_public_id,p.public_id AS owner_public_id,
                   a.title,a.activity_type,a.status,a.starts_at,a.ends_at,a.description_cipher,a.version
            FROM core_activity a JOIN core_campus cp ON cp.id = a.campus_id
            JOIN core_principal p ON p.id = a.owner_id WHERE a.id = ? AND a.deleted = 0
            """, (rs, row) -> activityRow(rs), id);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "活动不存在或已删除"); }
        return activity(rows.get(0), memberships.read(List.of(id)).getOrDefault(id,
            new MemberLists(List.of(), List.of())));
    }
    private long activityId(String publicId) {
        Long id = jdbc.queryForObject("SELECT id FROM core_activity WHERE public_id = ? AND deleted = 0",
            Long.class, publicId);
        if (id == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "活动不存在或已删除"); }
        return id;
    }
    private long activityId(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = ScopeSql.activities(actor);
        List<Object> args = new ArrayList<>(List.of(publicId));
        args.addAll(scope.args());
        List<Long> ids = jdbc.query("""
            SELECT a.id FROM core_activity a WHERE a.public_id = ? AND a.deleted = 0 AND (
            """ + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> rs.getLong("id"), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "活动不存在或不在授权范围"); }
        return ids.get(0);
    }
    private ActivityCampus activityCampus(long activityId) {
        Long campusId = jdbc.queryForObject("SELECT campus_id FROM core_activity WHERE id = ? AND deleted = 0",
            Long.class, activityId);
        return new ActivityCampus(campusId);
    }
    private List<String> safeIds(List<String> ids) { return ids == null ? List.of() : ids; }
    private ActivityRow activityRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ActivityRow(rs.getLong("id"), rs.getString("public_id"), rs.getString("campus_public_id"),
            rs.getString("owner_public_id"), rs.getString("title"), rs.getString("activity_type"),
            rs.getString("status"), instant(rs.getTimestamp("starts_at")),
            instant(rs.getTimestamp("ends_at")), rs.getString("description_cipher"), rs.getLong("version"));
    }
    private Instant instant(java.sql.Timestamp value) { return value == null ? null : value.toInstant(); }
    private Activity activity(ActivityRow row, MemberLists members) {
        return new Activity(row.publicId(), row.campusId(), row.ownerId(), row.title(), row.activityType(),
            row.status(), row.startsAt(), row.endsAt(), row.descriptionCipher() == null ? null
                : cipher.decrypt(row.descriptionCipher()), row.version(), members.participants(), members.promoters());
    }
    /** Campus identity used to reject cross-campus touchpoint linkage. */
    private record ActivityCampus(long campusId) { }
    private CustomerContact insertContact(long customerId, CustomerContactInput input) {
        String value = normalizeContact(input.channel(), input.value());
        String contactPublicId = "CONTACT_" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_customer_contact
                (public_id,customer_id,person_role,channel,value_cipher,is_primary)
            VALUES (?,?,?,?,?,?)
            """, contactPublicId, customerId, input.personRole(), input.channel(),
            cipher.encrypt(value), input.primary());
        long contactId = jdbc.queryForObject("SELECT id FROM core_customer_contact WHERE public_id = ?",
            Long.class, contactPublicId);
        insertIndexes("core_customer_contact_index", "contact_id", contactId,
            input.channel(), value);
        return new CustomerContact(contactPublicId, input.personRole(), input.channel(),
            cipher.maskedContact(input.channel(), cipher.encrypt(value)), input.primary());
    }
    private void ensureContactAvailable(long customerId, String channel, String value, boolean primary) {
        Map<String, byte[]> indexes = cipher.blindIndexes(channel, value);
        String terms = String.join(" OR ", indexes.keySet().stream()
            .map(key -> "(ci.key_id=? AND ci.value_hash=?)").toList());
        List<Object> args = new ArrayList<>(List.of(customerId, channel));
        indexes.forEach((key, hash) -> { args.add(key); args.add(hash); });
        Integer duplicate = jdbc.queryForObject("""
            SELECT COUNT(cc.id) FROM core_customer_contact cc
            JOIN core_customer_contact_index ci ON ci.contact_id=cc.id
            WHERE cc.customer_id=? AND cc.channel=? AND cc.deleted=0 AND ci.deleted=0 AND (
            """ + terms + ")", Integer.class, args.toArray());
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "该客户档案已登记此联系方式");
        }
        if (primary) { ensurePrimaryContactAvailable(customerId, channel); }
    }
    private void ensurePrimaryContactAvailable(long customerId, String channel) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(id) FROM core_customer_contact
            WHERE customer_id=? AND channel=? AND is_primary=1 AND deleted=0
            """, Integer.class, customerId, channel);
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "该客户档案此类联系方式已有主要联系方式");
        }
    }
    private void insertIndexes(String table, String ownerColumn, long ownerId,
            String purpose, String value) {
        Map<String, byte[]> indexes = cipher.blindIndexes(purpose, value);
        Map<String, String> searchIndexes = table.equals("core_customer_name_index")
                || table.equals("core_customer_contact_index")
            ? SavedViewSearchNgrams.encode(cipher, value) : Map.of();
        for (Map.Entry<String, byte[]> entry : indexes.entrySet()) {
            if (table.equals("core_customer_name_index")) {
                String ngrams = searchIndexes.get(entry.getKey());
                jdbc.update("INSERT INTO " + table + " (customer_id,key_id,value_hash,search_ngrams) VALUES (?,?,?,?)",
                    ownerId, entry.getKey(), entry.getValue(), ngrams);
            } else if (table.equals("core_customer_contact_index")) {
                String ngrams = searchIndexes.get(entry.getKey());
                jdbc.update("INSERT INTO " + table + " (contact_id,key_id,value_hash,value_search_ngrams) VALUES (?,?,?,?)",
                    ownerId, entry.getKey(), entry.getValue(), ngrams);
            } else {
                jdbc.update("INSERT INTO " + table + " (" + ownerColumn + ",key_id,value_hash) VALUES (?,?,?)",
                    ownerId, entry.getKey(), entry.getValue());
            }
        }
    }
    private SearchFilter customerSearch(Actor actor, String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) { return new SearchFilter("", List.of()); }
        String value = rawQuery.trim();
        Map<String, byte[]> nameIndexes = cipher.blindIndexes("customer_name", normalizeName(value));
        String contactChannel = value.contains("@") ? "email" : "phone";
        String contactValue = normalizeContact(contactChannel, value);
        Map<String, byte[]> contactIndexes = cipher.blindIndexes(contactChannel, contactValue);
        String nameTerms = indexTerms("ni", nameIndexes);
        String contactTerms = indexTerms("ci", contactIndexes);
        String sql = " AND (c.public_id = ? OR c.id IN (SELECT ni.customer_id FROM core_customer_name_index ni WHERE ni.deleted = 0 AND ("
            + nameTerms + ")) OR c.id IN (SELECT cc.customer_id FROM core_customer_contact cc "
            + "JOIN core_customer_contact_index ci ON ci.contact_id = cc.id AND ci.deleted = 0 "
            + "WHERE cc.deleted = 0 AND cc.channel = ? AND (" + contactTerms + "))";
        List<Object> args = new ArrayList<>();
        args.add(value);
        addIndexArgs(args, nameIndexes);
        args.add(contactChannel);
        addIndexArgs(args, contactIndexes);
        if (actor.permissions().contains("student.read")) {
            ScopeSql.Clause studentScope = ScopeSql.students(actor, null);
            sql += " OR c.id IN (SELECT link.customer_id FROM core_customer_student link "
                + "JOIN core_student s ON s.id = link.student_id WHERE link.deleted = 0 "
                + "AND s.public_id = ? AND (" + studentScope.sql() + "))";
            args.add(value);
            args.addAll(studentScope.args());
        }
        sql += ")";
        return new SearchFilter(sql, List.copyOf(args));
    }
    private String indexTerms(String alias, Map<String, byte[]> indexes) {
        return String.join(" OR ", indexes.keySet().stream()
            .map(key -> "(" + alias + ".key_id = ? AND " + alias + ".value_hash = ?)").toList());
    }
    private void addIndexArgs(List<Object> args, Map<String, byte[]> indexes) {
        indexes.forEach((key, hash) -> { args.add(key); args.add(hash); });
    }
    private long customerId(String publicId) {
        Long id = jdbc.queryForObject("SELECT id FROM core_customer WHERE public_id = ? AND deleted = 0",
            Long.class, publicId);
        if (id == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或已删除"); }
        return id;
    }
    private long customerId(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = ScopeSql.customers(actor);
        List<Object> args = new ArrayList<>(List.of(publicId));
        args.addAll(scope.args());
        List<Long> ids = jdbc.query("""
            SELECT c.id FROM core_customer c WHERE c.public_id = ? AND c.deleted = 0 AND (
            """ + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> rs.getLong("id"), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不在授权范围"); }
        return ids.get(0);
    }
    private long readableCustomerId(Actor actor, String publicId) {
        ScopeSql.Clause scope = ScopeSql.customerReads(actor);
        List<Object> args = new ArrayList<>(List.of(publicId));
        args.addAll(scope.args());
        List<Long> ids = jdbc.query("SELECT c.id FROM core_customer c WHERE c.public_id=? AND c.deleted=0 AND ("
            + scope.sql() + ")", (rs, row) -> rs.getLong("id"), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不在授权范围"); }
        return ids.get(0);
    }
    private long studentId(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = ScopeSql.students(actor, null);
        List<Object> args = new ArrayList<>(List.of(publicId));
        args.addAll(scope.args());
        List<Long> ids = jdbc.query("""
            SELECT s.id FROM core_student s WHERE s.public_id = ? AND (
            """ + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> rs.getLong("id"), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "学生不存在或不在授权范围"); }
        return ids.get(0);
    }
    private long campus(Actor actor, String publicId) {
        return campus(actor, publicId, false);
    }
    private long campus(Actor actor, String publicId, boolean lock) {
        List<Long> ids = jdbc.query("""
            SELECT id FROM core_campus WHERE public_id = ? AND active = 1 AND deleted = 0
            """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> rs.getLong("id"), publicId);
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "校区不存在或已停用"); }
        long id = ids.get(0);
        if (!actor.scope().equals("all") && !actor.campusIds().contains(id)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号无权在该校区管理客户");
        }
        return id;
    }
    private List<CustomerContactInput> safeContacts(List<CustomerContactInput> contacts) {
        return contacts == null ? List.of() : contacts;
    }
    private String normalizeName(String value) { return value.trim().replaceAll("\\s+", " "); }
    private String normalizeContact(String channel, String value) {
        String normalized = value.trim();
        if (channel.equals("phone")) { return normalized.replaceAll("[\\s()\\-]", ""); }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }
    /** Parameterized customer search predicate appended to the independent read scope. */
    private record SearchFilter(String sql, List<Object> args) { }
}
