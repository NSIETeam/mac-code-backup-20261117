package cn.siyue.core;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Query-string filters for a scoped opportunity page. */
public record OpportunityListFilters(
        @Size(max = 120) String customerId,
        @Size(max = 20) String stage,
        @Min(1) @Max(100000) Integer page,
        @Min(1) @Max(100) Integer size) {
    /** 仅为省略的分页参数填充默认值；合法范围由Bean Validation和服务检查。
     * @param customerId 可选客户公开标识，最多120字符
     * @param stage 可选商机阶段，最多20字符
     * @param page 省略时采用1；允许范围1至100000
     * @param size 省略时采用50；允许范围1至100
     */
    public OpportunityListFilters {
        page = page == null ? 1 : page;
        size = size == null ? 50 : size;
    }
}
