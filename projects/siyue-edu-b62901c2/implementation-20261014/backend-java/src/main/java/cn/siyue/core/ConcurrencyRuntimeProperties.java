package cn.siyue.core;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Deployment-tunable concurrency limits with conservative hard bounds. */
@Validated
@ConfigurationProperties(prefix = "siyue.concurrency")
public record ConcurrencyRuntimeProperties(
        @Min(1) @Max(64) int jdbcMaxPoolSize,
        @Min(10) @Max(512) int httpMaxThreads,
        @Min(1) @Max(512) int httpMinSpareThreads,
        @Min(10) @Max(16384) int httpMaxConnections,
        @Min(1) @Max(4096) int httpAcceptCount) {
    /** 在属性绑定时校验连接数与线程上下界之间的关系；单项范围由Bean Validation核验。
     * @param jdbcMaxPoolSize JDBC最大连接池大小，配置约束1至64
     * @param httpMaxThreads HTTP最大工作线程数，配置约束10至512
     * @param httpMinSpareThreads HTTP最小备用线程数，必须不超过最大线程数
     * @param httpMaxConnections HTTP最大连接数，必须不小于最大线程数
     * @param httpAcceptCount HTTP等待接收队列长度，配置约束1至4096
     * @throws IllegalArgumentException 备用线程超过最大线程或最大连接数小于最大线程数时
     */
    public ConcurrencyRuntimeProperties {
        if (httpMinSpareThreads > httpMaxThreads) {
            throw new IllegalArgumentException("HTTP minimum spare threads must not exceed maximum threads");
        }
        if (httpMaxConnections < httpMaxThreads) {
            throw new IllegalArgumentException("HTTP maximum connections must be at least maximum threads");
        }
    }
}
