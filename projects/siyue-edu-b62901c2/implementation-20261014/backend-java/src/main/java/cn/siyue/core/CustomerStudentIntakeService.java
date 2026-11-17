package cn.siyue.core;

import cn.siyue.core.AcademicDtos.Student;
import cn.siyue.core.AcademicDtos.StudentInput;
import cn.siyue.core.CrmDtos.CustomerStudent;
import cn.siyue.core.CrmDtos.CustomerStudentInput;
import cn.siyue.core.CrmDtos.CustomerStudentIntakeInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Atomically creates a student profile for CRM intake and links it to the authorized customer household.
 * It locks the normalized student identity before rechecking the active actor and commits both records with one
 * idempotency receipt; an omitted draft campus is taken from the authorized customer.
 */
@Service
public class CustomerStudentIntakeService {
    private final AccessRepository access;
    private final AcademicRepository academic;
    private final CrmRepository crm;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /** Creates the intake workflow with active-actor, student, customer, retry, and serialization dependencies.
     * @param access active-principal lock and permission recheck
     * @param academic student-identity lock and profile-creation operations
     * @param crm customer scope, campus resolution, and household-link operations
     * @param mutations idempotency receipt and audit persistence
     * @param mapper serializer used to hash the request and persist the replay result
     */
    public CustomerStudentIntakeService(AccessRepository access, AcademicRepository academic,
            CrmRepository crm, MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.academic = academic;
        this.crm = crm;
        this.mutations = mutations;
        this.mapper = mapper;
    }

    /** Creates a student profile and links it to a customer in one transaction.
     * @param actor caller requiring both {@code crm.customer.write} and {@code student.write}; active status and
     *              permissions are rechecked after identity locking
     * @param customerId public ID of the customer household, subject to the caller's CRM write scope
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input student profile and household relationship; the student object is required and its identity is
     *              checked for conflicts before either record is created
     * @return the created student-to-customer relationship and linked profile identifiers
     * @throws BusinessException when permission, customer scope, identity, input, or idempotency validation fails
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public CustomerStudent create(Actor actor, String customerId, String key,
            CustomerStudentIntakeInput input) throws JsonProcessingException {
        actor.require("crm.customer.write");
        actor.require("student.write");
        requireKey(key);
        if (input == null || input.student() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请填写学生档案");
        }
        academic.lockCreateIdentity(input.student());
        Actor current = access.lockActive(actor.id());
        current.require("crm.customer.write");
        current.require("student.write");
        crm.authorizeCustomerWrite(current, customerId);
        String action = "crm.customer.student.create_and_link";
        String hash = mutations.hash(mapper.writeValueAsString(java.util.List.of(customerId, input)));
        String replay = mutations.replay(current, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, CustomerStudent.class); }

        StudentInput studentInput = input.student();
        if (studentInput.campusId() == null || studentInput.campusId().isBlank()) {
            studentInput = new StudentInput(studentInput.name(), studentInput.grade(), studentInput.phone(),
                crm.customerCampusPublicId(current, customerId), studentInput.originLeadId());
        }
        Student student = academic.createStudent(current, studentInput);
        CustomerStudent linked = crm.linkOwnedStudent(current, customerId, student.id(), input.relationship());
        mutations.complete(current, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(linked), linked.linkId()));
        return linked;
    }

    private void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请提供有效幂等键");
        }
    }
}
