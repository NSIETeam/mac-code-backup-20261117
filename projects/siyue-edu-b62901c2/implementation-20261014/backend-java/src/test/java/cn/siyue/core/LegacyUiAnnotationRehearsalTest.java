package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyUiAnnotationRehearsalTest {
    static ObjectNode envelope() throws Exception {
        return (ObjectNode) new ObjectMapper().readTree("""
            {"schema":"siyue.legacy-business/v1","revision":149,"state":{"authFeedback":{
             "schema":"siyue.legacy-auth-feedback/v1",
             "authSourceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
             "businessSourceSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
             "annotations":[{"id":"11111111-1111-4111-8111-111111111111","page":"/crm/","pageTitle":"模拟页面",
             "elementKind":"button","elementLabel":"保存","selector":"button.save","issue":"调整间距",
             "category":"interface","createdBy":"actor","createdByName":"模拟管理","status":"resolved",
             "createdAt":"2026-10-16T00:00:00Z","updatedAt":"2026-10-16T00:01:00Z"}],
             "audit":[{"id":"22222222-2222-4222-8222-222222222222","requestId":"33333333-3333-4333-8333-333333333333",
             "actorId":"actor","action":"ui.annotation.create","outcome":"success","targetId":"11111111-1111-4111-8111-111111111111",
             "at":"2026-10-16T00:00:00.001Z","details":{"page":"/crm/","elementKind":"button"}},
             {"id":"44444444-4444-4444-8444-444444444444","requestId":"55555555-5555-4555-8555-555555555555",
             "actorId":"reviewer","action":"ui.annotation.update","outcome":"success","targetId":"11111111-1111-4111-8111-111111111111",
             "at":"2026-10-16T00:01:00.001Z","details":{"status":"resolved"}}]}}}
            """);
    }
    @Test void preservesCompleteInterfaceAndWorkflowSource() throws Exception {
        var source = envelope(); var rows = LegacyUiAnnotationRehearsal.validate(LegacyUiAnnotationRehearsal.payload(source));
        assertEquals(1, rows.size()); assertEquals(2, rows.get(0).events().size());
        var row = (ObjectNode) source.path("state").path("authFeedback").path("annotations").get(0);
        row.put("category", "workflow").put("workflowName", "模拟审批").put("flowDefinition", "甲→乙")
            .put("desiredFlowDefinition", "甲→审核→乙");
        assertEquals("甲→审核→乙", LegacyUiAnnotationRehearsal.validate(LegacyUiAnnotationRehearsal.payload(source)).get(0).request().desiredFlowDefinition());
        row.remove("desiredFlowDefinition"); assertThrows(IllegalArgumentException.class,
            () -> LegacyUiAnnotationRehearsal.validate(LegacyUiAnnotationRehearsal.payload(source)));
    }
    @Test void rejectsMissingAuditAndMismatchedStatusOrActor() throws Exception {
        for (String kind : new String[]{"audit", "status", "actor", "time", "duplicate"}) {
            var source = envelope(); var payload = (ObjectNode) source.path("state").path("authFeedback");
            var row = (ObjectNode) payload.path("annotations").get(0);
            switch (kind) {
                case "audit" -> payload.putArray("audit");
                case "status" -> row.put("status", "open");
                case "actor" -> row.put("createdBy", "other");
                case "time" -> row.put("updatedAt", "2026-10-17T00:00:00Z");
                default -> ((com.fasterxml.jackson.databind.node.ArrayNode) payload.path("annotations")).add(row.deepCopy());
            }
            assertThrows(IllegalArgumentException.class, () -> LegacyUiAnnotationRehearsal.validate(payload), kind);
        }
    }
    @Test void capturesCreatorAndAuditActorsForInactivePrincipalPreparation() throws Exception {
        var state = envelope().path("state");
        assertEquals(java.util.Set.of("actor", "reviewer"), LegacyMigrationCandidateCli.requiredPrincipals(state, new ObjectMapper().createArrayNode()));
    }
}
