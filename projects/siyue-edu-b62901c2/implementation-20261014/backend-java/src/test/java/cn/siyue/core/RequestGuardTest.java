package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class RequestGuardTest {
    private final RequestGuard guard = new RequestGuard("https://crm.example.com", new ObjectMapper());
    @Test void blocksForeignOriginAndDoesNotTrustClientTraceId() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v2/academic/students/s/communications");
        request.addHeader("Origin", "https://foreign.example.com");
        request.addHeader("X-Trace-Id", "untrusted");
        var response = new MockHttpServletResponse();
        guard.doFilter(request, response, (req, res) -> fail("foreign origin reached controller"));
        assertEquals(403, response.getStatus());
        assertNotEquals("untrusted", response.getHeader("X-Trace-Id"));
        assertTrue(response.getContentAsString().contains("FORBIDDEN"));
        assertNull(MDC.get("traceId"));
    }
    @Test void allowsConfiguredOriginAndCleansMdcOnFailure() {
        var request = new MockHttpServletRequest("POST", "/api/v2/academic/students/s/communications");
        request.addHeader("Origin", "https://crm.example.com");
        assertThrows(IllegalStateException.class, () -> guard.doFilter(request,
            new MockHttpServletResponse(), (req, res) -> { throw new IllegalStateException("failed"); }));
        assertNull(MDC.get("traceId"));
    }
    @Test void rejectsNonOriginConfiguration() {
        assertThrows(IllegalArgumentException.class,
            () -> new RequestGuard("https://crm.example.com/path", new ObjectMapper()));
    }
}
