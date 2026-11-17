package cn.siyue.core;

import java.util.List;

/** Read-only employee and teacher directory projections. */
public final class StaffDirectoryDtos {
    private StaffDirectoryDtos() { }

    /** 员工/教师目录的分类和分页输入；分类及页数合法性在目录服务验证。 */
    public record Query(String category, int page, int size) { }
    /** 目录成员当前数据范围类型与校区公开标识集合，不作为调用者授权依据。 */
    public record Scope(String type, List<String> campusIds) { }
    /** 启用账号的身份和业务档案投影；姓名/用户名优先档案，角色来自当前有效岗位，createdAt/version为档案值。 */
    public record DirectoryMember(String id, String name, String username, String employeeNo,
            List<String> roleIds, Scope scope, boolean disabled, String createdAt, long version,
            String phone, String employmentStart, String employmentEnd, String title, String note,
            String campusId, String campus) { }
    /** 按当前账号范围和教师/职工分类筛选后的目录分页，保留匹配总数。 */
    public record DirectoryPage(List<DirectoryMember> items, long total, int page, int size, String category) { }
}
