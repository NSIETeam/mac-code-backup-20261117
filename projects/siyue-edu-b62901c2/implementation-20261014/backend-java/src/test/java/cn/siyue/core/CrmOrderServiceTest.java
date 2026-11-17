package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmOrder;
import cn.siyue.core.CrmDtos.CrmReceipt;
import cn.siyue.core.CrmDtos.OrderInput;
import cn.siyue.core.CrmDtos.ReceiptInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrmOrderServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final CrmOrderRepository orders = mock(CrmOrderRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final CrmOrderService service = new CrmOrderService(access, orders, mutations,
        new ObjectMapper().findAndRegisterModules());
    private final Actor actor = new Actor(5, "staff-5", "own", Set.of(),
        Set.of("crm.order.write", "crm.order.read", "crm.opportunity.read", "crm.receipt.record", "report.read"));

    @Test void legacyReceiptGrantRechecksRevocationBeforeOrderOrReplay() {
        Actor legacy=new Actor(5,"staff-5","campus",Set.of(1L),Set.of("order.confirmReceipt"));
        when(access.lockActive(5)).thenReturn(new Actor(5,"staff-5","campus",Set.of(1L),Set.of("order.read")));
        var input=new ReceiptInput(new BigDecimal("25.00"),"bank_transfer",null,Instant.EPOCH,2);
        assertThrows(BusinessException.class,()->service.receive(legacy,"ORD_1","legacy-receipt-key-1234",input));
        verify(access).lockActive(5);
        verifyNoInteractions(orders,mutations);
    }

    @Test void paymentLinkRequiresExactPermissionAndVisibleCrmOrderWithoutWriting() {
        BusinessException denied = assertThrows(BusinessException.class, () -> service.paymentLink(actor, "ORD_1"));
        assertEquals(ErrorCode.FORBIDDEN, denied.code());
        verifyNoInteractions(orders, mutations);
        Actor allowed = new Actor(actor.id(), actor.publicId(), "own", Set.of(), Set.of("order.paymentLink"));
        BusinessException unavailable = assertThrows(BusinessException.class, () -> service.paymentLink(allowed, "ORD_1"));
        assertEquals(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, unavailable.code());
        verify(orders).authorize(allowed, "ORD_1");
        doThrow(new BusinessException(ErrorCode.NOT_FOUND, "订单不可见")).when(orders).authorize(allowed, "ORD_OTHER");
        BusinessException hidden = assertThrows(BusinessException.class, () -> service.paymentLink(allowed, "ORD_OTHER"));
        assertEquals(ErrorCode.NOT_FOUND, hidden.code());
        verifyNoInteractions(mutations);
    }

    @Test void createSnapshotsSellerAndUsesIdempotency() throws Exception {
        when(access.lockActive(5)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("order-hash");
        var input = new OrderInput("OPP_1", new BigDecimal("125.00"), "秋季数学课程", 4);
        var order = order();
        when(orders.create(actor, input)).thenReturn(order);
        assertEquals(order, service.create(actor, "crm-order-create-key-1234", input));
        verify(mutations).complete(eq(actor), argThat(write -> write.action().equals("crm.order.create")
            && write.resourceId().equals("ORD_1")));
        when(mutations.replay(actor, "crm-order-create-key-1234", "crm.order.create", "order-hash"))
            .thenReturn(new ObjectMapper().findAndRegisterModules().writeValueAsString(order));
        assertEquals(order, service.create(actor, "crm-order-create-key-1234", input));
        verify(orders, times(1)).create(actor, input);
        verify(orders, times(2)).authorizeOpportunity(actor, "OPP_1");
    }

    @Test void successfulOrderReplayOnlyNeedsCurrentVisibilityNotNewOrderEligibility() throws Exception {
        when(access.lockActive(actor.id())).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("order-hash");
        var input = new OrderInput("OPP_1", new BigDecimal("125.00"), "秋季数学课程", 4);
        when(mutations.replay(actor, "crm-order-create-key-1234", "crm.order.create", "order-hash"))
            .thenReturn(new ObjectMapper().findAndRegisterModules().writeValueAsString(order()));
        when(orders.create(actor, input)).thenThrow(new BusinessException(ErrorCode.ALREADY_EXISTS, "已有订单"));
        assertEquals(order(), service.create(actor, "crm-order-create-key-1234", input));
        var sequence = inOrder(access, orders, mutations);
        sequence.verify(access).lockActive(actor.id());
        sequence.verify(orders).authorizeOpportunity(actor, "OPP_1");
        sequence.verify(mutations).hash(anyString());
        sequence.verify(mutations).replay(actor, "crm-order-create-key-1234", "crm.order.create", "order-hash");
        verify(orders, never()).create(any(), any());
        verify(mutations, never()).complete(any(), any());
    }

    @Test void narrowingCampusScopeDeniesOrderReplayBeforeReadingItsStoredResult() {
        var narrowed = new Actor(actor.id(), actor.publicId(), "campus", Set.of(99L), actor.permissions());
        when(access.lockActive(actor.id())).thenReturn(narrowed);
        doThrow(new BusinessException(ErrorCode.NOT_FOUND, "商机不在当前授权校区"))
            .when(orders).authorizeOpportunity(narrowed, "OPP_1");
        assertThrows(BusinessException.class, () -> service.create(actor, "crm-order-create-key-1234",
            new OrderInput("OPP_1", new BigDecimal("125.00"), "秋季数学课程", 4)));
        verify(orders).authorizeOpportunity(narrowed, "OPP_1");
        verify(orders, never()).create(any(), any());
        verifyNoInteractions(mutations);
    }

    @Test void revokedOrderWritePermissionPreventsBothAuthorizationAndReplay() {
        var revoked = new Actor(actor.id(), actor.publicId(), "own", Set.of(), Set.of("crm.opportunity.read"));
        when(access.lockActive(actor.id())).thenReturn(revoked);
        assertThrows(BusinessException.class, () -> service.create(actor, "crm-order-create-key-1234",
            new OrderInput("OPP_1", new BigDecimal("125.00"), "秋季数学课程", 4)));
        verifyNoInteractions(orders, mutations);
    }

    @Test void receivedLedgerRequiresFinanceGrantAndExpectedVersion() throws Exception {
        when(access.lockActive(5)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("receipt-hash");
        when(orders.receive(eq(actor), eq("ORD_1"), any())).thenReturn(new CrmReceipt("RCT_1", "ORD_1",
            "staff-5", new BigDecimal("25.00"), "bank_transfer", null, Instant.EPOCH));
        var input = new ReceiptInput(new BigDecimal("25.00"), "bank_transfer", null, Instant.EPOCH, 2);
        var result = service.receive(actor, "ORD_1", "receipt-record-key-1234", input);
        assertEquals(new BigDecimal("25.00"), result.amount());
        verify(orders).authorize(actor, "ORD_1");
        Actor noFinance = new Actor(8, "staff-8", "own", Set.of(), Set.of("crm.order.read"));
        assertThrows(BusinessException.class, () -> service.receive(noFinance, "ORD_1",
            "receipt-record-key-5678", input));
        verify(access, times(1)).lockActive(5);
    }

    @Test void pageAndReceiptPermissionsAreExplicit() {
        when(orders.list(actor, null, 1, 50)).thenReturn(new CrmDtos.OrderPage(java.util.List.of(), 0, 1, 50));
        assertEquals(0, service.list(actor, null, 1, 50).total());
        verify(orders).list(actor, null, 1, 50);
        assertThrows(BusinessException.class, () -> service.list(actor, null, 0, 50));
        assertThrows(BusinessException.class, () -> service.receipts(
            new Actor(9, "staff-9", "own", Set.of(), Set.of()), "ORD_1"));
        verify(orders, never()).receipts(any(), anyString());
    }

    @Test void customerHistoryRequiresBothCustomerAndOrderReadAndBoundsPaging() {
        Actor reader = new Actor(12, "staff-12", "own", Set.of(), Set.of("crm.order.read", "crm.customer.read"));
        when(orders.listForCustomer(reader, "CUS_1", 2, 20)).thenReturn(new CrmDtos.OrderPage(java.util.List.of(), 0, 2, 20));
        assertEquals(2, service.customerOrders(reader, "CUS_1", 2, 20).page());
        verify(orders).listForCustomer(reader, "CUS_1", 2, 20);
        clearInvocations(orders);
        Actor noCustomerRead = new Actor(13, "staff-13", "own", Set.of(), Set.of("crm.order.read"));
        assertThrows(BusinessException.class, () -> service.customerOrders(noCustomerRead, "CUS_1", 1, 20));
        assertThrows(BusinessException.class, () -> service.customerOrders(reader, "CUS_1", 1, 101));
        verify(orders, never()).listForCustomer(any(), anyString(), anyInt(), anyInt());
    }

    @Test void receiptReportRequiresGrantAndCapsPeriod() {
        var from = Instant.parse("2026-01-01T00:00:00Z");
        var until = from.plusSeconds(3600);
        var report = new CrmDtos.ReceiptReport(java.util.List.of(), 0, BigDecimal.ZERO,
            from, until, Instant.now(), 1, 50);
        when(orders.receiptReport(actor, from, until, 1, 50)).thenReturn(report);
        assertEquals(report, service.receiptReport(actor, from, until, 1, 50));
        assertThrows(BusinessException.class, () -> service.receiptReport(actor, from,
            from.plusSeconds(366L * 86400 + 1), 1, 50));
        assertThrows(BusinessException.class, () -> service.receiptReport(
            new Actor(9, "staff-9", "own", Set.of(), Set.of()), from, until, 1, 50));
        verify(orders).receiptReport(actor, from, until, 1, 50);
    }

    private CrmOrder order() {
        return new CrmOrder("ORD_1", "OPP_1", "CUS_1", "STU_1", "CAMPUS_1", "staff-5",
            "staff-5", "秋季数学课程", new BigDecimal("125.00"), BigDecimal.ZERO, BigDecimal.ZERO,
            "none", "pending", 1, null);
    }
}
