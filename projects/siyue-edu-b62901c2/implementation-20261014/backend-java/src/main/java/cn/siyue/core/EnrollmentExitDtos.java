package cn.siyue.core;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Explicit academic exit requests and their committed relational outcomes. */
public final class EnrollmentExitDtos {
    private EnrollmentExitDtos() { }

    /** Requires the version displayed with the enrollment to prevent stale exits. */
    public record Input(@NotBlank @Size(max = 500) String reason,
            @NotNull @Min(1) Long expectedVersion) { }

    /** Records withdrawal separately from any optional refund request. */
    public record Receipt(String enrollmentId, String orderId, String status,
            BigDecimal refundableAmount, String refundRequestId,
            int cancelledAdjustments, long version, Instant occurredAt) { }

    /** Cancels every enrollment tied to one accepted, unpaid checkout as one transaction. */
    public record CheckoutCancelInput(@NotBlank @Size(max = 500) String reason,
            @NotNull @Min(1) Long expectedVersion) { }

    /** Contains every order-level cancellation performed by one checkout command. */
    public record CheckoutCancelReceipt(String checkoutId, String status,
            List<Receipt> enrollments, long version, Instant occurredAt) { }
}
