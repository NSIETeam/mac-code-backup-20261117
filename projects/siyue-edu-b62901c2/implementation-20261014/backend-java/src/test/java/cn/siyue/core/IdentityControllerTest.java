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

@WebMvcTest(IdentityController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class IdentityControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;

    @Test void returnsDatabaseResolvedScopeAndPermissionsWithoutSequenceIds() throws Exception {
        Actor actor = new Actor(97, "staff-97", "campus", Set.of(21L),
            Set.of("crm.lead.read", "crm.customer.read"));
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor);
        when(access.campusPublicIds(actor)).thenReturn(List.of("CAMPUS_A"));
        mvc.perform(get("/api/v2/identity/me")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.principalId").value("staff-97"))
            .andExpect(jsonPath("$.data.scope").value("campus"))
            .andExpect(jsonPath("$.data.campusIds[0]").value("CAMPUS_A"))
            .andExpect(jsonPath("$.data.permissions").isArray())
            .andExpect(jsonPath("$.data.id").doesNotExist());
        verify(access).resolve("https://issuer.example", "subject");
        verify(access).campusPublicIds(actor);
    }
}
