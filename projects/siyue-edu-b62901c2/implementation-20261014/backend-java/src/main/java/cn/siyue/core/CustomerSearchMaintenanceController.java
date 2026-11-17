package cn.siyue.core;

import cn.siyue.core.CustomerSearchDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public maintenance receipts contain only counts and progress, never decrypted identity values. */
@RestController
@RequestMapping("/api/v2/crm/customer-search")
public class CustomerSearchMaintenanceController {
    private final AccessRepository access;
    private final CustomerSearchMaintenanceService service;
    /**
     * 装配两类学生查询索引的有界维护入口，回执仅包含计数与进度。
     * @param access 可信 issuer/subject 绑定解析器
     * @param service 独立索引维护授权和幂等批处理服务
     */
    public CustomerSearchMaintenanceController(AccessRepository access,CustomerSearchMaintenanceService service) {
        this.access=access; this.service=service;
    }
    /**
     * 读取学生姓名索引覆盖状态，要求 crm.customer.search.manage 和全部范围。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @return 总数、已索引数、待处理数及索引版本
     * @throws BusinessException 身份不可用、缺少维护权限或并非全部数据范围时
     */
    @GetMapping("/student-name-index/status")
    public ApiResponse<NameIndexStatus> nameStatus(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.nameStatus(actor(jwt)));
    }
    /**
     * 重建一批学生姓名索引，服务重新锁定身份并保存幂等维护回执。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param key 本次有界重建的幂等键
     * @param input 非负起始行 ID 和 1 至 200 的源记录批次上限
     * @return 处理数量、后续位置、完成标记及索引状态，或同请求原回执
     * @throws BusinessException 身份、全局维护权限、批次边界或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PostMapping("/student-name-index/rebuild")
    public ApiResponse<NameIndexRebuild> rebuildNames(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Rebuild input) throws JsonProcessingException {
        return ApiResponse.success(service.rebuildNames(actor(jwt),key,input));
    }
    /**
     * 读取学号索引覆盖状态，要求 crm.customer.search.manage 和全部范围。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @return 总数、已索引数、待处理数及索引版本
     * @throws BusinessException 身份不可用、缺少维护权限或并非全部数据范围时
     */
    @GetMapping("/student-number-index/status")
    public ApiResponse<StudentIdentifierDtos.IndexStatus> numberStatus(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.numberStatus(actor(jwt)));
    }
    /**
     * 重建一批学生标识索引，保存可重放的计数和继续位置，不返回解密标识。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param key 本次有界重建的幂等键
     * @param input 非负起始行 ID 和 1 至 200 的源记录批次上限
     * @return 处理数量、继续位置及完成标记，或同请求原回执
     * @throws BusinessException 身份、全局维护权限、批次边界或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PostMapping("/student-number-index/rebuild")
    public ApiResponse<StudentIdentifierDtos.RebuildResult> rebuildNumbers(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Rebuild input) throws JsonProcessingException {
        return ApiResponse.success(service.rebuildNumbers(actor(jwt),key,input));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(),jwt.getSubject()); }
}
