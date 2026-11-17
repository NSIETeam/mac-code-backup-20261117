package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;

final class TaskRoutingSupport {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private TaskRoutingSupport() { }
    static void invalid(String text) { throw new BusinessException(ErrorCode.INVALID_INPUT,text); }
    static void stale() { throw new BusinessException(ErrorCode.PREVIEW_STALE,"任务安排已变化，请重新预览"); }
    static void notFound() { throw new BusinessException(ErrorCode.NOT_FOUND,"任务批次不存在或不在当前范围"); }
    static String clean(String value) { return value==null||value.isBlank()?null:value.trim(); }
    static void key(String key) { if(key==null||!key.matches("[A-Za-z0-9._:-]{16,128}"))invalid("幂等键无效"); }
    static void page(int page,int size) { if(page<1||page>100000||size<1||size>100)invalid("分页范围无效"); }
    static void period(Instant start,Instant due) {
        if(start==null||due==null||!due.isAfter(start)||!due.isAfter(Instant.now()))invalid("任务开始/截止时间无效");
        if(start.getNano()%1_000_000!=0||due.getNano()%1_000_000!=0)invalid("任务时间最多支持毫秒精度，请重新选择时间");
    }
    static void management(Actor actor) {
        actor.require("crm.task.read"); actor.require("crm.task.manage");
    }
    static void write(Actor actor) { management(actor); actor.require("crm.task.write"); actor.require("crm.task.assign"); }
    static void create(Actor actor) { actor.require("crm.task.write"); actor.require("crm.task.assign"); }
    static String json(Object value) {
        try { return JSON.writeValueAsString(value); } catch(Exception error) { throw new IllegalStateException(error); }
    }
    static <T> T parse(String value,Class<T> type) {
        try { return JSON.readValue(value,type); } catch(Exception error) { throw new IllegalStateException(error); }
    }
    static void operation(String action) {
        if(!Set.of("allocation","recall","transfer","schedule","cancel").contains(action))invalid("任务管理操作无效");
    }
    static List<String> actions(Actor actor,String mode,String status) {
        if(!actor.permissions().containsAll(Set.of("crm.task.write","crm.task.assign","crm.task.manage"))||!"active".equals(status))return List.of();
        return "supervisor".equals(mode)?List.of("allocation","recall","transfer","schedule","cancel"):List.of("schedule","cancel");
    }
}
