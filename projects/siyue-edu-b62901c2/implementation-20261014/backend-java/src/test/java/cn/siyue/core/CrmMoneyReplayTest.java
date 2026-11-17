package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Old encrypted numeric responses remain replayable after response-only money serialization changes. */
class CrmMoneyReplayTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AccessRepository access = mock(AccessRepository.class);
    private final CrmOrderRepository orders = mock(CrmOrderRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final CrmOrderService service = new CrmOrderService(access, orders, mutations, mapper);
    private final Actor actor = new Actor(5, "staff-5", "own", Set.of(),
        Set.of("crm.order.write", "crm.opportunity.read", "crm.receipt.record", "crm.order.read"));

    @Test void legacyNumericOrderResponseReplaysWithoutNewWriteAndSerializesExactly() throws Exception {
        when(access.lockActive(actor.id())).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("legacy-order-hash");
        when(mutations.replay(actor, "legacy-order-money-key", "crm.order.create", "legacy-order-hash"))
            .thenReturn("""
                {"id":"ORD_1","opportunityId":"OPP_1","customerId":"CUS_1","studentId":null,
                 "campusId":"CAMPUS_1","attributedOwnerId":"staff-5","currentAttributionOwnerId":"staff-5",
                 "productSnapshot":"合成商品","totalAmount":9999999999999999.99,"receivedAmount":0.00,
                 "refundedAmount":0,"refundStatus":"none","status":"pending","version":1}
                """);
        var input = new CrmDtos.OrderInput("OPP_1", new BigDecimal("9999999999999999.99"), "合成商品", 4);
        var result = service.create(actor, "legacy-order-money-key", input);
        assertEquals(input.totalAmount(), result.totalAmount());
        var json = mapper.readTree(mapper.writeValueAsString(result));
        assertTrue(json.get("totalAmount").isTextual());
        assertEquals("9999999999999999.99", json.get("totalAmount").textValue());
        assertEquals("0.00", json.get("receivedAmount").textValue());
        assertEquals("0", json.get("refundedAmount").textValue());
        verify(orders).authorizeOpportunity(actor, "OPP_1");
        verify(orders, never()).create(any(), any());
        verify(mutations, never()).complete(any(), any());
    }

    @Test void legacyNumericReceiptResponseReplaysWithExactAmountAndOriginalTime() throws Exception {
        when(access.lockActive(actor.id())).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("legacy-receipt-hash");
        when(mutations.replay(actor, "legacy-receipt-money-key", "crm.receipt.record", "legacy-receipt-hash"))
            .thenReturn("""
                {"id":"RCT_1","orderId":"ORD_1","actorId":"staff-5","amount":9999999999999999.99,
                 "method":"cash","reference":null,"receivedAt":"2000-01-01T00:00:42.123Z"}
                """);
        var input = new CrmDtos.ReceiptInput(new BigDecimal("9999999999999999.99"), "cash", null,
            Instant.parse("2000-01-01T00:00:42.123Z"), 1);
        var result = service.receive(actor, "ORD_1", "legacy-receipt-money-key", input);
        assertEquals(input.amount(), result.amount());
        assertEquals(input.receivedAt(), result.receivedAt());
        var json = mapper.readTree(mapper.writeValueAsString(result));
        assertTrue(json.get("amount").isTextual());
        assertEquals("9999999999999999.99", json.get("amount").textValue());
        verify(orders).authorize(actor, "ORD_1");
        verify(orders, never()).receive(any(), anyString(), any());
        verify(mutations, never()).complete(any(), any());
    }

    @Test void requestMoneySerializationKeepsTheOriginalNumericHashInput() throws Exception {
        var order = new CrmDtos.OrderInput("OPP_1", new BigDecimal("125.00"), "合成商品", 4);
        assertEquals("{\"opportunityId\":\"OPP_1\",\"totalAmount\":125.00,\"productSnapshot\":\"合成商品\","
            + "\"expectedOpportunityVersion\":4}", mapper.writeValueAsString(order));
        var receipt = new CrmDtos.ReceiptInput(new BigDecimal("25.00"), "cash", null, Instant.EPOCH, 1);
        assertTrue(mapper.readTree(mapper.writeValueAsString(receipt)).get("amount").isNumber());
    }
}
