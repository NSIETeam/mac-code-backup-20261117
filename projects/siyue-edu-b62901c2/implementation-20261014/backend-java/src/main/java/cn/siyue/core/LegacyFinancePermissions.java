package cn.siyue.core;

/** Preserves explicit legacy finance actions without granting unrelated native CRM permissions. */
final class LegacyFinancePermissions {
    private LegacyFinancePermissions() { }

    static void readOrders(Actor actor) {
        actor.requireAny("crm.order.read", "order.read", "order.confirmReceipt");
    }

    static boolean legacyOrderReader(Actor actor) {
        return actor.permissions().contains("order.read") || actor.permissions().contains("order.confirmReceipt");
    }

    static void receive(Actor actor) {
        if (actor.permissions().contains("order.confirmReceipt")) { return; }
        actor.require("crm.receipt.record");
        actor.require("crm.order.read");
    }

    static boolean legacyRefundReader(Actor actor) {
        return actor.permissions().contains("refund.read") || actor.permissions().contains("refund.approve")
            || actor.permissions().contains("refund.submit");
    }

    static void readRefunds(Actor actor) {
        if (!legacyRefundReader(actor)) { actor.require("crm.refund.read"); }
    }

    static void review(Actor actor) {
        if (actor.permissions().contains("refund.approve")) { return; }
        actor.require("crm.refund.approve");
        actor.require("crm.order.read");
    }

    static void settle(Actor actor) {
        if (actor.permissions().contains("refund.submit")) { return; }
        actor.require("crm.refund.record");
        actor.require("crm.order.read");
    }
}
