package cn.siyue.core;

import cn.siyue.core.SalesReportDtos.ActivityPage;
import cn.siyue.core.SalesReportDtos.Metric;
import cn.siyue.core.SalesReportDtos.Report;
import java.math.BigDecimal;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SalesReportController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class SalesReportControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private SalesReportRepository repository;
    @MockitoBean private PiiCipher cipher;

    private Actor actor() { return new Actor(5, "staff-5", "all", Set.of(), Set.of("report.read")); }

    @Test void reportAcceptsDateRangeAndReturnsAssignmentCohort() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        Instant from = Instant.parse("2026-09-30T16:00:00Z");
        Instant to = Instant.parse("2026-10-31T15:59:59.999Z");
        Report report = new Report(List.of(new Metric("staff-5", 3, 0, null, 0, null, true, 10,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0)), "全部授权数据", from, to,
            "assignment", "按分配时间统计");
        when(repository.report(actor(), null, from, to)).thenReturn(report);
        mvc.perform(get("/api/v2/crm/reports/sales").param("from", "2026-10-01").param("to", "2026-10-31")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.cohort").value("assignment"))
            .andExpect(jsonPath("$.data.items[0].lowSample").value(true));
        verify(repository).report(actor(), null, from, to);
    }

    @Test void salesActivityKeepsOpaqueCursorAndValidatesDateOrder() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        when(repository.activity(eq(actor()), any())).thenReturn(new ActivityPage(List.of(), false, null));
        mvc.perform(get("/api/v2/crm/sales/activity").param("from", "2026-10-01").param("to", "2026-10-02")
            .param("cursor", "opaque-cursor").with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v2/crm/sales/activity").param("from", "2026-10-03").param("to", "2026-10-02")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isBadRequest());
        verify(repository, times(1)).activity(eq(actor()), any());
    }
}
