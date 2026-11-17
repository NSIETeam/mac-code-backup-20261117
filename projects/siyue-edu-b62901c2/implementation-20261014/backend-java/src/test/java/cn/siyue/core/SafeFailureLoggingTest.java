package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Confirms failed scheduled/API work is traceable without retaining exception payloads. */
class SafeFailureLoggingTest {
    @Test
    void workerGeneratesTraceWithoutLoggingSourceExceptionAndRestoresCallerContext() {
        Logger logger = (Logger) LoggerFactory.getLogger(LeadPolicyWorker.class);
        ListAppender<ILoggingEvent> log = new ListAppender<>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing(); super.append(event);
            }
        };
        log.start(); logger.addAppender(log);
        AccessRepository access = mock(AccessRepository.class);
        when(access.resolve("issuer", "subject")).thenThrow(new IllegalStateException("PRIVATE_TEST_PAYLOAD"));
        var worker = new LeadPolicyWorker(access, mock(LeadPolicyService.class), "issuer", "subject");
        MDC.put("traceId", "outer-trace");
        try {
            worker.execute();
            assertEquals("outer-trace", MDC.get("traceId"));
            ILoggingEvent event = log.list.get(0);
            assertNotNull(event.getMDCPropertyMap().get("traceId"));
            assertNotEquals("outer-trace", event.getMDCPropertyMap().get("traceId"));
            assertFalse(event.getFormattedMessage().contains("PRIVATE_TEST_PAYLOAD"));
            assertNull(event.getThrowableProxy());
            MDC.remove("traceId"); worker.execute();
            assertNull(MDC.get("traceId"));
        } finally { MDC.remove("traceId"); logger.detachAppender(log); log.stop(); }
    }

    @Test
    void wrappedInternalFailureIsLoggedAndReturnedAsGenericError() {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptions.class);
        ListAppender<ILoggingEvent> log = new ListAppender<>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing(); super.append(event);
            }
        };
        log.start(); logger.addAppender(log); MDC.put("traceId", "api-test-trace");
        try {
            var result = new ApiExceptions().business(new BusinessException(ErrorCode.INTERNAL_ERROR, "PRIVATE_TEST_PAYLOAD"));
            assertEquals(500, result.getStatusCode().value());
            assertEquals("服务暂时不可用", result.getBody().message());
            assertEquals("api-test-trace", result.getBody().traceId());
            assertEquals(1, log.list.size());
            assertFalse(log.list.get(0).getFormattedMessage().contains("PRIVATE_TEST_PAYLOAD"));
            assertNull(log.list.get(0).getThrowableProxy());
        } finally { MDC.remove("traceId"); logger.detachAppender(log); log.stop(); }
    }
}
