package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmRefundRequest;
import cn.siyue.core.CrmDtos.RefundRequestInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrmRefundServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final CrmRefundRepository refunds = mock(CrmRefundRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final CrmRefundService service = new CrmRefundService(access, refunds, mutations,
        new ObjectMapper().findAndRegisterModules());
    private final Actor actor = new Actor(5, "staff-5", "own", Set.of(),
        Set.of("crm.order.read", "crm.refund.request", "crm.refund.read"));

    @Test void legacyRefundReviewRechecksRevocationBeforeScopedWrite() {
        Actor legacy=new Actor(5,"staff-5","campus",Set.of(1L),Set.of("refund.approve"));
        when(access.lockActive(5)).thenReturn(new Actor(5,"staff-5","campus",Set.of(1L),Set.of("refund.read")));
        var input=new CrmDtos.RefundReviewInput("approve","核对通过",1);
        assertThrows(BusinessException.class,()->service.review(legacy,"REF_1","legacy-review-key-1234",input));
        verify(access).lockActive(5);
        verifyNoInteractions(refunds,mutations);
    }

    @Test void requestRequiresRefundPermissionAndPersistsIdempotently() throws Exception {
        when(access.lockActive(5)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("refund-request-hash");
        var input = new RefundRequestInput(new BigDecimal("25.00"), "课程未开始申请退款", "审批凭证编号 E-1");
        var result = new CrmRefundRequest("RFDREQ_1", "ORD_1", "staff-5", new BigDecimal("25.00"),
            "课程未开始申请退款", "审批凭证编号 E-1", "pending_approval", null, null, null, 1);
        when(refunds.request(actor, "ORD_1", input)).thenReturn(result);
        assertEquals(result, service.request(actor, "ORD_1", "refund-request-key-12345", input));
        verify(refunds).authorizeOrder(actor, "ORD_1");
        verify(mutations).complete(eq(actor), argThat(write -> write.action().equals("crm.refund.request")
            && write.resourceId().equals("RFDREQ_1")));
        when(mutations.replay(actor, "refund-request-key-12345", "crm.refund.request", "refund-request-hash"))
            .thenReturn(new ObjectMapper().findAndRegisterModules().writeValueAsString(result));
        assertEquals(result, service.request(actor, "ORD_1", "refund-request-key-12345", input));
        verify(refunds, times(1)).request(actor, "ORD_1", input);
        Actor noRefund = new Actor(8, "staff-8", "own", Set.of(), Set.of("crm.order.read"));
        assertThrows(BusinessException.class, () -> service.request(noRefund, "ORD_1",
            "refund-request-key-56789", input));
    }

    @Test void readQueueRequiresRefundReadGrantAndBoundedPagination() {
        when(refunds.requests(actor, "ORD_1", 1, 20)).thenReturn(
            new CrmDtos.RefundRequestPage(java.util.List.of(), 0, 1, 20));
        assertEquals(0, service.requests(actor, "ORD_1", 1, 20).total());
        assertThrows(BusinessException.class, () -> service.requests(actor, "ORD_1", 1, 101));
        Actor noRead = new Actor(9, "staff-9", "own", Set.of(), Set.of("crm.order.read"));
        assertThrows(BusinessException.class, () -> service.requests(noRead, "ORD_1", 1, 20));
        verify(refunds, never()).requests(noRead, "ORD_1", 1, 20);
    }
}
