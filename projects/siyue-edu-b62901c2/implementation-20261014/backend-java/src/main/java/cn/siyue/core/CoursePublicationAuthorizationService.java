package cn.siyue.core;

import cn.siyue.core.CoursePublicationRepository.Reference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the current course publication fingerprint inside a short authorization transaction. */
@Service
public class CoursePublicationAuthorizationService {
    private final AccessRepository access;
    private final AcademicCatalogRepository catalog;
    private final CoursePublicationRepository publications;

    /**
     * Creates the local authorization boundary; this service never calls Redis.
     * @param access current employee identity and permission repository
     * @param catalog scoped course metadata repository
     * @param publications publication reference repository
     */
    public CoursePublicationAuthorizationService(AccessRepository access, AcademicCatalogRepository catalog,
            CoursePublicationRepository publications) {
        this.access = access;
        this.catalog = catalog;
        this.publications = publications;
    }

    /**
     * Resolves current grants and course scope instead of trusting the caller's earlier projection.
     * @param actor authenticated employee whose current identity is locked and reloaded
     * @param courseId public identifier of the course to inspect
     * @return current course version, publication state and immutable snapshot identifier
     * @throws BusinessException if the identity is inactive, permission is absent or course is outside scope
     */
    @Transactional(rollbackFor = Exception.class)
    public Reference authorize(Actor actor, String courseId) {
        Actor current = access.lockActive(actor.id());
        current.requireAny("course.read", "course.publish");
        return publications.reference(catalog.readCourseStatus(current, courseId));
    }
}
