package cn.siyue.core;

import cn.siyue.core.LeadPolicyDtos.Candidate;
import cn.siyue.core.LeadPolicyDtos.Policy;
import cn.siyue.core.LeadPolicyDtos.PolicyInput;
import cn.siyue.core.LeadPolicyDtos.Preview;
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

@WebMvcTest(LeadPolicyController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class LeadPolicyControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private LeadPolicyService service;
    private Actor actor() { return new Actor(7, "manager", "all", Set.of(),
        Set.of("crm.lead.assign", "crm.customer.read", "crm.lead.policy.manage")); }
    private org.springframework.test.web.servlet.request.RequestPostProcessor token() {
        return jwt().jwt(value -> value.issuer("https://issuer.example").subject("subject"));
    }

    @Test void returnsPolicyAndDryRunCandidates() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        Policy policy = new Policy(14, 21, 4, 48, 1, Instant.parse("2026-10-14T00:00:00Z"), "manager");
        when(service.get(actor())).thenReturn(policy);
        when(service.preview(actor(), 1, 20)).thenReturn(new Preview(policy,
            List.of(new Candidate("LEAD_1", "STAFF_1", "CAMPUS_1", "warning",
                Instant.parse("2026-10-12T00:00:00Z"), null, Instant.parse("2026-10-12T00:00:00Z"),
                Instant.parse("2026-10-12T04:00:00Z"), 3)), 1, 1, 20));
        mvc.perform(get("/api/v2/crm/lead-policy").with(token()).header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.reserveDays").value(14));
        mvc.perform(get("/api/v2/crm/lead-policy/preview?page=1&size=20").with(token())
                .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].kind").value("warning"));
        verify(service).preview(actor(), 1, 20);
    }

    @Test void updatesThresholdsWithVersionAndIdempotency() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        PolicyInput input = new PolicyInput(14, 21, 4, 1);
        when(service.update(actor(), "lead-policy-update-123", input)).thenReturn(
            new Policy(14, 21, 4, 48, 2, Instant.parse("2026-10-14T00:00:00Z"), "manager"));
        mvc.perform(put("/api/v2/crm/lead-policy").with(token()).header("Origin", "https://crm.example.com")
                .header("Idempotency-Key", "lead-policy-update-123").contentType("application/json")
                .content("{\"reserveDays\":14,\"reclaimDays\":21,\"firstFollowHours\":4,"
                    + "\"expectedVersion\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        verify(service).update(actor(), "lead-policy-update-123", input);
    }

    @Test void rejectsOutOfRangePolicyWithoutCallingService() throws Exception {
        mvc.perform(put("/api/v2/crm/lead-policy").with(token()).header("Origin", "https://crm.example.com")
                .header("Idempotency-Key", "lead-policy-update-123").contentType("application/json")
                .content("{\"reserveDays\":0,\"reclaimDays\":21,\"firstFollowHours\":4,"
                    + "\"expectedVersion\":1}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
