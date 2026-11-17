package cn.siyue.core;

import cn.siyue.core.ConsultationDtos.Input;
import cn.siyue.core.ConsultationDtos.MarkRead;
import cn.siyue.core.ConsultationDtos.NoticePage;
import cn.siyue.core.ConsultationDtos.NoticeQuery;
import cn.siyue.core.ConsultationDtos.Page;
import cn.siyue.core.ConsultationDtos.Query;
import cn.siyue.core.ConsultationDtos.Consultation;
import cn.siyue.core.ConsultationDtos.Notice;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CRM consultation records and recipient-only handoff notices. */
@RestController
@RequestMapping("/api/v2/crm/consultations")
@Validated
public class CrmConsultationController {
    private final AccessRepository access;
    private final CrmConsultationService service;
    /**
     * 连接咨询登记、范围查询和仅接收者可确认的交接提醒接口。
     * @param access 可信 issuer/subject 的员工绑定解析器
     * @param service 咨询授权、客户范围、提醒资格与幂等审计服务
     */
    public CrmConsultationController(AccessRepository access,CrmConsultationService service) {
        this.access=access;this.service=service;
    }
    /**
     * 按个人或授权团队范围读取咨询，校验起止时间及有界分页。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param query 范围、客户/员工、来源、时间区间及分页条件
     * @return 当前范围内的咨询页
     * @throws BusinessException 咨询读取许可、团队权限/范围、时间或分页无效时
     */
    @GetMapping
    public ApiResponse<Page> list(@AuthenticationPrincipal Jwt jwt,@Valid @ModelAttribute Query query) {
        return ApiResponse.success(service.list(actor(jwt),query));
    }
    /**
     * 读取当前授权范围内的一条咨询记录。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param id 咨询公开标识，非空且不超过 120 字符
     * @return 咨询内容和关联来源
     * @throws BusinessException 咨询读取许可、编号或咨询可见范围无效时
     */
    @GetMapping("/{id}")
    public ApiResponse<Consultation> detail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String id) { return ApiResponse.success(service.get(actor(jwt),id)); }
    /**
     * 要求原 lead.create 或现代线索写入和客户读取组合，登记咨询及后续提醒。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 客户、phone/wechat/visit/online/other 渠道、说明及可选未来跟进时间
     * @return 新咨询或同请求原回执
     * @throws BusinessException 权限、关联范围、渠道、说明、时间或幂等校验失败时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PostMapping
    public ApiResponse<Consultation> register(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Input input)
            throws JsonProcessingException { return ApiResponse.success(service.register(actor(jwt),key,input)); }
    /**
     * 只列出当前员工接收的咨询交接提醒。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param query unread/read/all 状态及有界分页
     * @return 当前接收者的提醒页
     * @throws BusinessException 咨询读取许可、状态或分页无效时
     */
    @GetMapping("/notices")
    public ApiResponse<NoticePage> notices(@AuthenticationPrincipal Jwt jwt,
            @Valid @ModelAttribute NoticeQuery query) { return ApiResponse.success(service.notices(actor(jwt),query)); }
    /**
     * 核验当前接收者和期望版本后，将指定交接提醒标为已读。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param id 提醒公开标识
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 提醒期望版本
     * @return 更新的提醒或同请求原回执
     * @throws BusinessException 读取许可、接收者资格、编号、版本或幂等校验失败时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PutMapping("/notices/{id}/read")
    public ApiResponse<Notice> markRead(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String id,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody MarkRead input)throws JsonProcessingException {
        return ApiResponse.success(service.markRead(actor(jwt),id,key,input));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(),jwt.getSubject()); }
}
