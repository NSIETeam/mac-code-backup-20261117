package cn.siyue.core;

import cn.siyue.core.CrmDtos.PublicFormReceipt;
import cn.siyue.core.CrmDtos.PublicFormSubmissionInput;
import cn.siyue.core.CrmDtos.PublicFormView;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.InetAddress;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous public access to published CRM forms and pending-review submissions.
 * These routes do not authenticate an employee; visitor fingerprints and source-address rate limits are public
 * submission controls, not staff authorization.
 * @see PublicCrmFormService
 * @see PublicStoreVisitor
 */
@RestController
@RequestMapping("/api/v2/public/forms")
@Validated
public class PublicCrmFormController {
    private final PublicCrmFormService service;
    private final PublicStoreVisitor visitor;
    /** Injects public form behavior and anonymous visitor identity handling.
     * @param service published-form reads and rate-limited submission operations
     * @param visitor verified signed-cookie identity provider; its visitor ID is not an employee principal
     */
    public PublicCrmFormController(PublicCrmFormService service,PublicStoreVisitor visitor){this.service=service;this.visitor=visitor;}

    /** Returns a published revision without exposing internal lead-field bindings.
     * @param formId public form identifier, limited to 120 characters
     * @param activityId optional activity query parameter
     * @param promotionLinkId optional promotion-link query parameter
     * @param request servlet request used to validate canonical and legacy attribution parameters
     * @return public form fields and public collection settings
     * @throws BusinessException if attribution parameters conflict or the published form/link is unavailable
     */
    @GetMapping("/{formId}")
    public ApiResponse<PublicFormView> view(@PathVariable @Size(max=120) String formId,
            @RequestParam(required=false) @Size(max=120) String activityId,
            @RequestParam(required=false) @Size(max=120) String promotionLinkId,HttpServletRequest request){
        String activity=canonical(request,"activityId","campaign"),promotion=canonical(request,"promotionLinkId","promotion");
        if(promotion!=null&&activity==null)throw new BusinessException(ErrorCode.INVALID_INPUT,"推广链接缺少活动标识");
        return ApiResponse.success(service.publicForm(formId,activity,promotion));
    }

    /** Accepts an idempotent anonymous submission and stores it for review.
     * The visitor cookie and source address are attribution/rate-limit inputs, not authenticated staff credentials.
     * @param formId public form identifier, limited to 120 characters
     * @param key idempotency key that must match the submission identifier under the service contract
     * @param input public answer payload and optional validated activity/trial/promotion context
     * @param request servlet request supplying network address and visitor cookie
     * @param response servlet response used to establish or refresh the visitor cookie
     * @return public submission receipt
     * @throws JsonProcessingException if request hashing or receipt serialization fails
     * @throws BusinessException if form, visitor, payload, context, rate-limit, or idempotency validation fails
     */
    @PostMapping("/{formId}/submissions")
    public ApiResponse<PublicFormReceipt> submit(@PathVariable @Size(max=120) String formId,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody PublicFormSubmissionInput input,
            HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response)throws JsonProcessingException{
        return ApiResponse.success(service.submitFromVisitor(formId,key,new PublicCrmFormService.VisitorContext(sourceAddress(request),visitor.identity(request,response)),input));
    }

    private String canonical(HttpServletRequest request,String name,String alias) {
        String[] primary=request.getParameterValues(name),legacy=request.getParameterValues(alias);
        if(primary!=null&&primary.length!=1||legacy!=null&&legacy.length!=1)
            throw new BusinessException(ErrorCode.INVALID_INPUT,"推广链接参数重复");
        String a=primary==null?null:primary[0],b=legacy==null?null:legacy[0];
        if(a!=null&&b!=null&&!a.equals(b))throw new BusinessException(ErrorCode.INVALID_INPUT,"推广链接参数冲突");
        String value=a==null?b:a;
        if(value!=null&&(value.isBlank()||value.length()>120))throw new BusinessException(ErrorCode.INVALID_INPUT,"推广链接参数无效");
        return value;
    }

    private String sourceAddress(HttpServletRequest request){
        String peer=request.getRemoteAddr();
        if(peer==null||!(peer.equals("127.0.0.1")||peer.equals("::1")||peer.equals("0:0:0:0:0:0:0:1")))return peer;
        String forwarded=request.getHeader("X-Real-IP");
        if(forwarded==null||forwarded.length()>45||!forwarded.matches("[0-9A-Fa-f:.]+"))return peer;
        try{return InetAddress.getByName(forwarded).getHostAddress();}
        catch(Exception ignored){return peer;}
    }
}
