package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class TransactionBoundaryContractTest {
    @Test void controllersAndSqlRepositoriesDoNotOwnTransactions() {
        for(Class<?> type:new Class<?>[]{FinanceWorkspaceController.class,FinanceWorkspaceRepository.class,
                CrmFormRateRepository.class,PublicStoreRateRepository.class}) {
            assertNull(type.getAnnotation(Transactional.class));
            for(Method method:type.getDeclaredMethods()) assertNull(method.getAnnotation(Transactional.class));
        }
    }
    @Test void rateServicesCommitIndependentlyWithExplicitCheckedExceptionRollback() {
        for(Class<?> type:new Class<?>[]{CrmFormRateLimiter.class,PublicStoreRateLimiter.class}) {
            assertNotNull(type.getAnnotation(Service.class));
            for(Method method:type.getDeclaredMethods()) {
                if(!method.getName().equals("allow")) continue;
                Transactional transaction=method.getAnnotation(Transactional.class);
                assertNotNull(transaction);assertEquals(Propagation.REQUIRES_NEW,transaction.propagation());
                assertArrayEquals(new Class<?>[]{Exception.class},transaction.rollbackFor());
            }
        }
    }
    @Test void financeReadsHaveExplicitServiceTransactions() {
        for(Method method:FinanceWorkspaceService.class.getDeclaredMethods()) {
            Transactional transaction=method.getAnnotation(Transactional.class);
            assertNotNull(transaction);assertTrue(transaction.readOnly());
            assertArrayEquals(new Class<?>[]{Exception.class},transaction.rollbackFor());
        }
    }
    @Test void publicationReaderKeepsCacheBetweenProxiedAuthorizationTransactions() throws Exception {
        assertNotNull(CoursePublicationAuthorizationService.class.getAnnotation(Service.class));
        var authorization = CoursePublicationAuthorizationService.class.getMethod("authorize", Actor.class, String.class);
        var transaction = authorization.getAnnotation(Transactional.class);
        assertNotNull(transaction);
        assertEquals(Propagation.REQUIRED, transaction.propagation());
        assertArrayEquals(new Class<?>[]{Exception.class}, transaction.rollbackFor());
        assertNull(CoursePublicationReader.class.getAnnotation(Transactional.class));
        assertNull(CoursePublicationReader.class.getMethod("get", Actor.class, String.class).getAnnotation(Transactional.class));
    }

}
