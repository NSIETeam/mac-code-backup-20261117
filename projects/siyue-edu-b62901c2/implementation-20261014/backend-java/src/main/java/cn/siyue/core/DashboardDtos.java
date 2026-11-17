package cn.siyue.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/** Permission-projected aggregate values for the employee home dashboard. */
public final class DashboardDtos {
    private DashboardDtos() { }

    /** Each nullable value is present only when the caller has that resource's read grant. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(Long leads, Long activeStudents, Long outstandingOrders, Conversion conversion) { }

    /** The caller's own conversion aggregate; no lead or order rows are returned. */
    public record Conversion(long assigned, long converted, BigDecimal rate, boolean lowSample, int minimumSample) { }
}
