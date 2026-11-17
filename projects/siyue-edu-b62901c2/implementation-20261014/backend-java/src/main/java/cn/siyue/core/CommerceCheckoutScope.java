package cn.siyue.core;

/** Shared creator and complete-campus visibility for checkout records. */
final class CommerceCheckoutScope {
    private CommerceCheckoutScope() { }

    static String sql(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> "1=1";
            case "campus" -> "EXISTS (SELECT 1 FROM core_commerce_checkout_campus cc "
                + "WHERE cc.checkout_id=p.id AND cc.deleted=0 AND cc.campus_id IN (SELECT pc.campus_id "
                + "FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.deleted=0)) "
                + "AND NOT EXISTS (SELECT 1 FROM core_commerce_checkout_campus cc WHERE cc.checkout_id=p.id "
                + "AND cc.deleted=0 AND cc.campus_id NOT IN (SELECT pc.campus_id FROM core_principal_campus pc "
                + "WHERE pc.principal_id=? AND pc.deleted=0))";
            case "own", "assigned" -> "p.created_by_id=?";
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }

    static Object[] args(Actor actor) {
        return switch (actor.scope()) {
            case "own", "assigned" -> new Object[] {actor.id()};
            case "campus" -> new Object[] {actor.id(), actor.id()};
            default -> new Object[0];
        };
    }
}
