package cn.siyue.core;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables SLA automation only in an environment that explicitly opts into the worker. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "siyue.crm.policy.worker.enabled", havingValue = "true")
public class LeadPolicyWorkerConfiguration { }
