package cn.siyue.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit opt-in and source checkpoint settings for legacy identity migration. */
@ConfigurationProperties(prefix = "siyue.auth.legacy-identity-migration")
public record LegacyIdentityMigrationProperties(boolean enabled, String sourceSha256, String checkpoint,
        String placeholderIssuer, String trustedIssuer, String trustedManifestJson) { }
