package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Java/MySQL replacement for the legacy administrator annotation queue. */
@RestController
@RequestMapping("/api/v2/admin/ui-annotations")
public class UiAnnotationController {
    private final AccessRepository access;
    private final UiAnnotationService service;
    /** 装配使用数据库权限的修改建议服务，不信任令牌中自报的角色。
     * @param access 员工身份和当前权限解析仓库
     * @param service 管理员范围、版本、幂等和审计处理服务
     */
    public UiAnnotationController(AccessRepository access,UiAnnotationService service) {
        this.access=access;this.service=service;
    }
    /** 读取有界的一页修改建议，不改变建议状态。
     * @param jwt 资源服务器已验证的员工访问令牌
     * @param status open 或 resolved；HTTP默认 open
     * @param page 页码，HTTP默认 1
     * @param size 页大小，HTTP默认 50，最大 100
     * @return 当前状态建议页的成功响应
     * @throws BusinessException 身份、管理员权限/范围或分页状态校验失败时
     */
    @GetMapping
    public ApiResponse<UiAnnotationDtos.Page> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="open") String status,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="50") int size) {
        return ApiResponse.success(service.list(actor(jwt),status,page,size));
    }
    /** 创建元素问题或流程建议，并由服务记录幂等结果及审计。
     * @param jwt 资源服务器已验证的员工访问令牌
     * @param key Idempotency-Key 请求头，需满足服务端格式约束
     * @param input 入口校验的建议及可选流程定义
     * @return 新建建议或原始重放结果的成功响应
     * @throws BusinessException 身份、权限、幂等或流程定义校验失败时
     * @throws JsonProcessingException 服务编码请求摘要或回执失败时
     */
    @PostMapping
    public ApiResponse<UiAnnotationDtos.View> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody UiAnnotationDtos.Create input)
            throws JsonProcessingException {
        return ApiResponse.success(service.create(actor(jwt),key,input));
    }
    /** 按版本更新建议状态；resolved 要求调用方提供已完成验证的声明。
     * @param jwt 资源服务器已验证的员工访问令牌
     * @param id 建议公开编号
     * @param key Idempotency-Key 请求头
     * @param input 入口校验的目标状态、期望版本和验证声明
     * @return 更新后的建议或原始重放结果的成功响应
     * @throws BusinessException 身份、权限、验证声明、建议版本或幂等校验失败时
     * @throws JsonProcessingException 服务编码请求摘要或回执失败时
     */
    @PatchMapping("/{id}")
    public ApiResponse<UiAnnotationDtos.View> update(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody UiAnnotationDtos.Update input)
            throws JsonProcessingException {
        return ApiResponse.success(service.update(actor(jwt),id,key,input));
    }
    private Actor actor(Jwt jwt) {return access.resolve(jwt.getIssuer().toString(),jwt.getSubject());}
}
