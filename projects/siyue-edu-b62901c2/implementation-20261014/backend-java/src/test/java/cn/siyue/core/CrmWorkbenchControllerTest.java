package cn.siyue.core;

import java.time.Instant;
import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CrmWorkbenchController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class CrmWorkbenchControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private CrmWorkbenchService service;

    @Test void bindsViewAndDateAndReturnsServerCountsWithDrilldown() throws Exception {
        Actor actor = new Actor(5, "staff-5", "own", Set.of(), Set.of("crm.lead.read", "crm.customer.read"));
        var date = LocalDate.parse("2026-10-14");
        var card = new CrmDtos.CrmWorkbenchCard("dueToday", "今日回访", 4,
            Map.of("activeOnly", "true", "ownerId", "staff-5"));
        var result = new CrmDtos.CrmWorkbench("mine", date, Instant.parse("2026-10-14T04:00:00Z"),
            Instant.parse("2026-10-13T16:00:00Z"), Instant.parse("2026-10-14T16:00:00Z"), List.of(card),
            Instant.parse("2026-10-14T04:00:00Z"));
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor);
        when(service.get(actor, "mine", date)).thenReturn(result);
        mvc.perform(get("/api/v2/crm/workbench?view=mine&date=2026-10-14")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.cards[0].count").value(4))
            .andExpect(jsonPath("$.data.cards[0].leadFilters.activeOnly").value("true"))
            .andExpect(jsonPath("$.data.cards[0].leadFilters.ownerId").value("staff-5"));
        verify(service).get(actor, "mine", date);
    }
}
