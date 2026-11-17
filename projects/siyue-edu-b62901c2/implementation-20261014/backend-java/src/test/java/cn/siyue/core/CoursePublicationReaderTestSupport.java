package cn.siyue.core;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

final class CoursePublicationReaderTestSupport {
    private CoursePublicationReaderTestSupport() { }
    static CoursePublicationReader create(AccessRepository access, AcademicCatalogRepository catalog,
            CoursePublicationRepository publications, PlatformTransactionManager manager) {
        var authorization = new CoursePublicationAuthorizationService(access, catalog, publications);
        var proxy = new ProxyFactory(authorization);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return new CoursePublicationReader((CoursePublicationAuthorizationService) proxy.getProxy(), publications);
    }
}
