package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Generates trace IDs and rejects browser requests from unapproved origins. */
public final class RequestGuard extends OncePerRequestFilter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private final String origin;
    private final ObjectMapper mapper;
    /** Uses the deployment origin and shared response serializer. */
    public RequestGuard(String origin, ObjectMapper mapper) {
        if (!origin.matches("https://[A-Za-z0-9.-]+(?::[0-9]+)?|http://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]+)?")) {
            throw new IllegalArgumentException("SIYUE_WEB_ORIGIN must be an origin");
        }
        this.origin = origin;
        this.mapper = mapper;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            String supplied = request.getHeader("Origin");
            if (!SAFE.contains(request.getMethod()) && supplied != null && !origin.equals(supplied)) {
                response.setStatus(403);
                response.setContentType("application/json;charset=UTF-8");
                mapper.writeValue(response.getOutputStream(), new ApiResponse<>(
                    "FORBIDDEN", "请求来源未授权", null, traceId));
                return;
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
