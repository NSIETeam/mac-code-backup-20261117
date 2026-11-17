package cn.siyue.core;

import cn.siyue.core.AccessPositionDtos.CreateInput;
import cn.siyue.core.AccessPositionDtos.MembersInput;
import cn.siyue.core.AccessPositionDtos.Position;
import cn.siyue.core.AccessPositionDtos.PositionPage;
import cn.siyue.core.AccessPositionDtos.UpdateInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manages versioned position grant templates and their real principal assignments. */
@RestController
@Validated
@RequestMapping("/api/v2/system/positions")
public class AccessPositionController {
    private final AccessRepository access;
    private final AccessPositionService service;

    /**
     * 装配职位权限模板与员工成员维护入口。
     * @param access 可信 issuer/subject 的本地身份解析器
     * @param service 全局管理授权、职位版本及幂等变更服务
     */
    public AccessPositionController(AccessRepository access, AccessPositionService service) {
        this.access = access;
        this.service = service;
    }

    /**
     * 在 system.access.manage 及全部数据范围内列出职位定义。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param page 从 1 开始的页码，最大 100000
     * @param size 每页 1 至 100 条
     * @return 职位分页及总数
     * @throws BusinessException 身份、全局管理权限或分页无效时
     */
    @GetMapping
    public ApiResponse<PositionPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.list(actor(jwt), page, size));
    }

    /**
     * 创建职位权限模板，服务在管理锁内重新加载并校验当前管理员。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param key 本次创建的幂等键
     * @param input 可选公开标识、名称、描述、目录分类与可授予权限
     * @return 新职位或同请求原回执
     * @throws BusinessException 身份、全局管理权限、输入、目标版本或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PostMapping
    public ApiResponse<Position> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.create(actor(jwt), key, input));
    }

    /**
     * 按期望版本替换职位的可编辑定义并保存变更审计。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param positionId 职位公开标识
     * @param key 本次更新的幂等键
     * @param input 期望版本、名称、描述、启用状态、目录分类和权限集合
     * @return 更新后的职位或同请求原回执
     * @throws BusinessException 身份、全局管理权限、输入、目标版本或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PutMapping("/{positionId}")
    public ApiResponse<Position> update(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String positionId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody UpdateInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.update(actor(jwt), positionId, key, input));
    }

    /**
     * 按期望版本整体替换职位成员；已归档或不存在的员工不得加入。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param positionId 职位公开标识
     * @param key 本次成员替换的幂等键
     * @param input 期望版本及最多 500 个不重复员工公开标识
     * @return 含替换后成员的职位或同请求原回执
     * @throws BusinessException 身份、全局管理权限、输入、目标版本或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PutMapping("/{positionId}/members")
    public ApiResponse<Position> members(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String positionId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody MembersInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.members(actor(jwt), positionId, key, input));
    }

    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
