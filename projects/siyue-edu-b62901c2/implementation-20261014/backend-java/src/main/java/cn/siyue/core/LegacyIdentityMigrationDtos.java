package cn.siyue.core;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request shape for the explicitly enabled legacy identity bind operation. */
public final class LegacyIdentityMigrationDtos {
    private LegacyIdentityMigrationDtos() { }

    public record BindInput(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,120}") String legacyUserId,
        @NotNull @Min(1) Long expectedVersion,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sourceSha256,
        @NotBlank @Size(max = 1024) String issuer,
        @NotBlank @Size(max = 512) String subject,
        @NotBlank @Pattern(regexp = "all|campus|assigned|own") String scope,
        @NotNull Boolean active,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> sourcePermissionCodes,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> permissionCodes,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 120) String> campusIds,
        @NotNull @Size(max = 16) List<@NotBlank @Size(max = 120) String> legacyRoleIds,
        @NotNull @Size(max = 16) List<@NotBlank @Size(max = 120) String> positionIds,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> deniedPermissionCodes,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> sourceDirectPermissionCodes,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> sourceDeniedPermissionCodes,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> expectedEffectivePermissionCodes,
        @NotBlank @Pattern(regexp = "siyue-permission-map-v[0-9]+") String permissionMappingVersion,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sourceRoleFingerprint,
        @NotNull Boolean mustChangePassword, @Size(max = 128) String credentialChangeTicket,
        @Valid DirectoryProfile directory) { }

    /** Original directory facts, authenticated by the private source manifest rather than token claims. */
    public record DirectoryProfile(@NotBlank @Size(max = 120) String displayName,
            @Size(max = 40) String employeeNo, @Size(max = 40) String archivedAt,
            @Size(max = 120) String archivedBy, @Size(max = 500) String archiveReason, @NotBlank @Size(max = 48) String username) { }

    /** Request to create or verify one disabled, permissionless stable-ID placeholder. */
    public record PrepareInput(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,120}") String legacyUserId,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sourceSha256) { }
}
