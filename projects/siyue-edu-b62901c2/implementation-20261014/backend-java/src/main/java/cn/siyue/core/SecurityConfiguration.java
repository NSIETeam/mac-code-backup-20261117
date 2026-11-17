package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

/** Validates external OAuth2 JWTs; local legacy sessions are never accepted. */
@Configuration
public class SecurityConfiguration {
    /** Builds stateless bearer security; cookies cannot authenticate any request. */
    @Bean
    public SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper, AccessRepository access,
            Environment environment) throws Exception {
        String origin = environment.getRequiredProperty("siyue.web-origin");
        String issuer = environment.getRequiredProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
        String audience = environment.getRequiredProperty("spring.security.oauth2.resourceserver.jwt.audiences");
        if (issuer.isBlank() || !issuer.startsWith("https://") || audience.isBlank()) {
            throw new IllegalArgumentException("A HTTPS issuer and non-empty audience are required");
        }
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(requests -> requests
                .requestMatchers("/api/v2/public/forms/**", "/api/v2/public/store", "/api/v2/public/store/**",
                    "/api/v2/public/oidc-config",
                    "/api/v2/public/cli-oidc-config", "/api/v2/system/credential-change-events").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> {})
                .authenticationEntryPoint((request, response, error) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    mapper.writeValue(response.getOutputStream(), new ApiResponse<>(
                        "UNAUTHORIZED", "请使用有效的 OAuth2 凭证", null, MDC.get("traceId")));
                }))
            .addFilterBefore(new RequestGuard(origin, mapper), BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new CredentialChangeGateFilter(access, mapper), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
