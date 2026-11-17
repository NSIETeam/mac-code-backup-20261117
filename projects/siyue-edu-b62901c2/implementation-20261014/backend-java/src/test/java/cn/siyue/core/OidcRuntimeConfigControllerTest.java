package cn.siyue.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OidcRuntimeConfigController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties={"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example/tenant",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core",
    "siyue.oidc.client-id=siyue-web",
    "siyue.oidc.cli-client-id=siyue-cli-native",
    "siyue.oidc.redirect-uri=https://crm.example.com/crm/",
    "siyue.oidc.scope=openid profile"})
class OidcRuntimeConfigControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;

    @Test void servesOnlyPublicOidcFieldsWithoutBearerAndDisablesCaching() throws Exception {
        mvc.perform(get("/api/v2/public/oidc-config"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.data.issuer").value("https://issuer.example/tenant"))
            .andExpect(jsonPath("$.data.clientId").value("siyue-web"))
            .andExpect(jsonPath("$.data.apiAudience").value("siyue-core"))
            .andExpect(jsonPath("$.data.redirectUri").value("https://crm.example.com/crm/"))
            .andExpect(jsonPath("$.data.clientSecret").doesNotExist());
    }

    @Test void servesNativeCliPublicClientSettingsOnlyWhenConfigured() throws Exception {
        mvc.perform(get("/api/v2/public/cli-oidc-config"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.data.issuer").value("https://issuer.example/tenant"))
            .andExpect(jsonPath("$.data.clientId").value("siyue-cli-native"))
            .andExpect(jsonPath("$.data.apiAudience").value("siyue-core"))
            .andExpect(jsonPath("$.data.scope").value("openid profile"))
            .andExpect(jsonPath("$.data.redirectUri").doesNotExist())
            .andExpect(jsonPath("$.data.clientSecret").doesNotExist());
    }

    @Test void unconfiguredCliPublicClientIsExplicitlyUnavailable() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://issuer.example")
            .withProperty("spring.security.oauth2.resourceserver.jwt.audiences", "siyue-core")
            .withProperty("siyue.oidc.client-id", "siyue-web")
            .withProperty("siyue.oidc.redirect-uri", "https://crm.example.com/crm/")
            .withProperty("siyue.web-origin", "https://crm.example.com");
        OidcRuntimeConfigController controller = new OidcRuntimeConfigController(environment);
        assertThrows(BusinessException.class, controller::getCli);
    }

    @Test void runtimeConfigRejectsCrossOriginRedirect() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://issuer.example")
            .withProperty("spring.security.oauth2.resourceserver.jwt.audiences", "siyue-core")
            .withProperty("siyue.oidc.client-id", "siyue-web")
            .withProperty("siyue.oidc.redirect-uri", "https://elsewhere.example/callback")
            .withProperty("siyue.oidc.scope", "openid profile")
            .withProperty("siyue.web-origin", "https://crm.example.com");
        assertThrows(IllegalArgumentException.class, () -> new OidcRuntimeConfigController(environment));
    }
}
