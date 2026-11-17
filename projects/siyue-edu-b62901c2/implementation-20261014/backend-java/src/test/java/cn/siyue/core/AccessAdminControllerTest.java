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

@WebMvcTest(AccessAdminController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties={"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class AccessAdminControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private AccessAdminService service;

    @Test void listRequiresBearerIdentityAndUsesBoundedPagination() throws Exception {
        mvc.perform(get("/api/v2/system/access/principals"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(access, service);
        Actor actor = new Actor(4, "admin-4", "all", Set.of(), Set.of("system.access.manage"));
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor);
        when(service.list(actor, 2, 20)).thenReturn(List.of(new AccessAdminDtos.PrincipalView(
            "staff-5", "own", true, 2, false, List.of("student.read"), List.of("student.read"), List.of(), List.of())));
        mvc.perform(get("/api/v2/system/access/principals").param("page", "2").param("size", "20")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].principalId").value("staff-5"))
            .andExpect(jsonPath("$.data[0].permissionCodes[0]").value("student.read"));
        verify(service).list(actor, 2, 20);
    }
}
