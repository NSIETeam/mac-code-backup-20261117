package cn.siyue.core;

import com.fasterxml.jackson.databind.JsonNode;

/** Rejects catalog history that is archived in the source but has no runtime-table mapping yet. */
final class LegacyCatalogSourcePreflight {
    private LegacyCatalogSourcePreflight() { }

    /** Checks unsupported class/session history before any phase checkpoint or business write.
     * @param state original business state; empty optional collections remain valid
     * @throws IllegalArgumentException if teacher collections or teaching history would be lost
     */
    static void requireMappedHistory(JsonNode state) {
        for (JsonNode classroom : LegacyCatalogRehearsal.array(state, "classes")) {
            JsonNode teachers = classroom.get("teacherIds");
            if (teachers != null && !teachers.isNull() && (!teachers.isArray() || !teachers.isEmpty())) {
                throw new IllegalArgumentException("班级 teacherIds 尚无完整迁移映射");
            }
            for (JsonNode session : LegacyCatalogRehearsal.array(classroom, "schedule")) {
                JsonNode record = session.get("record");
                if (record != null && !record.isNull()) {
                    throw new IllegalArgumentException("课次 record 的内容、操作人及时间尚无完整迁移映射");
                }
                requireEmptyText(session, "substituteReason");
                requireEmptyText(session, "substituteTeacherId");
            }
        }
    }

    private static void requireEmptyText(JsonNode session, String field) {
        JsonNode value = session.get(field);
        if (value != null && !value.isNull() && (!value.isTextual() || !value.asText().isBlank())) {
            throw new IllegalArgumentException("课次 " + field + " 尚无完整迁移映射");
        }
    }
}
