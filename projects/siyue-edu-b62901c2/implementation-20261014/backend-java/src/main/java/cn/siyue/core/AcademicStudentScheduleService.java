package cn.siyue.core;

import cn.siyue.core.AcademicDtos.StudentSchedulePage;
import cn.siyue.core.AcademicDtos.StudentScheduleQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Enforces current student and class read grants before returning a paged student schedule. */
@Service
public class AcademicStudentScheduleService {
    private final AccessRepository access;
    private final AcademicStudentScheduleRepository repository;

    /** Creates the service with active-principal revalidation and scoped schedule persistence. */
    public AcademicStudentScheduleService(AccessRepository access, AcademicStudentScheduleRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    /**
     * Lists effective lesson occurrences in a validated window of at most 31 days.
     * @param actor authenticated principal requiring both {@code class.read} and {@code student.read}
     * @param query optional student selector plus business-time bounds and bounded page coordinates
     * @return one page of schedule rows within both current student and class scopes
     * @throws BusinessException when either grant is missing/revoked, the view is invalid, or the time/page bounds fail
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED, readOnly = false)
    public StudentSchedulePage list(Actor actor, StudentScheduleQuery query) {
        actor.require("class.read");
        actor.require("student.read");
        Actor current = access.lockActiveShared(actor.id());
        current.require("class.read");
        current.require("student.read");
        return repository.list(current, query);
    }
}
