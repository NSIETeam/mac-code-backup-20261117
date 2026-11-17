package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Groups serialization and idempotency operations for reschedule transactions. */
@Component
public class AcademicRescheduleMutations {
    record Replay<T>(T value, String hash) { }
    record Completion(Actor actor, String key, String action, String hash, String body, String resource) { }
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    public AcademicRescheduleMutations(MutationRepository mutations, ObjectMapper mapper) {
        this.mutations = mutations;
        this.mapper = mapper;
    }

    String hash(Object value) throws JsonProcessingException {
        return mutations.hash(mapper.writeValueAsString(value));
    }

    <T> T replay(Actor actor, String key, String action, String hash, Class<T> type)
            throws JsonProcessingException {
        String previous = mutations.replay(actor, key, action, hash);
        return previous == null ? null : mapper.readValue(previous, type);
    }

    String serialize(Object value) throws JsonProcessingException { return mapper.writeValueAsString(value); }

    void complete(Completion completion) {
        mutations.complete(completion.actor(), new MutationRepository.RetryWrite(completion.key(),
            completion.action(), completion.hash(), completion.body(), completion.resource()));
    }
}
