package cn.siyue.core;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Stable DTOs for explicitly provisioned employee identities and database grants. */
public final class AccessAdminDtos {
    private AccessAdminDtos() { }

    /** Request to create one OIDC subject mapping. */
    public record CreateInput(
        @NotBlank @Size(max = 120) @Pattern(regexp = "[A-Za-z0-9_-]+") String principalId,
        @NotBlank @Size(max = 1024) String issuer,
        @NotBlank @Size(max = 512) String subject,
        @NotBlank @Pattern(regexp = "all|campus|assigned|own") String scope,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> permissionCodes,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> deniedPermissionCodes,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 120) String> campusIds) { }

    /** Versioned update of an existing employee's scope and grants. */
    public record UpdateInput(
        @NotNull Long expectedVersion,
        @NotBlank @Pattern(regexp = "all|campus|assigned|own") String scope,
        @NotNull Boolean active,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> permissionCodes,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> deniedPermissionCodes,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 120) String> campusIds) { }

    /** Safe employee access view; OIDC issuer and subject are intentionally omitted. */
    public record PrincipalView(
        String principalId, String scope, boolean active, long version, boolean credentialChangeRequired,
        List<String> permissionCodes, List<String> effectivePermissionCodes, List<String> deniedPermissionCodes,
        List<String> campusIds) { }
}
