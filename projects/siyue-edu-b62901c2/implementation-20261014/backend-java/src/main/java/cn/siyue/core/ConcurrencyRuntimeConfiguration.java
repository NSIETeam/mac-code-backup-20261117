package cn.siyue.core;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers validated, bounded database and HTTP concurrency settings. */
@Configuration
@EnableConfigurationProperties(ConcurrencyRuntimeProperties.class)
public class ConcurrencyRuntimeConfiguration { }
