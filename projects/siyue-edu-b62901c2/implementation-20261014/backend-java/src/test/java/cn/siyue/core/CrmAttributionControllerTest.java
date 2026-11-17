package cn.siyue.core;

import cn.siyue.core.CrmDtos.AttributionRequestInput;
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

@WebMvcTest(CrmAttributionController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class CrmAttributionControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private CrmAttributionService service;
    private Actor actor() { return new Actor(5, "staff-5", "own", Set.of(), Set.of("crm.order.read")); }

    @Test void requestRequiresAProposedSellerAndIdempotencyKey() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        var input = new AttributionRequestInput("staff-6", "订单归属录入错误", null);
        var result = new CrmDtos.AttributionRequest("ATTRREQ_1", "ORD_1", "staff-5", "staff-5", "staff-5",
            "staff-6", null, null, "pending_approval", null, null, null, 1);
        when(service.request(actor(), "ORD_1", "attribution-request-key-1234", input)).thenReturn(result);
        mvc.perform(post("/api/v2/crm/orders/ORD_1/attribution-requests")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com").header("Idempotency-Key", "attribution-request-key-1234")
            .contentType("application/json").content("""
                {"proposedOwnerId":"staff-6","reason":"订单归属录入错误"}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("pending_approval"));
        verify(service).request(actor(), "ORD_1", "attribution-request-key-1234", input);
    }

    @Test void invalidReviewDecisionIsRejectedBeforeServiceCall() throws Exception {
        mvc.perform(put("/api/v2/crm/attribution-requests/ATTRREQ_1/review")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example"))).header("Origin", "https://crm.example.com").header("Idempotency-Key", "attribution-review-key-1234")
            .contentType("application/json").content("""
                {"decision":"pending","note":"审核说明","expectedVersion":1}
                """))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
