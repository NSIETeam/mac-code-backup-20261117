package cn.siyue.core;

import cn.siyue.core.CustomerSearchDtos.*;
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

@WebMvcTest({CrmController.class,CustomerSearchMaintenanceController.class})
@Import(SecurityConfiguration.class)
@TestPropertySource(properties={"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example","spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class CustomerSearchControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private CustomerStudentIntakeService studentIntake;
    @MockitoBean private CrmService crm;
    @MockitoBean private CustomerSearchMaintenanceService maintenance;
    private final Actor actor=new Actor(1,"staff-1","all",Set.of(),Set.of("crm.customer.read","crm.customer.search.manage"));
    private void current() { when(access.resolve("https://issuer.example","subject")).thenReturn(actor); }
    @Test void typedQueryKeepsOriginalNumberCaseAndLeadingZeros() throws Exception {
        current(); var query=new Query("student_number","000aA",2,20);
        when(crm.customers(actor,query)).thenReturn(new CrmDtos.CustomerPage(List.of(),0,2,20));
        mvc.perform(get("/api/v2/crm/customers").param("searchType","student_number").param("query","000aA").param("page","2").param("size","20")
            .with(jwt().jwt(token->token.issuer("https://issuer.example").subject("subject"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2));
        verify(crm).customers(actor,query);
    }
    @Test void invalidModeRejectedAndIndexNotReadyIsExplicit503() throws Exception {
        current();
        mvc.perform(get("/api/v2/crm/customers?searchType=wildcard&query=x")
            .with(jwt().jwt(token->token.issuer("https://issuer.example").subject("subject")))).andExpect(status().isBadRequest());
        when(crm.customers(actor,new Query("name","合成孩子",1,50))).thenThrow(new BusinessException(ErrorCode.SEARCH_INDEX_NOT_READY,"请维护索引"));
        mvc.perform(get("/api/v2/crm/customers").param("searchType","name").param("query","合成孩子")
            .with(jwt().jwt(token->token.issuer("https://issuer.example").subject("subject"))))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SEARCH_INDEX_NOT_READY"));
    }
    @Test void maintenanceBindsBoundedBodyAndIdempotencyWithoutPersonalData() throws Exception {
        current(); var input=new Rebuild(0,100); var receipt=new NameIndexRebuild(0,0,false,new NameIndexStatus(0,0,0,1,1));
        when(maintenance.rebuildNames(actor,"search-maintenance-123",input)).thenReturn(receipt);
        mvc.perform(post("/api/v2/crm/customer-search/student-name-index/rebuild")
            .with(jwt().jwt(token->token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com").header("Idempotency-Key","search-maintenance-123")
            .contentType("application/json").content("{\"afterId\":0,\"limit\":100}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status.pendingStudents").value(0));
        mvc.perform(post("/api/v2/crm/customer-search/student-name-index/rebuild")
            .with(jwt().jwt(token->token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com").header("Idempotency-Key","search-maintenance-123")
            .contentType("application/json").content("{\"afterId\":0,\"limit\":201}"))
            .andExpect(status().isBadRequest());
        verify(maintenance,times(1)).rebuildNames(actor,"search-maintenance-123",input);
    }
}
