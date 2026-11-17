package cn.siyue.core;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Fixed promotion-link inputs and scoped employee responses; public clients never supply attribution names. */
public final class CampaignPromotionDtos {
    private CampaignPromotionDtos() { }
    /** Client intent; server resolves publication state, promoter label and all internal identifiers. */
    public record Create(@NotBlank @Size(max=120) String formId,@NotBlank @Size(max=120) String promoterId,
            @NotBlank @Size(max=200) String name,@Min(1) long expectedVersion) { }
    /** Immutable link projection, with a relative same-origin canonical public form URL. */
    public record Link(String id,String name,String formId,String promoterId,String promoterName,
            Instant createdAt,String createdBy,String url) { }
    /** Scoped choice; label is a retained name or an explicitly marked account fallback. */
    public record Choice(String id,String label) { }
    /** Employee-visible links and creation choices at the supplied activity version. */
    public record Page(List<Link> items,long version,List<Choice> forms,List<Choice> promoters) { }
    /** Exact receipt retained for replay; version advances once for the created link. */
    public record Created(Link link,long version) { }
}
