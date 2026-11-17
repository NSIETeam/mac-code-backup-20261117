package cn.siyue.core;

import cn.siyue.core.CrmDtos.Activity;
import cn.siyue.core.CrmDtos.ActivityInput;
import cn.siyue.core.CrmDtos.ActivityMembersInput;
import cn.siyue.core.CrmDtos.ActivityPage;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CrmActivityController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class CrmActivityControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private CrmService service;
    private Actor actor() { return new Actor(5, "staff-5", "campus", Set.of(2L), Set.of("crm.activity.read")); }
    @Test void createActivityRequiresTypedDatesAndIdempotency() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        Instant start = Instant.parse("2026-11-01T01:00:00Z");
        var input = new ActivityInput("campus-2", "秋季开放日", "event", start,
            start.plusSeconds(3600), "合成活动", List.of("staff-6"), List.of("staff-7"));
        when(service.createActivity(actor(), "stable-activity-key-123", input)).thenReturn(
            new Activity("ACT_1", "campus-2", "staff-5", "秋季开放日", "event", "active",
                start, start.plusSeconds(3600), "合成活动", 1, List.of("staff-6"), List.of("staff-7")));
        mvc.perform(post("/api/v2/crm/activities")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com").header("Idempotency-Key", "stable-activity-key-123")
            .contentType("application/json").content("""
                {"campusId":"campus-2","title":"秋季开放日","activityType":"event",
                 "startsAt":"2026-11-01T01:00:00Z","endsAt":"2026-11-01T02:00:00Z",
                 "description":"合成活动","participantIds":["staff-6"],"promoterIds":["staff-7"]}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.participantIds[0]").value("staff-6"))
            .andExpect(jsonPath("$.data.promoterIds[0]").value("staff-7"));
        mvc.perform(post("/api/v2/crm/activities")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example"))).header("Origin", "https://crm.example.com").contentType("application/json")
            .content("{\"campusId\":\"campus-2\",\"title\":\"x\",\"activityType\":\"unknown\"}"))
            .andExpect(status().isBadRequest());
        verify(service).createActivity(actor(), "stable-activity-key-123", input);
    }
    @Test void activityListsAndClosesWithOptimisticVersion() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        when(service.activities(actor(), new CrmActivityQuery("campus-2", "scope", "event", "active", "开放", 1, 25)))
            .thenReturn(new ActivityPage(List.of(), 0, 1, 25));
        mvc.perform(get("/api/v2/crm/activities?campusId=campus-2&view=scope&activityType=event&status=active&page=1&size=25").queryParam("query","开放")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        verify(service).activities(actor(), new CrmActivityQuery("campus-2", "scope", "event", "active", "开放", 1, 25));
        when(service.closeActivity(actor(), "ACT_1", "stable-activity-close-123",
            new CrmDtos.ActivityVersion(1))).thenReturn(new Activity("ACT_1", "campus-2", "staff-5",
                "开放日", "event", "closed", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), null, 2,
                List.of(), List.of()));
        mvc.perform(put("/api/v2/crm/activities/ACT_1/close")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com").header("Idempotency-Key", "stable-activity-close-123")
            .contentType("application/json").content("{\"expectedVersion\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("closed"))
            .andExpect(jsonPath("$.data.version").value(2));
    }
    @Test void memberReplacementRequiresTheCurrentVersionAndIdempotencyKey() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        var input = new ActivityMembersInput(List.of("staff-6"), List.of(), 4);
        var updated = new Activity("ACT_1", "campus-2", "staff-5", "开放日", "event", "active",
            Instant.EPOCH, Instant.EPOCH.plusSeconds(60), null, 5, List.of("staff-6"), List.of());
        when(service.updateActivityMembers(actor(), "ACT_1", "activity-members-key-123", input)).thenReturn(updated);
        mvc.perform(put("/api/v2/crm/activities/ACT_1/members")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com").header("Idempotency-Key", "activity-members-key-123")
            .contentType("application/json").content("""
                {"participantIds":["staff-6"],"promoterIds":[],"expectedVersion":4}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(5));
        verify(service).updateActivityMembers(actor(), "ACT_1", "activity-members-key-123", input);
    }
}
