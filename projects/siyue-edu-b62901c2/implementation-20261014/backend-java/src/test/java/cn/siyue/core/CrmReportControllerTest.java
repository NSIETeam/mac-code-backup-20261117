package cn.siyue.core;

import cn.siyue.core.CrmDtos.ReceiptReport;
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

@WebMvcTest(CrmReportController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class CrmReportControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private CrmOrderService service;
    @MockitoBean private CrmRefundService refunds;
    private Actor actor() { return new Actor(5, "staff-5", "own", Set.of(), Set.of("report.read")); }

    @Test void receiptReportAcceptsExplicitUtcWindow() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant until = Instant.parse("2026-10-31T00:00:00Z");
        when(service.receiptReport(actor(), from, until, 1, 25)).thenReturn(new ReceiptReport(
            List.of(), 0, BigDecimal.ZERO, from, until, Instant.now(), 1, 25));
        mvc.perform(get("/api/v2/crm/reports/receipts")
            .param("fromAt", from.toString()).param("untilAt", until.toString())
            .param("page", "1").param("size", "25")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalReceived").value(0));
        verify(service).receiptReport(actor(), from, until, 1, 25);
    }

    @Test void missingPeriodDoesNotCallReportService() throws Exception {
        mvc.perform(get("/api/v2/crm/reports/receipts").with(jwt().jwt(token -> token.issuer("https://issuer.example")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        verifyNoInteractions(refunds);
    }

    @Test void cashFlowReportExposesGrossRefundAndNetValues() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant until = Instant.parse("2026-10-31T00:00:00Z");
        var report = new CrmDtos.CashFlowReport(List.of(), 0, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, from, until, Instant.now(), 1, 25);
        when(refunds.cashFlowReport(actor(), from, until, 1, 25)).thenReturn(report);
        mvc.perform(get("/api/v2/crm/reports/cash-flow")
            .param("fromAt", from.toString()).param("untilAt", until.toString())
            .param("page", "1").param("size", "25")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.netCashFlow").value(0));
        verify(refunds).cashFlowReport(actor(), from, until, 1, 25);
    }
}
