package cn.siyue.core;

import com.fasterxml.jackson.databind.JsonNode;

/** Business-only employee profile fields; identity and access state remain on the principal. */
public final class StaffProfileDtos {
    private StaffProfileDtos() { }

    /** 保留原JSON类型的档案局部更新及预期版本；服务端拒绝未知字段和非法版本，不做字符串宽松转换。 */
    public record UpdateInput(JsonNode patch, JsonNode expectedVersion) { }
    /** 员工业务档案的电话、入离职日期、职称、备注和校区信息；未建档时版本为0，不包含登录授权。 */
    public record Profile(String employeeId, String phone, String employmentStart, String employmentEnd,
            String title, String note, String campusId, String campus, long version) { }
}
