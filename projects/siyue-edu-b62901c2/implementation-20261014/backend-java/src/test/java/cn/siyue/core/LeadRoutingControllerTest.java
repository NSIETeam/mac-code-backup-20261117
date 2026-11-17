package cn.siyue.core;

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

@WebMvcTest(LeadRoutingController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class LeadRoutingControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private LeadRoutingService service;
    private Actor actor() { return new Actor(7,"manager","campus",Set.of(3L),Set.of("crm.lead.assign")); }
    @Test void simulatesRulesForAnAuthorizedLead() throws Exception {
        when(access.resolve("https://issuer.example","subject")).thenReturn(actor());
        var plan = new LeadRoutingDtos.RoutingPlan("LEAD_1","RULE_1","STAFF_1","命中优先级 2 的规则 数学",
            List.of(new LeadRoutingDtos.Candidate("RULE_1","数学","STAFF_1",2,true,"可接收")));
        when(service.simulate(actor(),"LEAD_1")).thenReturn(plan);
        mvc.perform(get("/api/v2/crm/lead-routing/leads/LEAD_1/simulate")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.ownerId").value("STAFF_1"))
            .andExpect(jsonPath("$.data.candidates[0].eligible").value(true));
        verify(service).simulate(actor(),"LEAD_1");
    }
    @Test void readsRuleHistoryThroughTheScopedService() throws Exception {
        when(access.resolve("https://issuer.example","subject")).thenReturn(actor());
        var page = new LeadRoutingDtos.RuleHistoryPage(List.of(new LeadRoutingDtos.RuleHistoryEvent(
            "EVENT_1", "SOURCE_1", "update", "manager", java.time.Instant.parse("2026-10-02T00:00:00Z"),
            "更新规则", "{}")), 1, 1, 50);
        when(service.ruleHistory(actor(),"RULE_1",1,50)).thenReturn(page);
        mvc.perform(get("/api/v2/crm/lead-routing/rules/RULE_1/history")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].sourceEventId").value("SOURCE_1"));
        verify(service).ruleHistory(actor(),"RULE_1",1,50);
    }
    @Test void appliesOnlyExplicitVersionedLeadSelection() throws Exception {
        when(access.resolve("https://issuer.example","subject")).thenReturn(actor());
        var input = new LeadRoutingDtos.ApplyInput(List.of(new LeadRoutingDtos.LeadVersion("LEAD_1",4)));
        when(service.apply(actor(),"routing-apply-key-12345",input)).thenReturn(
            new LeadRoutingDtos.ApplyResult(List.of(),List.of()));
        mvc.perform(post("/api/v2/crm/lead-routing/apply")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com").header("Idempotency-Key","routing-apply-key-12345")
            .contentType("application/json").content("{\"leads\":[{\"leadId\":\"LEAD_1\",\"expectedVersion\":4}] }"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.assigned").isArray())
            .andExpect(jsonPath("$.data.unmatched").isArray());
        verify(service).apply(actor(),"routing-apply-key-12345",input);
    }
    @Test void previewsBatchImpactsBeforeASeparateApplyRequest() throws Exception {
        when(access.resolve("https://issuer.example","subject")).thenReturn(actor());
        var input = new LeadRoutingDtos.ApplyInput(List.of(new LeadRoutingDtos.LeadVersion("LEAD_1",4)));
        var preview = new LeadRoutingDtos.BatchPreview(List.of(new LeadRoutingDtos.RoutingPlan(
            "LEAD_1","RULE_1","STAFF_1","命中规则",List.of())),1,0);
        when(service.preview(actor(),input)).thenReturn(preview);
        mvc.perform(post("/api/v2/crm/lead-routing/preview")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com").contentType("application/json")
            .content("{\"leads\":[{\"leadId\":\"LEAD_1\",\"expectedVersion\":4}] }"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.assignable").value(1))
            .andExpect(jsonPath("$.data.unmatched").value(0));
        verify(service).preview(actor(),input);
    }
    @Test void rejectsOutOfRangeRulePriorityBeforeService() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        mvc.perform(post("/api/v2/crm/lead-routing/rules")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com").header("Idempotency-Key","routing-rule-key-12345")
            .contentType("application/json").content("""
                {"name":"规则","campusId":"CAMPUS_1","ownerId":"STAFF_1","priority":10001,
                 "active":true,"source":"online_form"}
                """))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void preservesCustomRoutingSourceValues() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        var input = new LeadRoutingDtos.RuleCreate("自定义来源规则", "CAMPUS_1", null,
            null, "sms", "STAFF_1", 1, true);
        var result = new LeadRoutingDtos.RoutingRule("RULE_1", input.name(), input.campusId(), null,
            null, input.source(), input.ownerId(), input.priority(), input.active(), 1);
        when(service.createRule(actor(), "routing-rule-key-12345", input)).thenReturn(result);
        mvc.perform(post("/api/v2/crm/lead-routing/rules")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com").header("Idempotency-Key", "routing-rule-key-12345")
            .contentType("application/json").content("""
                {"name":"自定义来源规则","campusId":"CAMPUS_1","ownerId":"STAFF_1","priority":1,
                 "active":true,"source":"sms"}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("sms"));
        verify(service).createRule(actor(), "routing-rule-key-12345", input);
    }
}
