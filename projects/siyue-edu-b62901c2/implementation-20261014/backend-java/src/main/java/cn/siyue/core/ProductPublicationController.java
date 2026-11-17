package cn.siyue.core;

import cn.siyue.core.CommerceDtos.Product;
import cn.siyue.core.CommerceDtos.PublicationInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Course-oriented publication routes preserve the legacy product.publish permission boundary. */
@RestController
@RequestMapping("/api/v2/commerce/courses")
@Validated
public class ProductPublicationController {
    private final AccessRepository access;
    private final ProductPublicationService service;
    /**
     * 装配课程商品上下架入口，与教学版本发布保持独立。
     * @param access 可信 issuer/subject 的本地身份解析器
     * @param service 锁定课程、校验版本并保存商品上下架及幂等回执的服务
     */
    public ProductPublicationController(AccessRepository access,ProductPublicationService service) {
        this.access=access;this.service=service;
    }
    /**
     * 上架课程商品；服务重新加载身份，要求 product.publish 或 commerce.manage。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析当前本地身份
     * @param id 可访问课程的公开标识
     * @param key 本次上架请求的幂等键
     * @param input 正整数课程期望版本及可选、非空且不重复的班级选择
     * @return 保存后的商品，或重新校验当前商品范围后返回的原请求回执
     * @throws BusinessException 身份、权限、课程范围、版本、班级选择或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或商品回执的 JSON 编解码失败时
     */
    @PostMapping("/{id}/publish")
    public ApiResponse<Product> publish(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody PublicationInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.publish(actor(jwt),id,key,input,true));
    }
    /**
     * 下架课程商品，保留独立的下架幂等操作类型；不更改教学发布版本。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析当前本地身份
     * @param id 可访问课程的公开标识
     * @param key 本次下架请求的幂等键
     * @param input 课程期望版本及由服务校验的可选班级选择
     * @return 下架后的商品，或当前仍有权读取的原请求回执
     * @throws BusinessException 身份、product.publish/commerce.manage 权限、范围、版本、输入或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或商品回执的 JSON 编解码失败时
     */
    @PostMapping("/{id}/unpublish")
    public ApiResponse<Product> unpublish(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody PublicationInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.publish(actor(jwt),id,key,input,false));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(),jwt.getSubject()); }
}
