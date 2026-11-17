package cn.siyue.core;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Validated compatibility contract for imported Node CRM saved views. */
public record LegacySavedViewQuery(
        @Pattern(regexp = "customers|leads|opportunities|crmTasks|serviceTickets") String resource,
        @Pattern(regexp = "mine|team") String scope,
        @Pattern(regexp = "all") String queue,
        @Size(max = 200) String search,
        @Pattern(regexp = "updatedAt:desc") String sort,
        @Pattern(regexp = "createdAt") String dateField) {

    /** Validates the legacy view against the route and current team grant before a list read. */
    public void authorize(Actor actor, String expectedResource) {
        if (!present()) { return; }
        validateRoute(expectedResource);
        validateFixedFilters();
        authorizeScope(actor);
        validateSearch();
    }

    private void validateRoute(String expectedResource) {
        if (resource == null || !resource.equals(expectedResource)
                || scope == null || queue == null || sort == null || dateField == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "保存视图筛选条件不完整或资源不匹配");
        }
    }

    private void validateFixedFilters() {
        if (!"all".equals(queue) || !"updatedAt:desc".equals(sort) || !"createdAt".equals(dateField)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "此保存视图包含当前不支持的筛选选项");
        }
    }

    private void authorizeScope(Actor actor) {
        if ("team".equals(scope)) {
            if (!("all".equals(actor.scope()) || "campus".equals(actor.scope()))) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号没有团队范围查看权限");
            }
            actor.requireAny("crm.lead.assign", "lead.assign", "report.read");
        }
    }

    private void validateSearch() {
        if (search != null && search.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "保存视图搜索词不能为空");
        }
    }

    /** Returns the normalized substring as a bound LIKE pattern. */
    public String searchPattern() {
        if (search == null) { return null; }
        String normalized = java.text.Normalizer.normalize(search.trim(), java.text.Normalizer.Form.NFKC)
            .toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) { return null; }
        String escaped = normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    /** Indicates whether this request contains any imported-view parameters. */
    public boolean present() {
        return nonblank(resource) || nonblank(scope) || nonblank(queue) || nonblank(search)
            || nonblank(sort) || nonblank(dateField);
    }

    private boolean nonblank(String value) { return value != null && !value.isBlank(); }
}
