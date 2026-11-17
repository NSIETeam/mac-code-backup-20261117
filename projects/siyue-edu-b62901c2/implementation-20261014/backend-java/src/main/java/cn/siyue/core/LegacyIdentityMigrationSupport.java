package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Supplies serialization and idempotency services used by legacy identity migration. */
@Component
final class LegacyIdentityMigrationSupport {
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    LegacyIdentityMigrationSupport(MutationRepository mutations, ObjectMapper mapper) {
        this.mutations = mutations;
        this.mapper = mapper;
    }

    MutationRepository mutations() {
        return mutations;
    }

    ObjectMapper mapper() {
        return mapper;
    }
}
