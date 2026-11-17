package cn.siyue.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded, deployment-isolated Redis cache settings; MySQL remains authoritative. */
@ConfigurationProperties(prefix = "siyue.publication-cache")
public record PublicationCacheProperties(boolean enabled, String namespace, int ttlSeconds) {
    /** 仅启用时要求独立数据集命名空间和有界TTL，停用配置不触发Redis访问。
     * @param enabled 是否启用不可变教学版本缓存
     * @param namespace 启用时必填8至80位字母数字下划线连字符，部署需保证数据集唯一
     * @param ttlSeconds 启用时缓存TTL，范围30至3600秒
     * @throws IllegalArgumentException 启用但命名空间格式或TTL无效时
     */
    public PublicationCacheProperties {
        if (enabled && (namespace == null || !namespace.matches("[a-zA-Z0-9_-]{8,80}"))) {
            throw new IllegalArgumentException("Publication cache requires an explicit dataset namespace");
        }
        if (enabled && (ttlSeconds < 30 || ttlSeconds > 3600)) {
            throw new IllegalArgumentException("Publication cache TTL must be 30..3600 seconds");
        }
    }
}
