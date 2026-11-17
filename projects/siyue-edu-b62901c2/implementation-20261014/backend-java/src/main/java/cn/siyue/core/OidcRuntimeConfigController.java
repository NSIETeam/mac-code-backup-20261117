package cn.siyue.core;

import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes only non-secret OIDC settings needed by the browser PKCE client. */
@RestController
@RequestMapping("/api/v2/public")
public class OidcRuntimeConfigController {
    private final OidcConfig config;
    private final CliOidcConfig cliConfig;

    /** 加载公开客户端配置，并要求浏览器回调为无查询和片段的 HTTPS 地址且匹配配置的 Web origin。
     * @param environment 含 issuer、audience、Web client、redirect URI 和可选 CLI client 的运行环境
     * @throws IllegalStateException 必填环境属性缺失时
     * @throws IllegalArgumentException 回调 URI、scope 或必填配置值不合法时
     */
    public OidcRuntimeConfigController(Environment environment) {
        String issuer = environment.getRequiredProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
        String audience = environment.getRequiredProperty("spring.security.oauth2.resourceserver.jwt.audiences");
        String clientId = environment.getRequiredProperty("siyue.oidc.client-id");
        String redirectUri = environment.getRequiredProperty("siyue.oidc.redirect-uri");
        String scope = environment.getProperty("siyue.oidc.scope", "openid profile");
        String cliClientId = environment.getProperty("siyue.oidc.cli-client-id", "");
        String webOrigin = environment.getRequiredProperty("siyue.web-origin");
        URI redirect = URI.create(redirectUri);
        boolean unsafeRedirect = !redirect.isAbsolute() || !"https".equalsIgnoreCase(redirect.getScheme())
            || redirect.getHost() == null || redirect.getUserInfo() != null
            || redirect.getQuery() != null || redirect.getFragment() != null
            || !origin(redirect).equals(webOrigin);
        if (issuer.isBlank() || audience.isBlank() || clientId.isBlank() || unsafeRedirect
                || !scope.matches("openid(?: [A-Za-z0-9:_-]+)*")) {
            throw new IllegalArgumentException("OIDC browser configuration is invalid");
        }
        config = new OidcConfig(issuer, clientId, audience, redirectUri, scope);
        cliConfig = cliClientId.isBlank() ? null : new CliOidcConfig(issuer, cliClientId, audience, scope);
    }

    /** 公开浏览器 PKCE 客户端所需的非秘密配置，并禁止缓存。
     * @return 包含 issuer、client ID、audience、回调与 scope 的 no-store 成功响应
     */
    @GetMapping("/oidc-config")
    public ResponseEntity<ApiResponse<OidcConfig>> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(config));
    }

    /** 当运行配置提供 CLI client ID 时公开该客户端的非秘密参数，不验证身份提供方的实际注册状态。
     * @return CLI issuer、client ID、audience 和 scope 的 no-store 成功响应
     * @throws BusinessException 未配置 CLI client ID 时，错误码为 CONFIGURATION_UNAVAILABLE
     */
    @GetMapping("/cli-oidc-config")
    public ResponseEntity<ApiResponse<CliOidcConfig>> getCli() {
        if (cliConfig == null) {
            throw new BusinessException(ErrorCode.CONFIGURATION_UNAVAILABLE, "CLI OIDC client is not configured");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(cliConfig));
    }

    private String origin(URI uri) {
        int port = uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + (port == -1 ? "" : ":" + port);
    }

    /** 浏览器公开客户端参数，不包含客户端秘密或访问令牌。
     * @param issuer 身份提供方发行者地址
     * @param clientId 浏览器公开客户端编号
     * @param apiAudience API要求的受众
     * @param redirectUri 已检查 origin 的 HTTPS 回调地址
     * @param scope 以 openid 开头的授权范围
     */
    public record OidcConfig(String issuer, String clientId, String apiAudience,
                             String redirectUri, String scope) { }
    /** CLI公开客户端参数；本记录不提供客户端秘密或认证凭证。
     * @param issuer 身份提供方发行者地址
     * @param clientId 配置的 CLI 客户端编号
     * @param apiAudience API要求的受众
     * @param scope 授权范围
     */
    public record CliOidcConfig(String issuer, String clientId, String apiAudience, String scope) { }
}
