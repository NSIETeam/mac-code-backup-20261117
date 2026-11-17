package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrator-only feedback with transactional authorization, idempotency and audit. */
@Service
public class UiAnnotationService {
    private final AccessRepository access;
    private final UiAnnotationRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** 装配修改建议的授权锁、持久化和幂等依赖。
     * @param access 当前管理员身份状态和授权锁读取器
     * @param repository 修改建议及流程定义的持久化仓库
     * @param mutations 请求重放与审计结果仓库
     * @param mapper 请求摘要和原始回执的 JSON 编解码器
     */
    public UiAnnotationService(AccessRepository access,UiAnnotationRepository repository,
            MutationRepository mutations,ObjectMapper mapper) {
        this.access=access;this.repository=repository;this.mutations=mutations;this.mapper=mapper;
    }
    /** 重新核验全局管理员权限后，读取指定状态的一页修改建议。
     * @param actor 需要 system.access.manage 权限且数据范围为 all 的当前身份
     * @param status open 或 resolved 状态筛选
     * @param page 从 1 开始的页码，最大 100000
     * @param size 每页数量，范围 1 至 100
     * @return 当前页的建议及分页统计；调用方需继续分页才能取得完整清单
     * @throws BusinessException 当前身份失效、权限或范围不足、状态或分页无效时
     */
    @Transactional(rollbackFor=Exception.class)
    public UiAnnotationDtos.Page list(Actor actor,String status,int page,int size) {
        administrator(actor);
        if(!java.util.Set.of("open","resolved").contains(status)||page<1||page>100000||size<1||size>100)
            throw new BusinessException(ErrorCode.INVALID_INPUT,"清单筛选或分页无效");
        return repository.list(status,page,size);
    }
    /** 在同一事务保存修改建议和审计，匹配的幂等请求返回原始回执。
     * @param actor 重新锁定核验的全局管理员身份
     * @param key 16 至 100 位字母、数字、下划线或短横线幂等键
     * @param input 已由入口校验的建议；workflow 类须含流程名、当前及目标完整定义
     * @return 新建建议，或同一请求已保存的建议回执
     * @throws BusinessException 管理员身份、幂等键或流程定义检查失败，或请求重放冲突时
     * @throws JsonProcessingException 请求摘要或重放结果 JSON 编解码失败时
     */
    @Transactional(rollbackFor=Exception.class)
    public UiAnnotationDtos.View create(Actor actor,String key,UiAnnotationDtos.Create input)
            throws JsonProcessingException {
        actor=administrator(actor);requireKey(key);validateFlow(input);
        String action="ui.annotation.create",hash=mutations.hash(mapper.writeValueAsString(input));
        String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null)return mapper.readValue(replay,UiAnnotationDtos.View.class);
        var created=repository.create(actor,input);
        mutations.complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(created),created.id()));
        return created;
    }
    /** 按版本更新建议状态；标记 resolved 必须由调用方明确声明已验证。
     * 此声明不是自动验收证明，调用方仍应先实际完成修改和验证。
     * @param actor 重新锁定核验的全局管理员身份
     * @param id 待更新建议公开编号，参与幂等操作标识
     * @param key 16 至 100 位字母、数字、下划线或短横线幂等键
     * @param input 已由入口校验的目标状态、验证声明和期望版本
     * @return 更新后的建议，或同一请求的原始回执
     * @throws BusinessException 权限不足、完成状态未声明验证、建议不存在、版本或幂等冲突时
     * @throws JsonProcessingException 请求摘要或重放结果 JSON 编解码失败时
     */
    @Transactional(rollbackFor=Exception.class)
    public UiAnnotationDtos.View update(Actor actor,String id,String key,UiAnnotationDtos.Update input)
            throws JsonProcessingException {
        actor=administrator(actor);requireKey(key);
        if("resolved".equals(input.status())&&!input.verified())
            throw new BusinessException(ErrorCode.INVALID_INPUT,"须完成修改并验证后才能标记完成");
        String action="ui.annotation.update:"+id,hash=mutations.hash(mapper.writeValueAsString(input));
        String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null)return mapper.readValue(replay,UiAnnotationDtos.View.class);
        var before=repository.get(id,true);
        if(before.version()!=input.expectedVersion())throw new BusinessException(ErrorCode.ALREADY_EXISTS,"修改建议版本已变化");
        var updated=repository.update(id,input);
        mutations.complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(updated),id));
        return updated;
    }
    private Actor administrator(Actor actor) {
        Actor current=access.lockActive(actor.id());current.require("system.access.manage");
        if(!"all".equals(current.scope()))throw new BusinessException(ErrorCode.FORBIDDEN,"仅全局管理员可管理修改建议");
        return current;
    }
    private void requireKey(String key) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{16,100}"))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"请提供有效幂等键");
    }
    private void validateFlow(UiAnnotationDtos.Create input) {
        if("workflow".equals(input.category())&&(blank(input.workflowName())||blank(input.flowDefinition())
                ||blank(input.desiredFlowDefinition())))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"流程建议须包含名称及修改前后的完整定义");
    }
    private boolean blank(String value) {return value==null||value.isBlank();}
}
