package cn.siyue.core;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Bounded workflow/interface feedback; business record values never belong in locators. */
public final class UiAnnotationDtos {
    private UiAnnotationDtos() { }
    /** Original request and both flow definitions are retained together, encrypted at rest. */
    public record Create(
            @NotBlank @Size(max=256) @Pattern(regexp="/crm/.*") String page,
            @NotBlank @Size(max=120) String pageTitle,
            @NotBlank @Pattern(regexp="button|link|input|select|textarea|table-cell|table-row|table-header|tab|label|card|navigation|heading|other") String elementKind,
            @NotBlank @Size(max=200) String elementLabel,
            @NotBlank @Size(max=500) String selector,
            @NotBlank @Size(max=4000) String issue,
            @NotBlank @Pattern(regexp="interface|workflow") String category,
            @Size(max=120) String workflowName,
            @Size(max=6000) String flowDefinition,
            @Size(max=6000) String desiredFlowDefinition) { }
    /** Completion is explicit and version checked; merely reading does not resolve a request. */
    public record Update(@NotBlank @Pattern(regexp="open|resolved") String status,
                         @Min(1) long expectedVersion, boolean verified) { }
    /** Public identifiers only; encrypted feedback is returned only after administrator checks. */
    public record View(String id, String status, long version, String createdBy,
                       Instant createdAt, Instant updatedAt, Create request, String createdByName) { }
    /** Deterministic bounded queue with count for complete Agent traversal. */
    public record Page(List<View> annotations, long total, int page, int size) { }
}
