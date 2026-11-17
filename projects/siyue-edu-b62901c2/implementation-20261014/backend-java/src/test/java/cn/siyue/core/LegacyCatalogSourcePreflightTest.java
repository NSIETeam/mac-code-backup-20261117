package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyCatalogSourcePreflightTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void absentAndEmptyOptionalHistoryRemainsImportable() throws Exception {
        for (String state : new String[]{"{\"classes\":[]}",
                "{\"classes\":[{\"teacherIds\":[],\"schedule\":[{\"record\":null,"
                    + "\"substituteReason\":\" \",\"substituteTeacherId\":null}]}]}"}) {
            assertDoesNotThrow(() -> LegacyCatalogSourcePreflight.requireMappedHistory(mapper.readTree(state)));
        }
    }

    @Test void completeAndMalformedUnmappedHistoryIsRejectedWithoutDisclosingValues() throws Exception {
        for (String session : new String[]{
                "{\"record\":{\"content\":\"PRIVATE_FIXTURE\",\"actorId\":\"private-actor\",\"at\":\"2026-10-17T00:00:00Z\"}}",
                "{\"record\":{}}", "{\"record\":\"PRIVATE_FIXTURE\"}",
                "{\"substituteReason\":\"PRIVATE_FIXTURE\"}", "{\"substituteReason\":{}}",
                "{\"substituteTeacherId\":\"private-actor\"}", "{\"substituteTeacherId\":42}"}) {
            var state=mapper.readTree("{\"classes\":[{\"schedule\":["+session+"]}]}");
            var error=assertThrows(IllegalArgumentException.class,
                () -> LegacyCatalogSourcePreflight.requireMappedHistory(state));
            assertFalse(error.getMessage().contains("PRIVATE_FIXTURE"));
            assertFalse(error.getMessage().contains("private-actor"));
        }
    }

    @Test void teacherCollectionsCannotBeSilentlyReducedToOneTeacher() throws Exception {
        for (String teachers : new String[]{"[\"private-actor\"]", "[\"a\",\"b\"]", "{}", "\"a\""}) {
            var state=mapper.readTree("{\"classes\":[{\"teacherIds\":"+teachers+",\"schedule\":[]}]}");
            var error=assertThrows(IllegalArgumentException.class,
                () -> LegacyCatalogSourcePreflight.requireMappedHistory(state));
            assertTrue(error.getMessage().contains("teacherIds"));
            assertFalse(error.getMessage().contains("private-actor"));
        }
    }
}
