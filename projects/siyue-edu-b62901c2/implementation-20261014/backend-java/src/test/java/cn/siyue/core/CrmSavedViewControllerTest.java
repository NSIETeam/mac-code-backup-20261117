package cn.siyue.core;

import cn.siyue.core.CrmSavedViewDtos.Create;
import cn.siyue.core.CrmSavedViewDtos.View;
import java.util.List;
import java.util.Map;
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

@WebMvcTest(CrmSavedViewController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class CrmSavedViewControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private CrmSavedViewService service;
    private Actor actor() { return new Actor(5, "staff-5", "own", Set.of(), Set.of("crm.lead.read")); }
    private Actor customerActor() {
        return new Actor(6, "staff-6", "own", Set.of(), Set.of("crm.customer.read"));
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor token() {
        return jwt().jwt(value -> value.issuer("https://issuer.example").subject("subject"));
    }
    @Test void personalLeadViewContract() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        var view = new View("VIEW_1", "leads", "今日未联系", Map.of("status", "new"), 1);
        when(service.list(actor(), "leads")).thenReturn(List.of(view));
        mvc.perform(get("/api/v2/crm/saved-views?resourceType=leads").with(token())
                .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("今日未联系"))
            .andExpect(jsonPath("$.data[0].filters.status").value("new"));
        var input = new Create("leads", "今日未联系", Map.of("status", "new"));
        when(service.create(actor(), "crm-saved-view-key-123", input)).thenReturn(view);
        mvc.perform(post("/api/v2/crm/saved-views").with(token()).header("Origin", "https://crm.example.com")
                .header("Idempotency-Key", "crm-saved-view-key-123").contentType("application/json")
                .content("{\"resourceType\":\"leads\",\"name\":\"今日未联系\",\"filters\":{\"status\":\"new\"}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("VIEW_1"));
        verify(service).create(actor(), "crm-saved-view-key-123", input);
    }
    @Test void unsupportedQueueFailsBeforeServiceCall() throws Exception {
        mvc.perform(get("/api/v2/crm/saved-views?resourceType=all").with(token())
                .header("Origin", "https://crm.example.com"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void customersQueuePassesHttpParameterValidationAndChecksNativePermissionInService()
            throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(customerActor());
        var view = new View("VIEW_CUSTOMER_1", "customers", "按姓名查找", Map.of("searchType", "name"), 1);
        when(service.list(customerActor(), "customers")).thenReturn(List.of(view));
        mvc.perform(get("/api/v2/crm/saved-views?resourceType=customers").with(token())
                .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].resourceType").value("customers"))
            .andExpect(jsonPath("$.data[0].filters.searchType").value("name"));
        verify(service).list(customerActor(), "customers");
    }
}
