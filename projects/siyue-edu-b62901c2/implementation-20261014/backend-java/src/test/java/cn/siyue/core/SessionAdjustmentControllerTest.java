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

@WebMvcTest(SessionAdjustmentController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties={"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class SessionAdjustmentControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private SessionAdjustmentService service;

    @Test void listsOnlyBoundedAndValidatedReservationFilters() throws Exception {
        mvc.perform(get("/api/v2/academic/makeup?kind=unknown").with(jwt().jwt(token -> token.issuer("https://issuer.example"))))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/academic/makeup?size=101").with(jwt().jwt(token -> token.issuer("https://issuer.example"))))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void passesTheAuthenticatedActorAndExactListQuery() throws Exception {
        Actor actor = new Actor(8, "teacher", "assigned", Set.of(), Set.of("class.read"));
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor);
        var query = new SessionAdjustmentDtos.Query("makeup", "active", 2, 20);
        when(service.list(actor, query)).thenReturn(new SessionAdjustmentDtos.Page(List.of(), 0, 2, 20));
        mvc.perform(get("/api/v2/academic/makeup?kind=makeup&status=active&page=2&size=20")
                .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.page").value(2));
        verify(service).list(actor, query);
    }
}
