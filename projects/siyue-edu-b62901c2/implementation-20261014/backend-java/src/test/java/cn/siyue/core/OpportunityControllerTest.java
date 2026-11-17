package cn.siyue.core;

import cn.siyue.core.CrmDtos.Opportunity;
import cn.siyue.core.CrmDtos.OpportunityEvent;
import cn.siyue.core.CrmDtos.OpportunityInput;
import cn.siyue.core.CrmDtos.OpportunityPage;
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

@WebMvcTest(OpportunityController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class OpportunityControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private OpportunityService service;
    private Actor actor() { return new Actor(5, "staff-5", "own", Set.of(), Set.of("crm.opportunity.read")); }
    @Test void listUsesBoundedFiltersAndCreationRequiresSeparateNeedFields() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        when(service.list(actor(), "CUS_1", "qualified", 1, 25))
            .thenReturn(new OpportunityPage(List.of(), 0, 1, 25));
        mvc.perform(get("/api/v2/crm/opportunities?customerId=CUS_1&stage=qualified&page=1&size=25")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        Instant start = Instant.parse("2026-10-14T00:00:00Z");
        var created = new Opportunity("OPP_1", "CUS_1", null, "campus-2", "staff-5", null, null,
            "王同学 · 数学 · 2026秋", "数学", "2026秋", "qualified", null, start, 1);
        var input = new OpportunityInput("CUS_1", null, null, null, created.title(), "数学", "2026秋", null);
        when(service.create(actor(), "opportunity-create-key-123", input)).thenReturn(created);
        mvc.perform(post("/api/v2/crm/opportunities")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com").header("Idempotency-Key", "opportunity-create-key-123")
            .contentType("application/json").content("""
                {"customerId":"CUS_1","title":"王同学 · 数学 · 2026秋","subject":"数学","term":"2026秋"}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.stage").value("qualified"));
        verify(service).create(actor(), "opportunity-create-key-123", input);
    }
    @Test void wonCannotBeSubmittedAsAManualStage() throws Exception {
        mvc.perform(put("/api/v2/crm/opportunities/OPP_1/stage")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example"))).header("Origin", "https://crm.example.com")
            .header("Idempotency-Key", "opportunity-stage-key-123")
            .contentType("application/json")
            .content("{\"stage\":\"won\",\"expectedVersion\":1}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void opportunityEventsAreReadFromTheDedicatedTimeline() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        when(service.events(actor(), "OPP_1")).thenReturn(List.of(new OpportunityEvent("EVT_1", "staff-5",
            "followup", "qualified", "qualified", "phone", "完成初次沟通", Instant.EPOCH)));
        mvc.perform(get("/api/v2/crm/opportunities/OPP_1/events")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].eventType").value("followup"));
    }
}
