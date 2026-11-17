package cn.siyue.core;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyRuntimePropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class);

    @Test void bindsBoundedDeploymentOverrides() {
        contextRunner.withPropertyValues("siyue.concurrency.jdbc-max-pool-size=16",
            "siyue.concurrency.http-max-threads=256", "siyue.concurrency.http-min-spare-threads=24",
            "siyue.concurrency.http-max-connections=1024", "siyue.concurrency.http-accept-count=200")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ConcurrencyRuntimeProperties.class)).isEqualTo(
                    new ConcurrencyRuntimeProperties(16, 256, 24, 1024, 200));
            });
    }

    @Test void rejectsPoolAndQueueValuesOutsideHardBounds() {
        contextRunner.withPropertyValues("siyue.concurrency.jdbc-max-pool-size=65")
            .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("siyue.concurrency.http-accept-count=4097")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test void rejectsInconsistentTomcatThreadAndConnectionLimits() {
        contextRunner.withPropertyValues("siyue.concurrency.http-max-threads=32",
            "siyue.concurrency.http-min-spare-threads=33")
            .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("siyue.concurrency.http-max-threads=64",
            "siyue.concurrency.http-max-connections=63")
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConcurrencyRuntimeProperties.class)
    static class TestConfiguration { }
}
