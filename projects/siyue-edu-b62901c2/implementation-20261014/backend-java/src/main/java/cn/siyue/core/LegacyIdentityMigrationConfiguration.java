package cn.siyue.core;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers opt-in identity-migration properties. */
@Configuration
@EnableConfigurationProperties({LegacyIdentityMigrationProperties.class, CredentialChangeProperties.class,
    CredentialAdminResetProperties.class})
public class LegacyIdentityMigrationConfiguration { }
