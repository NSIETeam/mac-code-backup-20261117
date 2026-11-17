package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.*;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OpportunityNeedController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties={"siyue.web-origin=https://crm.example.com","spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example","spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class OpportunityNeedControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private OpportunityNeedService service;
    @MockitoBean private OpportunityNeedReadRepository reads;
    @MockitoBean private OpportunityConfigRepository config;
    @MockitoBean private OpportunityAssignmentService assignment;
    private Actor actor() { return new Actor(1,"P1","own",Set.of(),Set.of("crm.opportunity.read")); }
    @Test void existingAdvisorCanRequestOptionsByOpportunityWithoutIntakeCustomerPermissions() throws Exception {
        when(access.resolve("https://issuer.example","subject")).thenReturn(actor());
        when(service.optionsFor(actor(),"O1")).thenReturn(new Options(5,null,List.of(),List.of()));
        mvc.perform(get("/api/v2/crm/opportunity-needs/O1/options").with(jwt().jwt(t->t.issuer("https://issuer.example").subject("subject"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.catalogVersion").value(5));
        verifyNoInteractions(config,reads);
    }
    @Test void missingDepartmentIsRejectedBeforePreview() throws Exception {
        mvc.perform(post("/api/v2/crm/opportunity-needs/preview").with(jwt().jwt(token -> token.issuer("https://issuer.example"))).header("Origin","https://crm.example.com")
            .contentType("application/json").content("""
              {"customerId":"C1","title":"需求","subject":"数学","term":"秋","detail":"真实咨询","identity":{"projectId":"P1"}}
              """)).andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }
    @Test void previewUsesDepartmentProjectSeparatelyAndReturnsExplicitOutcomeCandidates() throws Exception {
        when(access.resolve("https://issuer.example","subject")).thenReturn(actor());
        when(service.preview(eq(actor()),any())).thenReturn(new Preview(1,"hash",2,null,null,null,List.of(),List.of("create"),"P1",List.of()));
        mvc.perform(post("/api/v2/crm/opportunity-needs/preview").with(jwt().jwt(t->t.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com").contentType("application/json").content("""
              {"customerId":"C1","title":"需求","subject":"数学","term":"秋","detail":"真实咨询","identity":{"departmentId":"D1","projectId":"PROJ1"}}
              """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.allowedActions[0]").value("create"));
        verify(service).preview(eq(actor()),argThat(input->input.identity().departmentId().equals("D1")&&input.identity().projectId().equals("PROJ1")&&input.subject().equals("数学")));
    }
    @Test void assignmentAndProfileRequireVersionedPayloadAndIdempotencyKey() throws Exception {
        mvc.perform(put("/api/v2/crm/opportunity-needs/O1/assignment").with(jwt().jwt(token -> token.issuer("https://issuer.example"))).header("Origin","https://crm.example.com")
            .contentType("application/json").content("{\"ownerId\":\"P2\",\"reason\":\"交接\",\"expectedVersion\":1}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v2/crm/opportunity-needs/O1/profile").with(jwt().jwt(token -> token.issuer("https://issuer.example"))).header("Origin","https://crm.example.com")
            .header("Idempotency-Key","profile-original-request").contentType("application/json")
            .content("{\"profile\":{},\"expectedVersion\":1,\"expectedCatalogVersion\":0}"))
            .andExpect(status().isBadRequest()); verifyNoInteractions(service,assignment);
    }
}
