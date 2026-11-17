package cn.siyue.core;

import java.util.Objects;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Builds annotation-aware Service proxies for the offline CLI without starting the web application. */
final class LegacyMigrationPhaseTransactions {
    private LegacyMigrationPhaseTransactions() { }

    /**
     * Creates a Spring proxy whose transaction attributes come from the phase Service's annotation.
     * No transaction is opened during construction; each execute invocation gets the declared boundary.
     * @param manager the transaction manager for the same JDBC data source used by the importer
     * @return a class-based Service proxy; never a directly invoked, unadvised Service instance
     * @throws NullPointerException if no transaction manager was supplied
     */
    static LegacyMigrationPhaseService create(PlatformTransactionManager manager) {
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(Objects.requireNonNull(manager, "transaction manager"));
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        advice.afterPropertiesSet();
        var factory = new ProxyFactory(new LegacyMigrationPhaseService());
        factory.setProxyTargetClass(true);
        factory.addAdvice(advice);
        return (LegacyMigrationPhaseService) factory.getProxy();
    }
}
