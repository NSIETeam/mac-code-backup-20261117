package cn.siyue.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import cn.siyue.core.AcademicDtos.Student;
import cn.siyue.core.AcademicDtos.StudentInput;

/** Persists encrypted student identity and its explicit legacy CRM links. */
@Repository
public class StudentProfileRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final StudentIdentityMutationLock identityLocks;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Uses the application JDBC transaction and PII keyring. */
    public StudentProfileRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.identityLocks = new StudentIdentityMutationLock(jdbc, cipher);
    }

    /** Reads only the append-only audit timeline for a profile visible to the current actor. */
    public AcademicDtos.StudentHistoryPage history(Actor actor, String publicId, int page, int size) {
        ScopeSql.Clause scope = ScopeSql.studentReads(actor);
        List<Object> scopeArgs = new ArrayList<>();
        scopeArgs.add(publicId);
        scopeArgs.addAll(scope.args());
        Long visible = jdbc.queryForObject("SELECT COUNT(s.id) FROM core_student s WHERE s.public_id=? "
            + "AND s.deleted=0 AND (" + scope.sql() + ")", Long.class, scopeArgs.toArray());
        if (visible == null || visible == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "学生档案不存在或不在当前范围");
        }
        List<Object> historyArgs = new ArrayList<>(scopeArgs);
        Long total = jdbc.queryForObject("SELECT COUNT(a.id) FROM core_audit a JOIN core_student s "
            + "ON s.public_id=a.resource_public_id WHERE s.public_id=? AND s.deleted=0 AND (" + scope.sql() + ")",
            Long.class, scopeArgs.toArray());
        historyArgs.add(size);
        historyArgs.add(Math.multiplyExact((long) page - 1, size));
        List<AcademicDtos.StudentHistoryEvent> events = jdbc.query("SELECT a.action,a.created_at,"
            + "p.public_id actor_public_id FROM core_audit a JOIN core_student s "
            + "ON s.public_id=a.resource_public_id JOIN core_principal p ON p.id=a.actor_id "
            + "WHERE s.public_id=? AND s.deleted=0 AND (" + scope.sql() + ") "
            + "ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?", (rs, row) ->
                new AcademicDtos.StudentHistoryEvent(rs.getString("action"), rs.getTimestamp("created_at").toInstant(),
                    rs.getString("actor_public_id")), historyArgs.toArray());
        return new AcademicDtos.StudentHistoryPage(events, total == null ? 0 : total, page, size);
    }

    /** Acquires the identity mutex before any actor or source-row FOR UPDATE lock is taken. */
    public void lockCreateIdentity(StudentInput input) {
        validateNewStudent(input);
        identityLocks.lock(input.name().trim(), input.phone().trim());
    }

    /** Resolves the proposed update identity and locks it before actor-row serialization. */
    public void lockUpdateIdentity(Actor actor, String publicId, AcademicDtos.StudentUpdateInput input) {
        lockTargetIdentity(actor, publicId, input);
    }

    /** Creates one owned student profile while encrypting name and phone before persistence. */
    public Student createStudent(Actor actor, StudentInput input) {
        validateNewStudent(input);
        identityLocks.lock(input.name().trim(), input.phone().trim());
        StudentDraft draft = prepareStudentDraft(actor, input);
        String publicId = "STU_" + UUID.randomUUID();
        assertNoDuplicateStudent(null, draft.name(), draft.phone());
        assertNoDuplicateLeadIdentity(draft.lead() == null ? 0 : draft.lead().id(), draft.name(), draft.phone());
        Long customerId = draft.lead() == null ? null : draft.lead().customerId();
        Long ownerId = ownerId(actor, draft.lead());
        Long assigneeId = draft.lead() == null ? null : draft.lead().assigneeId();
        jdbc.update("""
            INSERT INTO core_student (public_id,name_cipher,grade,phone_cipher,campus_id,origin_lead_id,
                customer_id,owner_id,assignee_id)
            VALUES (?,?,?,?,?,?,?,?,?)
            """, publicId, cipher.encrypt(draft.name()), draft.grade(), cipher.encrypt(draft.phone()), draft.campusId(),
            draft.lead() == null ? null : draft.lead().id(), customerId, ownerId, assigneeId);
        replaceStudentIdentity(publicId, draft.name(), draft.phone());
        new StudentNameIndexRepository(jdbc, cipher).replace(publicId, draft.name());
        linkStudentCustomer(customerId, publicId);
        linkStudentLead(actor, publicId, draft, assigneeId);
        StudentLinks links = new StudentLinks(draft.campusId(),
            draft.lead() == null ? null : input.originLeadId(), customerId, ownerId, assigneeId);
        return studentReceipt(publicId, new StudentReceiptData(draft.name(), draft.grade(), draft.phone(), 1, links));
    }

    /** 创建学员时经验证的姓名、年级、电话、校区和可选来源线索引用。 */
    private record StudentDraft(String name, String grade, String phone, Long campusId, LeadProfile lead) { }
    /** 学员档案修改中准备应用的规范姓名、年级、电话及校区。 */
    private record StudentChange(String name, String grade, String phone, Long campusId) { }
    /** 学员回执所需的校区、线索、客户及负责/分配员工关联，独立于个人资料字段。 */
    private record StudentLinks(Long campusId, String leadPublicId, Long customerId, Long ownerId, Long assigneeId) { }
    /** 生成学员提交回执使用的解密资料、版本及关联引用。 */
    private record StudentReceiptData(String name, String grade, String phone, long version, StudentLinks links) { }

    private StudentDraft prepareStudentDraft(Actor actor, StudentInput input) {
        validateNewStudent(input);
        String name = input.name().trim(), phone = input.phone().trim();
        Long campusId = input.campusId() == null || input.campusId().isBlank()
            ? null : campusId(actor, input.campusId());
        LeadProfile lead = optionalLead(actor, input.originLeadId());
        validateLeadForStudent(lead, name, phone, campusId);
        Long resolvedCampus = lead != null && lead.campusId() != null ? lead.campusId() : campusId;
        return new StudentDraft(name, normalizedStudentGrade(input.grade(), null), phone, resolvedCampus, lead);
    }

    private void validateNewStudent(StudentInput input) {
        if (input == null || input.name() == null || input.name().isBlank() || input.name().trim().length() > 80
                || input.phone() == null || !input.phone().trim().matches("1[3-9][0-9]{9}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "学生姓名或手机号无效");
        }
    }

    private LeadProfile optionalLead(Actor actor, String leadPublicId) {
        return leadPublicId == null || leadPublicId.isBlank() ? null : lockLead(actor, leadPublicId);
    }

    private void validateLeadForStudent(LeadProfile lead, String name, String phone, Long campusId) {
        if (lead == null) { return; }
        Integer existingSource = jdbc.queryForObject("SELECT COUNT(id) FROM core_student "
            + "WHERE origin_lead_id=? AND deleted=0", Integer.class, lead.id());
        if (lead.studentId() != null || existingSource != null && existingSource > 0) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "原线索已关联其他学生");
        }
        if (!leadIdentityMatches(lead, name, phone)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "学生姓名手机号与原线索不一致");
        }
        if (campusId != null && lead.campusId() != null && !campusId.equals(lead.campusId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "学生校区与原线索不一致");
        }
    }

    private Long ownerId(Actor actor, LeadProfile lead) {
        return lead == null || lead.ownerId() == null ? actor.id() : lead.ownerId();
    }

    private void linkStudentCustomer(Long customerId, String studentPublicId) {
        if (customerId == null) { return; }
        jdbc.update("""
            INSERT INTO core_customer_student(public_id,customer_id,student_id,relationship)
            SELECT ?,?,id,'unspecified' FROM core_student WHERE public_id=?
            """, "CUSSTU_" + UUID.randomUUID(), customerId, studentPublicId);
    }

    private void linkStudentLead(Actor actor, String studentPublicId, StudentDraft draft, Long assigneeId) {
        LeadProfile lead = draft.lead();
        if (lead == null) { return; }
        int linked = jdbc.update("UPDATE core_crm_lead SET student_id=(SELECT id FROM core_student WHERE public_id=?), "
            + "name_cipher=?,phone_cipher=?,contact_phone_cipher=?,assignee_id=?,version=version+1 "
            + "WHERE id=? AND student_id IS NULL AND deleted=0", studentPublicId, cipher.encrypt(draft.name()),
            cipher.encrypt(draft.phone()), cipher.encrypt(draft.phone()), assigneeId, lead.id());
        if (linked != 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "原线索已关联其他学生"); }
        appendLeadProfileEvent(actor, lead.id(), "student_linked", studentPublicId);
    }

    private Student studentReceipt(String studentPublicId, StudentReceiptData data) {
        StudentLinks links = data.links();
        return new Student(studentPublicId, data.name(), data.grade(), cipher.maskedPhone(cipher.encrypt(data.phone())),
            data.version(), campusPublicId(links.campusId()), links.leadPublicId(),
            linkedPublicId("core_customer", links.customerId()), linkedPublicId("core_principal", links.ownerId()),
            linkedPublicId("core_principal", links.assigneeId()));
    }

    private void lockTargetIdentity(Actor actor, String publicId, AcademicDtos.StudentUpdateInput input) {
        ScopeSql.Clause scope = ScopeSql.studentWrite(actor);
        List<StudentIdentitySnapshot> rows = jdbc.query("""
            SELECT s.name_cipher,s.phone_cipher FROM core_student s
            WHERE s.public_id=? AND s.deleted=0 AND """ + " " + scope.sql(),
            (rs, row) -> new StudentIdentitySnapshot(rs.getString("name_cipher"), rs.getString("phone_cipher")),
            concat(List.of(publicId), scope.args()).toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "学生不存在或不在当前授权范围"); }
        StudentIdentitySnapshot old = rows.get(0);
        String name = input.name() == null ? cipher.decrypt(old.nameCipher()) : input.name().trim();
        String phone = input.phone() == null
            ? old.phoneCipher() == null ? null : cipher.decrypt(old.phoneCipher()) : input.phone().trim();
        identityLocks.lock(name, phone);
    }

    private String linkedPublicId(String table, Long id) {
        return id == null ? null : publicId(table, id);
    }

    /** Locks and updates one student only when the caller may write its legacy owner/campus/roster scope. */
    public Student updateStudent(Actor actor, String publicId, AcademicDtos.StudentUpdateInput input) {
        lockTargetIdentity(actor, publicId, input);
        StudentProfile old = lockStudent(actor, publicId);
        if (old.version() != input.expectedVersion()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "学生档案已更新，请刷新后重试");
        }
        StudentChange change = prepareStudentChange(actor, old, input);
        LeadProfile lead = old.originLeadId() == null ? null : lockLead(actor,
            publicId("core_crm_lead", old.originLeadId()));
        if (lead != null) { synchronizeLeadProfile(actor, lead, old, change); }
        updateStudentRow(old, change);
        replaceStudentIdentity(publicId, change.name(), change.phone());
        new StudentNameIndexRepository(jdbc, cipher).replace(publicId, change.name());
        StudentLinks links = new StudentLinks(change.campusId(), linkedPublicId("core_crm_lead", old.originLeadId()),
            old.customerId(), old.ownerId(), old.assigneeId());
        return studentReceipt(publicId, new StudentReceiptData(change.name(), change.grade(), change.phone(),
            old.version() + 1, links));
    }

    private StudentProfile lockStudent(Actor actor, String publicId) {
        ScopeSql.Clause scope = ScopeSql.studentWrite(actor);
        List<StudentProfile> rows = jdbc.query("""
            SELECT s.id,s.public_id,s.name_cipher,s.grade,s.phone_cipher,s.campus_id,s.origin_lead_id,
                s.customer_id,s.owner_id,s.assignee_id,s.version
            FROM core_student s WHERE s.public_id=? AND s.deleted=0 AND """ + " " + scope.sql() + " FOR UPDATE",
            (rs, row) -> new StudentProfile(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("name_cipher"), rs.getString("grade"), rs.getString("phone_cipher"),
                nullableLong(rs, "campus_id"), nullableLong(rs, "origin_lead_id"),
                nullableLong(rs, "customer_id"), nullableLong(rs, "owner_id"),
                nullableLong(rs, "assignee_id"), rs.getLong("version")),
            concat(List.of(publicId), scope.args()).toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "学生不存在或不在当前授权范围"); }
        return rows.get(0);
    }

    private StudentChange prepareStudentChange(Actor actor, StudentProfile old,
            AcademicDtos.StudentUpdateInput input) {
        String name = input.name() == null ? cipher.decrypt(old.nameCipher()) : input.name().trim();
        String phone = input.phone() == null ? old.phoneCipher() == null ? null : cipher.decrypt(old.phoneCipher())
            : input.phone().trim();
        if (name.isBlank() || name.length() > 80 || phone == null || !phone.matches("1[3-9][0-9]{9}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "学生姓名或手机号无效");
        }
        String grade = normalizedStudentGrade(input.grade(), old.grade());
        Long campusId = resolveUpdatedCampus(actor, old, input.campusId());
        validateOriginLeadUnchanged(old, input.originLeadId());
        assertNoDuplicateStudent(old.id(), name, phone);
        return new StudentChange(name, grade, phone, campusId);
    }

    private Long resolveUpdatedCampus(Actor actor, StudentProfile old, String campusPublicId) {
        return campusPublicId == null || campusPublicId.isBlank() ? old.campusId() : campusId(actor, campusPublicId);
    }

    private void validateOriginLeadUnchanged(StudentProfile old, String requestedLeadId) {
        if (requestedLeadId == null || requestedLeadId.isBlank()) { return; }
        String currentId = old.originLeadId() == null ? null : publicId("core_crm_lead", old.originLeadId());
        if (!requestedLeadId.equals(currentId)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "原始来源不能改写");
        }
    }

    private void synchronizeLeadProfile(Actor actor, LeadProfile lead, StudentProfile old, StudentChange change) {
        assertNoDuplicateLeadIdentity(lead.id(), change.name(), change.phone());
        int updated = jdbc.update("UPDATE core_crm_lead SET name_cipher=?,phone_cipher=?,contact_phone_cipher=?,version=version+1 "
            + "WHERE id=? AND student_id=? AND deleted=0", cipher.encrypt(change.name()), cipher.encrypt(change.phone()),
            cipher.encrypt(change.phone()), lead.id(), old.id());
        if (updated != 1) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "来源线索已变化，请刷新后重试"); }
        appendLeadProfileEvent(actor, lead.id(), "student_profile_sync", old.publicId());
    }

    private void updateStudentRow(StudentProfile old, StudentChange change) {
        int updated = jdbc.update("""
            UPDATE core_student SET name_cipher=?,grade=?,phone_cipher=?,campus_id=?,version=version+1,
                updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND deleted=0
            """, cipher.encrypt(change.name()), change.grade(), cipher.encrypt(change.phone()), change.campusId(),
            old.id(), old.version());
        if (updated != 1) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "学生档案已更新，请刷新后重试"); }
    }
    /** Validates current row scope before a retry can reveal a stored response. */
    public void authorizeStudentUpdate(Actor actor, String publicId) {
        ScopeSql.Clause scope = ScopeSql.studentWrite(actor);
        Integer found = jdbc.queryForObject("SELECT COUNT(s.id) FROM core_student s WHERE s.public_id=? "
            + "AND s.deleted=0 AND " + scope.sql(), Integer.class,
            concat(List.of(publicId), scope.args()).toArray());
        if (found == null || found == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "学生不存在或不在当前授权范围");
        }
    }

    /** 数据库学员档案的内部行投影，姓名与电话保持密文直到授权读取。 */
    private record StudentProfile(long id, String publicId, String nameCipher, String grade, String phoneCipher,
            Long campusId, Long originLeadId, Long customerId, Long ownerId, Long assigneeId, long version) { }
    /** 关联身份变更前保存的姓名和电话密文，用于维护查重索引与来源一致性。 */
    private record StudentIdentitySnapshot(String nameCipher, String phoneCipher) { }
    /** 创建或更新学员时核对的来源线索，含客户/校区/员工关联及联系资料密文。 */
    private record LeadProfile(long id, Long customerId, Long campusId, Long ownerId, Long assigneeId,
            Long studentId, String nameCipher, String phoneCipher, String contactPhoneCipher, String detailCipher) { }
    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
    private Long campusId(Actor actor, String publicId) {
        List<Long> rows = jdbc.query("SELECT c.id FROM core_campus c WHERE c.public_id=? AND c.active=1 AND c.deleted=0",
            (rs, row) -> rs.getLong(1), publicId);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "校区不存在或已停用"); }
        long id = rows.get(0);
        if (actor.scope().equals("campus") && !actor.campusIds().contains(id)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "校区不在当前授权范围");
        }
        return id;
    }
    private String campusPublicId(Long id) {
        return id == null ? null : jdbc.queryForObject("SELECT public_id FROM core_campus WHERE id=?", String.class, id);
    }
    private String publicId(String table, long id) {
        return jdbc.queryForObject("SELECT public_id FROM " + table + " WHERE id=?", String.class, id);
    }
    private LeadProfile lockLead(Actor actor, String leadPublicId) {
        ScopeSql.Clause scope = ScopeSql.leads(actor);
        List<LeadProfile> rows = jdbc.query("""
            SELECT l.id,l.customer_id,l.campus_id,l.owner_id,l.assignee_id,l.student_id,
                l.name_cipher,l.phone_cipher,l.contact_phone_cipher,l.detail_cipher
            FROM core_crm_lead l WHERE l.public_id=? AND l.deleted=0 AND """ + " " + scope.sql() + " FOR UPDATE",
            (rs, row) -> new LeadProfile(rs.getLong("id"), nullableLong(rs,"customer_id"),
                nullableLong(rs,"campus_id"), nullableLong(rs,"owner_id"), nullableLong(rs,"assignee_id"),
                nullableLong(rs,"student_id"),rs.getString("name_cipher"),rs.getString("phone_cipher"),
                rs.getString("contact_phone_cipher"),rs.getString("detail_cipher")),
            concat(List.of(leadPublicId), scope.args()).toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "原线索不存在或不在当前授权范围"); }
        return rows.get(0);
    }
    private boolean leadIdentityMatches(LeadProfile lead, String name, String phone) {
        LeadIdentity identity = resolvedLeadIdentity(lead);
        return name.equals(identity.name()) && phone.equals(identity.contactPhone());
    }
    /** 从授权线索解析的姓名、本人电话和联系人电话，用于一致性匹配。 */
    private record LeadIdentity(String name, String phone, String contactPhone) { }
    private LeadIdentity leadIdentity(LeadProfile lead) {
        LeadIdentity identity = new LeadIdentity(decrypt(lead.nameCipher()), decrypt(lead.phoneCipher()),
            decrypt(lead.contactPhoneCipher()));
        if (lead.detailCipher() != null) { identity = mergeLegacyLeadDetail(identity, lead.detailCipher()); }
        return withPhoneFallback(identity);
    }

    private String decrypt(String encrypted) {
        if (encrypted == null) { return null; }
        String value = cipher.decrypt(encrypted);
        return value.isBlank() ? null : value;
    }

    private LeadIdentity mergeLegacyLeadDetail(LeadIdentity identity, String encryptedDetail) {
        try {
            JsonNode source = mapper.readTree(cipher.decrypt(encryptedDetail));
            return new LeadIdentity(textOrExisting(source, "name", identity.name()),
                textOrExisting(source, "phone", identity.phone()),
                textOrExisting(source, "contactPhone", identity.contactPhone()));
        } catch (java.io.IOException | IllegalStateException ignored) {
            // Java-created leads keep identity on their linked customer until populated in V51 columns.
            return identity;
        }
    }

    private String textOrExisting(JsonNode source, String field, String existing) {
        if (existing != null || !source.path(field).isTextual() || source.path(field).asText().isBlank()) {
            return existing;
        }
        return source.path(field).asText();
    }

    private LeadIdentity withPhoneFallback(LeadIdentity identity) {
        String contact = identity.contactPhone() == null ? identity.phone() : identity.contactPhone();
        return new LeadIdentity(identity.name(), identity.phone(), contact);
    }
    /** Fills only absent source identity fields from the linked Java customer. */
    private LeadIdentity resolvedLeadIdentity(LeadProfile lead) {
        LeadIdentity identity = leadIdentity(lead);
        String name = identity.name();
        String contact = identity.contactPhone();
        if (lead.customerId() != null && (name == null || contact == null)) {
            if (name == null) {
                List<String> names = jdbc.query("SELECT display_name_cipher FROM core_customer "
                    + "WHERE id=? AND deleted=0", (rs, row) -> cipher.decrypt(rs.getString(1)), lead.customerId());
                if (!names.isEmpty()) { name = names.get(0); }
            }
            if (contact == null) {
                List<String> contacts = jdbc.query("SELECT value_cipher FROM core_customer_contact "
                    + "WHERE customer_id=? AND deleted=0 AND channel='phone' ORDER BY is_primary DESC,id",
                    (rs, row) -> cipher.decrypt(rs.getString(1)), lead.customerId());
                if (!contacts.isEmpty()) { contact = contacts.get(0); }
            }
        }
        return new LeadIdentity(name, identity.phone(), contact);
    }
    private void assertNoDuplicateLeadIdentity(long excludedLeadId, String name, String phone) {
        // Node checks duplicate lead identity across the source state, even when other leads are not visible.
        List<LeadProfile> leads = jdbc.query("""
            SELECT l.id,l.customer_id,l.campus_id,l.owner_id,l.assignee_id,l.student_id,
                l.name_cipher,l.phone_cipher,l.contact_phone_cipher,l.detail_cipher
            FROM core_crm_lead l WHERE l.id<>? AND l.deleted=0
            """, (rs, row) -> new LeadProfile(rs.getLong("id"),nullableLong(rs,"customer_id"),
                nullableLong(rs,"campus_id"),nullableLong(rs,"owner_id"),nullableLong(rs,"assignee_id"),
                nullableLong(rs,"student_id"),rs.getString("name_cipher"),rs.getString("phone_cipher"),
                rs.getString("contact_phone_cipher"),rs.getString("detail_cipher")),
            excludedLeadId);
        for (LeadProfile lead : leads) {
            LeadIdentity identity = resolvedLeadIdentity(lead);
            if (name.equals(identity.name()) && phone.equals(identity.contactPhone())) {
                throw new BusinessException(ErrorCode.ALREADY_EXISTS, "修改后的学生身份与另一线索重复");
            }
        }
    }
    private void assertNoDuplicateStudent(Long excludedId, String name, String phone) {
        assertIndexedIdentityUnused(excludedId, name, phone);
        assertNoNameCandidateDuplicate(name, phone, findNameCandidates(excludedId, name));
    }

    private void assertIndexedIdentityUnused(Long excludedId, String name, String phone) {
        Map<String, byte[]> identity = cipher.exactBlindIndexes("student_name",
            StudentIdentityMutationLock.identityKey(name, phone));
        String identityTerms = String.join(" OR ", identity.keySet().stream()
            .map(key -> "(key_id=? AND value_hash=?)").toList());
        List<Object> identityArgs = new ArrayList<>();
        identity.forEach((key, hash) -> { identityArgs.add(key); identityArgs.add(hash); });
        String identityExcept = excludedId == null ? "" : " AND student_id<>?";
        if (excludedId != null) { identityArgs.add(excludedId); }
        Integer indexed = jdbc.queryForObject("SELECT COUNT(id) FROM core_student_identity_index WHERE deleted=0 AND ("
            + identityTerms + ")" + identityExcept, Integer.class, identityArgs.toArray());
        if (indexed != null && indexed > 0) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "学生档案已存在"); }
    }

    private List<StudentProfile> findNameCandidates(Long excludedId, String name) {
        Map<String, byte[]> nameIndexes = cipher.blindIndexes("student_name", StudentNameIndexRepository.normalize(name));
        String nameTerms = String.join(" OR ", nameIndexes.keySet().stream()
            .map(key -> "(ni.key_id=? AND ni.value_hash=?)").toList());
        List<Object> args = new ArrayList<>();
        args.addAll(nameIndexes.keySet().stream().flatMap(key -> java.util.stream.Stream.of(key, nameIndexes.get(key))).toList());
        if (excludedId != null) { args.add(excludedId); }
        String except = excludedId == null ? "" : " AND s.id<>?";
        String sql = "SELECT s.id,s.public_id,s.name_cipher,s.grade,s.phone_cipher,s.campus_id,s.origin_lead_id,s.customer_id, "
            + "s.owner_id,s.assignee_id,s.version FROM core_student s WHERE s.deleted=0 AND "
            + "(EXISTS (SELECT ni.id FROM core_student_name_index ni WHERE ni.student_id=s.id AND ni.deleted=0 "
            + "AND ni.source_version=s.version AND (" + nameTerms + ")) OR NOT EXISTS "
            + "(SELECT ni.id FROM core_student_name_index ni WHERE ni.student_id=s.id AND ni.deleted=0 "
            + "AND ni.source_version=s.version))" + except;
        return jdbc.query(sql,
            (rs, row) -> new StudentProfile(rs.getLong("id"),rs.getString("public_id"),
                rs.getString("name_cipher"),rs.getString("grade"),rs.getString("phone_cipher"),
                nullableLong(rs,"campus_id"),nullableLong(rs,"origin_lead_id"),nullableLong(rs,"customer_id"),
                nullableLong(rs,"owner_id"),nullableLong(rs,"assignee_id"),rs.getLong("version")), args.toArray());
    }

    private void assertNoNameCandidateDuplicate(String name, String phone, List<StudentProfile> profiles) {
        for (StudentProfile profile : profiles) {
            if (name.equals(cipher.decrypt(profile.nameCipher())) && profile.phoneCipher() != null
                    && phone.equals(cipher.decrypt(profile.phoneCipher()))) {
                throw new BusinessException(ErrorCode.ALREADY_EXISTS, "学生档案已存在");
            }
        }
    }
    private void replaceStudentIdentity(String studentPublicId, String name, String phone) {
        Long studentId = jdbc.queryForObject("SELECT id FROM core_student WHERE public_id=? AND deleted=0",
            Long.class, studentPublicId);
        jdbc.update("UPDATE core_student_identity_index SET deleted=1,updated_at=UTC_TIMESTAMP(3) "
            + "WHERE student_id=? AND deleted=0", studentId);
        Map<String, byte[]> indexes = cipher.exactBlindIndexes("student_name",
            StudentIdentityMutationLock.identityKey(name, phone));
        try {
            indexes.forEach((key, hash) -> jdbc.update("""
                INSERT INTO core_student_identity_index(student_id,key_id,value_hash) VALUES(?,?,?)
                """, studentId, key, hash));
        } catch (DuplicateKeyException conflict) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "学生档案已存在");
        }
    }
    String normalizedStudentGrade(String input, String old) {
        if (input == null || input.isEmpty()) { return old; }
        String value = java.text.Normalizer.normalize(input.trim(), java.text.Normalizer.Form.NFKC)
            .replaceAll("\\s+", "");
        Matcher matcher = Pattern.compile("^(小学|小|初中|初|高中|高)?([一二三四五六七八九1-9])(?:年级|年)?$")
            .matcher(value);
        if (!matcher.matches()) {
            return preserveExistingGrade(input, old);
        }
        return canonicalGrade(input, old, value, matcher);
    }
    private String canonicalGrade(String input, String old, String value, Matcher matcher) {
        GradeLevel level = gradeLevel(value, matcher);
        int max = level.stage().equals("小学") ? 6 : 3;
        if (level.number() < 1 || level.number() > max) { return preserveExistingGrade(input, old); }
        return level.stage() + "一二三四五六".substring(level.number() - 1, level.number()) + "年级";
    }
    private GradeLevel gradeLevel(String value, Matcher matcher) {
        int number = parseGradeNumber(matcher.group(2));
        String stage = matcher.group(1);
        if (stage == null) { return inferGradeStage(value, number); }
        return new GradeLevel(normalizeGradeStage(stage), number);
    }
    private GradeLevel inferGradeStage(String value, int number) {
        if (!value.endsWith("年级") && !value.endsWith("年")) { throw invalidGrade(); }
        return number > 6 ? new GradeLevel("初中", number - 6) : new GradeLevel("小学", number);
    }
    private int parseGradeNumber(String rawNumber) {
        int chinese = "一二三四五六七八九".indexOf(rawNumber) + 1;
        return chinese == 0 ? Integer.parseInt(rawNumber) : chinese;
    }
    private String normalizeGradeStage(String stage) {
        return stage.startsWith("小") ? "小学" : stage.startsWith("初") ? "初中" : "高中";
    }
    private String preserveExistingGrade(String input, String old) {
        if (old != null && old.equals(input)) { return old; }
        throw invalidGrade();
    }
    /** 解析后的学段与年级序号，用于规范年级校验。 */
    private record GradeLevel(String stage, int number) { }
    private BusinessException invalidGrade() {
        return new BusinessException(ErrorCode.INVALID_INPUT, "请选择小学一年级至高中三年级的有效年级");
    }
    private void appendLeadProfileEvent(Actor actor, long leadId, String type, String studentPublicId) {
        String eventId = "LEADEVT_" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_crm_lead_event (public_id,lead_id,actor_id,event_type,occurred_at,detail_cipher)
            VALUES (?,?,?, ?,UTC_TIMESTAMP(3),?)
            """, eventId, leadId, actor.id(), type, cipher.encrypt("student:" + studentPublicId));
    }
    private List<Object> concat(List<?> first, List<?> second) {
        List<Object> values = new ArrayList<>(first.size() + second.size());
        values.addAll(first); values.addAll(second); return values;
    }
}
