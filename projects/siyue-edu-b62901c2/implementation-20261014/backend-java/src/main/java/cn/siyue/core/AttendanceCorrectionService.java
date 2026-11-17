package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Corrects a scoped attendance projection and appends its complete immutable history atomically. */
@Service
public class AttendanceCorrectionService {
    private final AccessRepository access;
    private final AttendanceCorrectionRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** 装配考勤更正的授权、历史写入和幂等依赖。
     * @param access 当前操作人状态锁定与授权读取器
     * @param repository 更正目标范围检查及考勤、历史记录持久化仓库
     * @param mutations 幂等请求和审计结果仓库
     * @param mapper 请求摘要及重放回执的 JSON 编解码器
     */
    public AttendanceCorrectionService(AccessRepository access,AttendanceCorrectionRepository repository,
            MutationRepository mutations,ObjectMapper mapper) {
        this.access=access;this.repository=repository;this.mutations=mutations;this.mapper=mapper;
    }
    /** 更正授权范围内的考勤，并在同一事务内追加历史及幂等回执。
     * @param actor 具有 attendance.manage 或 session.attendance 权限的身份，写入前重新锁定检查
     * @param target 更正目标的课次和学员标识，参与幂等请求摘要
     * @param key 16 至 100 位字母、数字、下划线或短横线幂等键
     * @param input 当前版本、目标状态、班级及非空更正原因（最多 500 字符）
     * @return 本次更正回执，或相同请求原有回执
     * @throws BusinessException 授权、输入、目标范围、版本、幂等或课消余额检查失败，或考勤已锁定时
     * @throws JsonProcessingException 请求摘要或重放回执 JSON 编解码失败时
     */
    @Transactional(rollbackFor=Exception.class, isolation=Isolation.READ_COMMITTED)
    public AttendanceCorrectionDtos.Receipt correct(Actor actor,AttendanceCorrectionDtos.Target target,
            String key,AttendanceCorrectionDtos.Input input) throws JsonProcessingException {
        actor.requireAny("attendance.manage", "session.attendance");
        validate(key,input);
        actor=access.lockActive(actor.id());
        actor.requireAny("attendance.manage", "session.attendance");
        var member=repository.authorize(actor,target,input.classId());
        String action="academic.attendance.correct";
        String hash=mutations.hash(mapper.writeValueAsString(List.of(target,input)));
        String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null)return mapper.readValue(replay,AttendanceCorrectionDtos.Receipt.class);
        var result=repository.correct(actor,member,target,input);
        mutations.complete(actor,new MutationRepository.RetryWrite(key,action,hash,
            mapper.writeValueAsString(result),result.id()));
        return result;
    }
    private void validate(String key,AttendanceCorrectionDtos.Input input) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{16,100}"))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"请提供有效幂等键");
        if(input==null||input.expectedVersion()<1||input.reason()==null||input.reason().isBlank()
                ||input.reason().length()>500||input.status()==null
                ||!List.of("attended","late","left_early","leave","absent").contains(input.status()))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"请填写有效版本、考勤状态和更正原因");
    }
}
