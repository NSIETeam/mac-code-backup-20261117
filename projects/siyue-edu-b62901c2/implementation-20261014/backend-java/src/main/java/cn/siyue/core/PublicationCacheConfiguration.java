package cn.siyue.core;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the optional immutable teaching snapshot cache. */
@Configuration
@EnableConfigurationProperties(PublicationCacheProperties.class)
public class PublicationCacheConfiguration { }
