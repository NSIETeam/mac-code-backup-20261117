package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.MarkRead;
import cn.siyue.core.OpportunityNeedDtos.Notification;
import cn.siyue.core.OpportunityNeedDtos.NotificationPage;
import cn.siyue.core.OpportunityNeedDtos.NotificationQuery;
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

@WebMvcTest(OpportunityNotificationController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core",
    "spring.jackson.deserialization.fail-on-unknown-properties=true"})
class OpportunityNotificationControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private OpportunityNotificationService service;
    private final Actor actor = new Actor(7, "EMP_7", "own", Set.of(), Set.of("crm.opportunity.read"));

    private void identity() { when(access.resolve("https://issuer.example", "subject")).thenReturn(actor); }
    private org.springframework.test.web.servlet.request.RequestPostProcessor authenticated() {
        return jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject"));
    }

    @Test void recipientListBindsFiltersAndReturnsRestrictedShellWithoutConsultationText() throws Exception {
        identity(); var query = new NotificationQuery("all", 2, 10);
        when(service.list(actor, query)).thenReturn(new NotificationPage(List.of(new Notification("NTF_1",
            null, null, "repeat_consultation", Instant.EPOCH, null, 1, "restricted")), 11, 2, 10));
        mvc.perform(get("/api/v2/crm/opportunity-notifications?status=all&page=2&size=10").with(authenticated()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(11))
            .andExpect(jsonPath("$.data.items[0].visibility").value("restricted"))
            .andExpect(jsonPath("$.data.items[0].opportunityId").isEmpty())
            .andExpect(jsonPath("$.data.items[0].consultationId").isEmpty())
            .andExpect(jsonPath("$.data.items[0].detail").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].recipientId").doesNotExist());
        verify(service).list(actor, query);
    }

    @Test void readAcknowledgementUsesVersionAndCallerKeyWithMappedActor() throws Exception {
        identity(); var input = new MarkRead(1);
        when(service.markRead(actor, "NTF_1", "notification_read_key_1", input)).thenReturn(new Notification(
            "NTF_1", "OPP_1", "NEVT_1", "repeat_consultation", Instant.EPOCH,
            Instant.parse("2026-10-14T01:02:03.678Z"), 2, "visible"));
        mvc.perform(put("/api/v2/crm/opportunity-notifications/NTF_1/read").with(authenticated())
            .header("Origin", "https://crm.example.com").header("Idempotency-Key", "notification_read_key_1")
            .contentType("application/json").content("{\"expectedVersion\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("NTF_1"))
            .andExpect(jsonPath("$.data.version").value(2)).andExpect(jsonPath("$.data.readAt").isNotEmpty());
        verify(service).markRead(actor, "NTF_1", "notification_read_key_1", input);
    }

    @Test void anonymousRequestsCannotListOrMarkReminders() throws Exception {
        mvc.perform(get("/api/v2/crm/opportunity-notifications")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v2/crm/opportunity-notifications/NTF_1/read").contentType("application/json")
            .content("{\"expectedVersion\":1}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(access, service);
    }

    @Test void invalidFilterMalformedPageUnknownBodyAndMissingKeyAreRejected() throws Exception {
        identity();
        mvc.perform(get("/api/v2/crm/opportunity-notifications?status=foreign").with(authenticated()))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/crm/opportunity-notifications?page=bad").with(authenticated()))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v2/crm/opportunity-notifications/NTF_1/read").with(authenticated())
            .header("Idempotency-Key", "notification_read_key_1").contentType("application/json")
            .content("{\"expectedVersion\":1,\"recipientId\":\"EMP_OTHER\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v2/crm/opportunity-notifications/NTF_1/read").with(authenticated())
            .contentType("application/json").content("{\"expectedVersion\":1}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v2/crm/opportunity-notifications/NTF_1/read").with(authenticated())
            .header("Idempotency-Key", "notification_read_key_1").contentType("application/json")
            .content("{\"expectedVersion\":0}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void invalidBrowserOriginDoesNotReachAcknowledgementService() throws Exception {
        mvc.perform(put("/api/v2/crm/opportunity-notifications/NTF_1/read").with(authenticated())
            .header("Origin", "https://other.example.com").header("Idempotency-Key", "notification_read_key_1")
            .contentType("application/json").content("{\"expectedVersion\":1}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(access, service);
    }
}
