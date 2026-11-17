package cn.siyue.core;

import cn.siyue.core.StaffProfileDtos.Profile;
import cn.siyue.core.StaffProfileDtos.UpdateInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reads and updates only business profile fields for existing principals. */
@RestController
@Validated
@RequestMapping("/api/v2/system/staff")
public class StaffProfileController {
    private final AccessRepository access;
    private final StaffProfileService service;

    /**
     * 装配既有员工业务档案的 HTTP 入口。
     * @param access issuer/subject 绑定及当前身份读取器
     * @param service 档案读取、字段校验及幂等更新事务服务
     */
    public StaffProfileController(AccessRepository access, StaffProfileService service) {
        this.access = access; this.service = service;
    }

    /**
     * 读取指定员工的业务档案，服务要求 account.update 和全部数据范围。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param employeeId 既有员工公开标识
     * @return 档案数据；尚未创建档案时为版本 0 的空档案
     * @throws BusinessException 身份未绑定、权限或范围不足、员工不存在时
     */
    @GetMapping("/{employeeId}/profile")
    public ApiResponse<Profile> get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,119}") String employeeId) {
        return ApiResponse.success(service.get(actor(jwt), employeeId));
    }

    /**
     * 更新允许的档案字段；服务锁定当前身份并校验版本、归档状态及幂等请求。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param employeeId 待更新员工的公开标识
     * @param key 本次档案修改的幂等键
     * @param input 档案字段补丁及可选期望版本
     * @return 保存后的档案或同请求的已保存回执
     * @throws BusinessException 身份、权限、范围、字段、归档状态、版本或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PutMapping("/{employeeId}/profile")
    public ApiResponse<Profile> update(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,119}") String employeeId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody UpdateInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.update(actor(jwt), key, employeeId, input));
    }

    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
