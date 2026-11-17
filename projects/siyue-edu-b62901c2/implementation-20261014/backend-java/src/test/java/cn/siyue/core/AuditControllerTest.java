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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuditController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties={"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class AuditControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private AuditService service;
    private Actor actor(){return new Actor(4,"staff-4","all",Set.of(),Set.of("audit.read"));}

    @Test void auditReadRequiresAuthenticationAndPermissionBackedActor() throws Exception {
        mvc.perform(get("/api/v2/audit")).andExpect(status().isUnauthorized());
        verifyNoInteractions(access,service);
        when(access.resolve("https://issuer.example","subject")).thenReturn(actor());
        var query=new AuditDtos.AuditQuery("staff-5","academic.class.update","class-1",null,null,1,20);
        when(service.search(actor(),query))
            .thenReturn(new AuditDtos.AuditPage(List.of(new AuditDtos.AuditEvent(
                java.time.Instant.parse("2026-10-14T00:00:00Z"),"staff-4","academic.class.update",
                "class-1","trace-1")),1,1,20));
        mvc.perform(get("/api/v2/audit").param("actorId","staff-5").param("action","academic.class.update")
            .param("resourceId","class-1").param("page","1").param("size","20")
            .with(jwt().jwt(token->token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].actorId").value("staff-4"));
        verify(service).search(actor(),query);
    }
}
