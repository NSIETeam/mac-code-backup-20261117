package cn.siyue.core;

import cn.siyue.core.StaffDirectoryDtos.DirectoryPage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Applies exact legacy directory grants and caller scope before reading staff profiles. */
@Service
public class StaffDirectoryService {
    private final AccessRepository access;
    private final StaffDirectoryRepository repository;

    /** Wires current principal resolution and the independent business-profile directory.
     * @param access repository used to reload and lock the active caller
     * @param repository scoped employee/profile reader
     */
    public StaffDirectoryService(AccessRepository access, StaffDirectoryRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    /** Reads a directory page using current grants and current position-based teacher classification.
     * @param authenticatedActor authenticated principal reference; its grants are reloaded inside the transaction
     * @param category all, staff or teacher; teacher-only access also accepts teacher.read or teacher.archive
     * @param page one-based page from 1 through 100000
     * @param size maximum members from 1 through 100
     * @return visible active principals and available business-profile fields, without credentials
     * @throws BusinessException if the principal is inactive, required directory grants are absent, or category/page is invalid
     */
    @Transactional(readOnly = false, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public DirectoryPage list(Actor authenticatedActor, String category, int page, int size) {
        Actor actor = access.lockActiveShared(authenticatedActor.id());
        String normalizedCategory = category(category);
        authorize(actor, normalizedCategory);
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "员工目录分页范围无效");
        }
        return repository.list(actor, normalizedCategory, page, size);
    }

    private static void authorize(Actor actor, String category) {
        if (category.equals("teacher")) {
            actor.requireAny("staff.read", "staff.archive", "account.update", "teacher.read", "teacher.archive");
        } else if (category.equals("staff")) {
            actor.requireAny("staff.read", "staff.archive", "account.update");
        } else {
            actor.requireAny("staff.read", "staff.archive", "account.update");
        }
    }

    private static String category(String category) {
        if (category == null || !java.util.Set.of("all", "staff", "teacher").contains(category)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "员工目录类别无效");
        }
        return category;
    }
}
