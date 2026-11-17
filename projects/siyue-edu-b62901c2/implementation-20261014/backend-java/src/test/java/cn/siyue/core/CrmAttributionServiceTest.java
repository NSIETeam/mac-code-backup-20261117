package cn.siyue.core;

import cn.siyue.core.CrmDtos.AttributionRequest;
import cn.siyue.core.CrmDtos.AttributionRequestInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrmAttributionServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final CrmAttributionRepository attribution = mock(CrmAttributionRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final CrmAttributionService service = new CrmAttributionService(access, attribution, mutations,
        new ObjectMapper().findAndRegisterModules());
    private final Actor actor = new Actor(5, "staff-5", "own", Set.of(),
        Set.of("crm.order.read", "crm.attribution.correct.request"));

    @Test void requestKeepsSnapshotAndStoresAnIdempotentCorrection() throws Exception {
        when(access.lockActive(5)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("attribution-request-hash");
        var input = new AttributionRequestInput("staff-6", "订单归属误录", "原始审批记录 A-1");
        var result = new AttributionRequest("ATTRREQ_1", "ORD_1", "staff-5", "staff-5", "staff-5",
            "staff-6", "订单归属误录", "原始审批记录 A-1", "pending_approval", null, null, null, 1);
        when(attribution.request(actor, "ORD_1", input)).thenReturn(result);
        assertEquals(result, service.request(actor, "ORD_1", "attribution-request-key-1234", input));
        verify(attribution).authorizeOrder(actor, "ORD_1");
        verify(mutations).complete(eq(actor), argThat(write -> write.action().equals("crm.attribution.correct.request")
            && write.resourceId().equals("ATTRREQ_1")));
        when(mutations.replay(actor, "attribution-request-key-1234", "crm.attribution.correct.request",
            "attribution-request-hash")).thenReturn(new ObjectMapper().findAndRegisterModules().writeValueAsString(result));
        assertEquals(result, service.request(actor, "ORD_1", "attribution-request-key-1234", input));
        verify(attribution, times(1)).request(actor, "ORD_1", input);
    }

    @Test void reviewRequiresSeparateApprovalPermission() {
        var input = new CrmDtos.AttributionReviewInput("approve", "已核验", 1);
        assertThrows(BusinessException.class, () -> service.review(actor, "ATTRREQ_1",
            "attribution-review-key-1234", input));
        verifyNoInteractions(access, attribution, mutations);
    }
}
