package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reads the five academic dictionary categories and atomically replaces one category's ordered values.
 * Reads require settings.update; replacements additionally require all-data scope and recheck active grants.
 * Replacement retains old rows by logical deletion, serializes through the campus-directory lock, and commits
 * dictionary changes, encrypted retry receipt and audit together under READ_COMMITTED isolation.
 */
@Service
public class AcademicDictionaryService {
    static final List<String> KINDS = List.of("subjects", "grades", "terms", "levels", "classTypes");
    /** Requested replacement for one category; validation trims values before hashing and persistence.
     * @param key one of subjects, grades, terms, levels or classTypes
     * @param values 1–200 non-null, nonblank values, each at most 100 characters after trimming, without duplicates
     */
    public record Input(String key, List<String> values) { }
    /** Full dictionary projection in category and configured value order.
     * @param values the five supported categories mapped to their active ordered values, including empty lists
     */
    public record Dictionary(Map<String, List<String>> values) { }
    private final JdbcTemplate jdbc;
    private final AccessRepository access;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /** Supplies local dictionary SQL, principal locking and the shared mutation journal.
     * @param jdbc transaction-participating connection access for dictionary rows and the directory lock
     * @param access current active-account and permission lookup for replacements
     * @param mutations canonical hashing, encrypted retry receipts and audit persistence
     * @param mapper normalized request and result JSON serializer
     */
    public AcademicDictionaryService(JdbcTemplate jdbc, AccessRepository access,
            MutationRepository mutations, ObjectMapper mapper) {
        this.jdbc = jdbc; this.access = access; this.mutations = mutations; this.mapper = mapper;
    }

    /** Reads all supported categories and their non-deleted values in configured order.
     * @param actor database-resolved caller with settings.update; no all-data-scope condition applies to this read
     * @return all five dictionary categories with active values ordered by sequence and internal identifier
     * @throws BusinessException if settings.update is absent
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Dictionary get(Actor actor) {
        actor.require("settings.update");
        return read();
    }

    /** Replaces one dictionary category while retaining prior rows and recording an exact retry receipt.
     * @param actor caller with settings.update and all-data scope, rechecked after locking the active principal
     * @param key retry key containing 16–128 letters, digits, dots, underscores, colons or hyphens
     * @param input supported category and 1–200 values, each nonblank and at most 100 trimmed characters;
     *              duplicate trimmed values are rejected and supplied ordering is retained
     * @return the complete dictionary after replacement, or the original result for the same normalized request
     * @throws BusinessException for missing/revoked permission, non-global scope, invalid category/values/key,
     *         duplicate normalized values or reuse of a retry key for another request
     * @throws JsonProcessingException if normalized request or result serialization/deserialization fails
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Dictionary update(Actor actor, String key, Input input) throws JsonProcessingException {
        authorize(actor);
        Input clean = validate(input);
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "幂等键格式无效");
        }
        actor = access.lockActive(actor.id()); authorize(actor);
        String hash = mutations.hash(mapper.writeValueAsString(clean));
        String replay = mutations.replay(actor, key, "dictionary.update", hash);
        if (replay != null) { return mapper.readValue(replay, Dictionary.class); }
        jdbc.queryForObject("SELECT id FROM core_campus_configuration_lock WHERE lock_name='campus-directory' FOR UPDATE", Long.class);
        jdbc.update("UPDATE core_academic_dictionary_value SET deleted=1,updated_at=UTC_TIMESTAMP(3) WHERE kind=? AND deleted=0", clean.key());
        int sequence = 0;
        for (String value : clean.values()) {
            jdbc.update("""
                INSERT INTO core_academic_dictionary_value(kind,value,sequence_no) VALUES(?,?,?)
                ON DUPLICATE KEY UPDATE deleted=0,sequence_no=VALUES(sequence_no),updated_at=UTC_TIMESTAMP(3)
                """, clean.key(), value, ++sequence);
        }
        Dictionary result = read();
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "dictionary.update", hash,
            mapper.writeValueAsString(result), clean.key()));
        return result;
    }

    static Input validate(Input input) {
        if (input == null || input.key() == null || !KINDS.contains(input.key()) || input.values() == null
                || input.values().isEmpty() || input.values().size() > 200) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "字典分类或项数无效");
        }
        List<String> clean = input.values().stream().map(value -> {
            if (value == null || value.trim().isEmpty() || value.trim().length() > 100) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "字典项无效");
            }
            return value.trim();
        }).toList();
        if (clean.stream().distinct().count() != clean.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "字典项重复");
        }
        return new Input(input.key(), clean);
    }

    private Dictionary read() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String kind : KINDS) result.put(kind, jdbc.query("SELECT value FROM core_academic_dictionary_value "
            + "WHERE kind=? AND deleted=0 ORDER BY sequence_no,id", (rs, row) -> rs.getString(1), kind));
        return new Dictionary(result);
    }

    private static void authorize(Actor actor) {
        actor.require("settings.update");
        if (!"all".equals(actor.scope())) throw new BusinessException(ErrorCode.FORBIDDEN, "教学字典修改需要全部数据范围");
    }
}
