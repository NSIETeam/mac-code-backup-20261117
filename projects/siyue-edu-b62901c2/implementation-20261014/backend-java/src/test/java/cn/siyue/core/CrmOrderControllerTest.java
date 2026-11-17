package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmOrder;
import cn.siyue.core.CrmDtos.CrmReceipt;
import cn.siyue.core.CrmDtos.OrderInput;
import cn.siyue.core.CrmDtos.OrderPage;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CrmOrderController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class CrmOrderControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private CrmOrderService service;
    private Actor actor() { return new Actor(5, "staff-5", "own", Set.of(), Set.of("crm.order.read")); }

    @Test void listUsesBoundedQueryAndCreateRequiresIdempotency() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        when(service.list(actor(), "OPP_1", 1, 20)).thenReturn(new OrderPage(List.of(), 0, 1, 20));
        mvc.perform(get("/api/v2/crm/orders?opportunityId=OPP_1&page=1&size=20")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        var input = new OrderInput("OPP_1", new BigDecimal("125.00"), "秋季数学", 4);
        var order = new CrmOrder("ORD_1", "OPP_1", "CUS_1", null, "CAMPUS_1", "staff-5",
            "staff-5", "秋季数学", new BigDecimal("125.00"), BigDecimal.ZERO, BigDecimal.ZERO,
            "none", "pending", 1, null);
        when(service.create(actor(), "crm-order-create-key-1234", input)).thenReturn(order);
        mvc.perform(post("/api/v2/crm/orders")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com").header("Idempotency-Key", "crm-order-create-key-1234")
            .contentType("application/json").content("""
                {"opportunityId":"OPP_1","totalAmount":125.00,"productSnapshot":"秋季数学","expectedOpportunityVersion":4}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.attributedOwnerId").value("staff-5"))
            .andExpect(jsonPath("$.data.totalAmount").isString())
            .andExpect(jsonPath("$.data.totalAmount").value("125.00"))
            .andExpect(jsonPath("$.data.receivedAmount").value("0"))
            .andExpect(jsonPath("$.data.refundedAmount").value("0"));
        verify(service).create(actor(), "crm-order-create-key-1234", input);
    }

    @Test void crmPaymentLinkReturnsProviderUnavailableWithoutUsingCheckoutRoute() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        doThrow(new BusinessException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "支付渠道未配置"))
            .when(service).paymentLink(actor(), "ORD_1");
        mvc.perform(post("/api/v2/crm/orders/ORD_1/payment-link")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com").header("Idempotency-Key", "crm-payment-link-key-1234"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("PAYMENT_PROVIDER_UNAVAILABLE"));
        verify(service).paymentLink(actor(), "ORD_1");
        verifyNoMoreInteractions(service);
    }

    @Test void customerOrderHistoryUsesASeparateScopedReadRoute() throws Exception {
        Actor customerActor = new Actor(5, "staff-5", "own", Set.of(), Set.of("crm.order.read", "crm.customer.read"));
        when(access.resolve("https://issuer.example", "subject")).thenReturn(customerActor);
        when(service.customerOrders(customerActor, "CUS_1", 2, 20)).thenReturn(new OrderPage(List.of(), 0, 2, 20));
        mvc.perform(get("/api/v2/crm/orders/customer/CUS_1?page=2&size=20")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2));
        verify(service).customerOrders(customerActor, "CUS_1", 2, 20);
    }

    @Test void receiptRequiresMethodAndExpectedOrderVersion() throws Exception {
        mvc.perform(post("/api/v2/crm/orders/ORD_1/receipts")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example"))).header("Origin", "https://crm.example.com").header("Idempotency-Key", "crm-receipt-record-1234")
            .contentType("application/json").content("""
                {"amount":10,"method":"unknown","receivedAt":"2026-10-14T00:00:00Z","expectedOrderVersion":1}
                """))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void orderListPreservesMaximumAmountAsDecimalStrings() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        var maximum = new BigDecimal("9999999999999999.99");
        var order = new CrmOrder("ORD_1", "OPP_1", "CUS_1", null, "CAMPUS_1", "staff-5",
            "staff-5", "合成商品", maximum, maximum, maximum, "refunded", "received", 3, null);
        when(service.list(actor(), null, 1, 50)).thenReturn(new OrderPage(List.of(order), 1, 1, 50));
        mvc.perform(get("/api/v2/crm/orders")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].totalAmount").value("9999999999999999.99"))
            .andExpect(jsonPath("$.data.items[0].receivedAmount").value("9999999999999999.99"))
            .andExpect(jsonPath("$.data.items[0].refundedAmount").value("9999999999999999.99"));
    }

    @Test void receiptAcceptsDecimalStringAndReturnsExactMaximum() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        var maximum = new BigDecimal("9999999999999999.99");
        var input = new CrmDtos.ReceiptInput(maximum, "cash", null, Instant.EPOCH, 1);
        when(service.receive(actor(), "ORD_1", "crm-receipt-record-1234", input))
            .thenReturn(new CrmReceipt("RCT_1", "ORD_1", "staff-5", maximum, "cash", null, Instant.EPOCH));
        mvc.perform(post("/api/v2/crm/orders/ORD_1/receipts")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com").header("Idempotency-Key", "crm-receipt-record-1234")
            .contentType("application/json").content("""
                {"amount":"9999999999999999.99","method":"cash","receivedAt":"1970-01-01T00:00:00Z","expectedOrderVersion":1}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderId").value("ORD_1"))
            .andExpect(jsonPath("$.data.amount").isString())
            .andExpect(jsonPath("$.data.amount").value("9999999999999999.99"));
        verify(service).receive(actor(), "ORD_1", "crm-receipt-record-1234", input);
    }
    @Test void classQuoteRouteKeepsFeesExactAndRequiresAnOpportunity() throws Exception {
        when(access.resolve("https://issuer.example","subject")).thenReturn(actor());
        var fees=new cn.siyue.core.AcademicDtos.ClassFees(new BigDecimal("300.00"),new BigDecimal("20.00"),"unused_lessons",null,"after_start");
        var quote=new CrmDtos.ClassQuote(new CrmDtos.ClassOrderTerms("CL_1","班级","C_1",1,fees),new BigDecimal("320.00"));
        when(service.classQuotes(actor(),"OPP_1",1,20)).thenReturn(new CrmDtos.ClassQuotePage(List.of(quote),1,1,20));
        mvc.perform(get("/api/v2/crm/orders/class-quotes").param("opportunityId","OPP_1")
            .with(jwt().jwt(token->token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].totalAmount").value("320.00"))
            .andExpect(jsonPath("$.data.items[0].terms.fees.courseFee").value("300.00"));
        mvc.perform(get("/api/v2/crm/orders/class-quotes")
            .with(jwt().jwt(token->token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com")).andExpect(status().isBadRequest());
        verify(service).classQuotes(actor(),"OPP_1",1,20);
    }

}
