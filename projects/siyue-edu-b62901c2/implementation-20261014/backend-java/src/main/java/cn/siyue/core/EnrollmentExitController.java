package cn.siyue.core;

import cn.siyue.core.EnrollmentExitDtos.CheckoutCancelInput;
import cn.siyue.core.EnrollmentExitDtos.CheckoutCancelReceipt;
import cn.siyue.core.EnrollmentExitDtos.Input;
import cn.siyue.core.EnrollmentExitDtos.Receipt;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Versioned endpoints for distinct enrollment cancellation and withdrawal transitions. */
@RestController
@RequestMapping("/api/v2/academic")
@Validated
public class EnrollmentExitController {
    private final AccessRepository access;
    private final EnrollmentExitService exits;

    /**
     * 连接报名撤销、退课和整单撤销的三个独立事务入口。
     * @param access 可信 issuer/subject 的员工身份解析器
     * @param exits 资格、版本、退款计算及幂等审计服务
     */
    public EnrollmentExitController(AccessRepository access, EnrollmentExitService exits) {
        this.access = access;
        this.exits = exits;
    }

    /**
     * 要求 enrollment.create，撤销一个当前可管理且未实收的直接报名。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param enrollmentId 报名公开标识
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 非空撤销原因和期望报名版本
     * @return 撤销事实及关联订单状态，或同请求原回执
     * @throws BusinessException 权限、报名范围、状态、原因、版本或幂等键无效时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PostMapping("/enrollments/{enrollmentId}/cancel")
    public ApiResponse<Receipt> cancel(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String enrollmentId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Input input)
            throws JsonProcessingException {
        return ApiResponse.success(exits.cancel(actor(jwt), enrollmentId, key, input));
    }

    /**
     * 要求 enrollment.withdraw，办理退课并单独记录计算出的退款申请。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param enrollmentId 报名公开标识
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 非空退课原因和期望报名版本
     * @return 退课与退款申请的关联回执，或同请求原回执
     * @throws BusinessException 权限、报名范围、状态、原因、版本或幂等校验失败时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PostMapping("/enrollments/{enrollmentId}/withdraw")
    public ApiResponse<Receipt> withdraw(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String enrollmentId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Input input)
            throws JsonProcessingException {
        return ApiResponse.success(exits.withdraw(actor(jwt), enrollmentId, key, input));
    }

    /**
     * 要求 enrollment.create，整体撤销同一商城申请已受理且未实收的全部订单。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param checkoutId 商城申请公开标识
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 撤销原因与整单期望版本
     * @return 整单撤销及其订单、报名结果，或同请求原回执
     * @throws BusinessException 权限、整单范围、版本或任一订单不可撤销时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PostMapping("/commerce-checkouts/{checkoutId}/cancel")
    public ApiResponse<CheckoutCancelReceipt> cancelCheckout(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String checkoutId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CheckoutCancelInput input) throws JsonProcessingException {
        return ApiResponse.success(exits.cancelCheckout(actor(jwt), checkoutId, key, input));
    }

    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
