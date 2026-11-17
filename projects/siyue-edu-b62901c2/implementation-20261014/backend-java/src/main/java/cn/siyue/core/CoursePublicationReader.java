package cn.siyue.core;

import cn.siyue.core.CoursePublicationDtos.*;
import cn.siyue.core.CoursePublicationRepository.Reference;
import org.springframework.stereotype.Service;

/** Keeps remote cache calls between short transactions and rechecks authority before returning. */
@Service
public class CoursePublicationReader {
    private final CoursePublicationAuthorizationService authorization;
    private final CoursePublicationRepository publications;

    /**
     * Creates the nontransactional read coordinator.
     * @param authorization Spring-proxied service for current identity and course reference checks
     * @param publications immutable snapshot loader with transaction-aware cache bypass
     */
    public CoursePublicationReader(CoursePublicationAuthorizationService authorization,
            CoursePublicationRepository publications) {
        this.authorization = authorization;
        this.publications = publications;
    }

    /**
     * Reads an immutable snapshot and verifies permission and current version again after loading it.
     * @param actor authenticated employee; each check reloads current permissions and scope
     * @param courseId public course identifier
     * @return authorized current course state and its last immutable publication, if present
     * @throws BusinessException on access denial or a reference change while the snapshot is loaded
     */
    public View get(Actor actor, String courseId) {
        Reference before = authorization.authorize(actor, courseId);
        Snapshot snapshot = before.publicationId() == null ? null : publications.snapshot(before.publicationId());
        Reference after = authorization.authorize(actor, courseId);
        if (!before.equals(after)) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "教学版本已变化，请刷新后查看");
        }
        return new View(after.id(), after.version(), after.published(), snapshot);
    }
}
