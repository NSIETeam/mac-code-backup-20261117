package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

class LegacyFinancePermissionsTest {
    private Actor actor(String... permissions) {
        return new Actor(1,"finance-test","campus",Set.of(1L),Set.of(permissions));
    }

    @Test void legacyActionsRemainSeparate() {
        assertDoesNotThrow(() -> LegacyFinancePermissions.receive(actor("order.confirmReceipt")));
        assertDoesNotThrow(() -> LegacyFinancePermissions.review(actor("refund.approve")));
        assertDoesNotThrow(() -> LegacyFinancePermissions.settle(actor("refund.submit")));
        assertThrows(BusinessException.class,() -> LegacyFinancePermissions.receive(actor("order.read")));
        assertThrows(BusinessException.class,() -> LegacyFinancePermissions.review(actor("refund.read")));
        assertThrows(BusinessException.class,() -> LegacyFinancePermissions.settle(actor("refund.approve")));
        assertThrows(BusinessException.class,() -> LegacyFinancePermissions.review(actor("refund.submit")));
        assertThrows(BusinessException.class,() -> LegacyFinancePermissions.receive(actor("refund.submit")));
    }

    @Test void nativeMutationGrantsStillNeedNativeOrderRead() {
        assertThrows(BusinessException.class,() -> LegacyFinancePermissions.receive(actor("crm.receipt.record")));
        assertThrows(BusinessException.class,() -> LegacyFinancePermissions.review(actor("crm.refund.approve")));
        assertThrows(BusinessException.class,() -> LegacyFinancePermissions.settle(actor("crm.refund.record")));
        assertThrows(BusinessException.class,() -> LegacyFinancePermissions.review(actor("crm.refund.approve","order.read")));
        assertDoesNotThrow(() -> LegacyFinancePermissions.receive(actor("crm.receipt.record","crm.order.read")));
        assertDoesNotThrow(() -> LegacyFinancePermissions.review(actor("crm.refund.approve","crm.order.read")));
        assertDoesNotThrow(() -> LegacyFinancePermissions.settle(actor("crm.refund.record","crm.order.read")));
    }

    @Test void readGrantsDoNotCrossFinancialResources() {
        assertDoesNotThrow(() -> LegacyFinancePermissions.readOrders(actor("order.read")));
        assertDoesNotThrow(() -> LegacyFinancePermissions.readOrders(actor("order.confirmReceipt")));
        for(String permission:Set.of("refund.read","refund.approve","refund.submit","crm.refund.read")) {
            assertDoesNotThrow(() -> LegacyFinancePermissions.readRefunds(actor(permission)));
            assertThrows(BusinessException.class,() -> LegacyFinancePermissions.readOrders(actor(permission)));
        }
        assertThrows(BusinessException.class,() -> LegacyFinancePermissions.readRefunds(actor("order.read")));
        assertThrows(BusinessException.class,() -> LegacyFinancePermissions.readOrders(actor()));
    }
}
