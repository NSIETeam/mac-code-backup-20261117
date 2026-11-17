package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在表单授权范围内查询奖励配置，并以草稿版本和幂等键保护配置变更。 */
@Service
public class FormRewardService {
    /** 奖励配置修改请求。
     * @param reward 奖励类型及资料或优惠券配置；null 表示移除草稿奖励
     * @param expectedVersion 用户读取到的草稿版本，必须与当前版本一致
     */
    public record Update(@Valid FormRewardRepository.Configuration reward,@Min(1) long expectedVersion) { }
    private final AccessRepository access;
    private final CrmFormRepository forms;
    private final FormRewardRepository rewards;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** 装配表单奖励配置服务；构造器本身不进行业务写入。
     * @param access 当前操作人状态及授权锁读取器
     * @param forms 表单范围校验和版本读取仓库
     * @param rewards 奖励选项、配置校验和持久化仓库
     * @param mutations 请求重放与审计结果存储
     * @param mapper 请求摘要和重放结果的 JSON 编解码器
     */
    public FormRewardService(AccessRepository access,CrmFormRepository forms,FormRewardRepository rewards,
            MutationRepository mutations,ObjectMapper mapper) {
        this.access=access;this.forms=forms;this.rewards=rewards;this.mutations=mutations;this.mapper=mapper;
    }
    /** 分页列出当前表单和操作人范围内可选的奖励优惠券。
     * @param actor 需要 crm.form.read 权限的当前身份
     * @param id 授权范围内的表单公开编号
     * @param page 从 1 开始的页码，最大 100000
     * @param size 每页数量，范围 1 至 100
     * @return 可选项及分页统计
     * @throws BusinessException 缺少读权限、表单不可访问或分页参数无效时
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public FormRewardRepository.CouponPage options(Actor actor,String id,int page,int size) {
        CrmLegacyAccess.read(actor, "crm.form.read");forms.authorizeForm(actor,id);
        if(page<1||page>100000||size<1||size>100) throw invalid("分页无效");
        return rewards.options(actor,forms.read(actor,id),page,size);
    }
    /** 读取当前表单的奖励配置，不执行领取或预约。
     * @param actor 需要 crm.form.read 权限的当前身份
     * @param id 授权范围内的表单公开编号
     * @return 当前草稿配置；尚未配置时为 null
     * @throws BusinessException 缺少读权限或表单不可访问时
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public FormRewardRepository.Configuration read(Actor actor,String id) {
        CrmLegacyAccess.read(actor, "crm.form.read");forms.authorizeForm(actor,id);return rewards.read(rewards.formId(id,false));
    }
    /** 在同一事务内修改奖励草稿、推进版本并记录可重放结果。
     * 相同幂等键和请求返回原结果；首次写入要求当前表单仍为匹配版本的草稿。
     * @param actor 重新锁定并检查 crm.form.write 权限的当前身份
     * @param id 授权范围内的表单公开编号
     * @param key 由字母、数字、下划线或短横线组成的 16 至 100 位幂等键
     * @param input 期望版本和待保存的奖励配置
     * @return 修改后的表单，或相同请求保存的原始表单结果
     * @throws BusinessException 身份或范围无效、幂等键冲突、草稿版本改变或配置不合法时
     * @throws JsonProcessingException 请求摘要或重放结果的 JSON 编解码失败时
     */
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public CrmDtos.MarketingForm update(Actor actor,String id,String key,Update input) throws JsonProcessingException {
        actor=access.lockActive(actor.id());CrmLegacyAccess.operation(actor, "lead.create", "crm.form.write");forms.authorizeForm(actor,id);
        if(key==null||!key.matches("[A-Za-z0-9_-]{16,100}")) throw invalid("幂等键无效");
        String hash=mutations.hash(mapper.writeValueAsString(java.util.List.of(id,input)));
        String replay=mutations.replay(actor,key,"crm.form.reward.update",hash);
        if(replay!=null) return mapper.readValue(replay,CrmDtos.MarketingForm.class);
        rewards.formId(id,true);CrmDtos.MarketingForm form=forms.read(actor,id);
        if(!form.status().equals("draft")||form.version()!=input.expectedVersion()) throw invalid("表单状态或版本已变化");
        rewards.saveDraft(actor,form,input.reward());rewards.advanceVersion(id);
        var result=forms.read(actor,id);mutations.complete(actor,new MutationRepository.RetryWrite(key,"crm.form.reward.update",hash,
            mapper.writeValueAsString(result),id));return result;
    }
    private BusinessException invalid(String text) {return new BusinessException(ErrorCode.INVALID_INPUT,text);}
}
