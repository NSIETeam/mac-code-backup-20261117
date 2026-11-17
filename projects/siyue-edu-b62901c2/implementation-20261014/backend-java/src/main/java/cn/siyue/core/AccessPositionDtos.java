package cn.siyue.core;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Versioned permission templates that can be attached to existing principals. */
public final class AccessPositionDtos {
    private AccessPositionDtos() { }

    /** 新建可授予职位模板的输入；具体权限目录、保留标识及范围由服务复核。
     * @param positionId 可选自定公开标识，限字母数字下划线连字符且至多120字符
     * @param name 非空职位名，至多100字符
     * @param description 可选说明，至多500字符
     * @param directoryKind staff或teacher目录分类，省略时服务采用staff
     * @param permissionCodes 非空列表且最多100项；元素必须非空并属于可授予权限目录
     */
    public record CreateInput(@Size(max = 120) @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9_-]{1,120}") String positionId,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @jakarta.validation.constraints.Pattern(regexp = "staff|teacher") String directoryKind,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> permissionCodes) { }

    /** 替换职位可编辑属性和权限，受期望版本及成员影响范围检查。
     * @param expectedVersion 用户读取的职位版本
     * @param name 非空名称，至多100字符
     * @param description 可选说明，至多500字符
     * @param active 显式启用或停用状态
     * @param directoryKind staff或teacher目录分类；省略时保留当前分类
     * @param permissionCodes 替换权限列表，最多100项，服务拒绝非法权限和重复项
     */
    public record UpdateInput(@NotNull Long expectedVersion,
            @NotBlank @Size(max = 100) String name, @Size(max = 500) String description,
            @NotNull Boolean active,
            @jakarta.validation.constraints.Pattern(regexp = "staff|teacher") String directoryKind,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> permissionCodes) { }

    /** 以完整员工集合替换职位成员，不能用于加入归档员工。
     * @param expectedVersion 当前职位期望版本
     * @param principalIds 完整成员公开标识列表，最多500项；空列表表示移除全部成员
     */
    public record MembersInput(@NotNull Long expectedVersion,
            @NotNull @Size(max = 500) List<@NotBlank @Size(max = 120) String> principalIds) { }

    /** 职位详情及当前有效成员关联，供权限管理和员工目录分类使用。
     * @param id 职位公开标识
     * @param name 职位名称
     * @param description 职位说明
     * @param directoryKind staff或teacher目录分类
     * @param active 职位是否启用
     * @param version 乐观锁版本
     * @param permissionCodes 职位权限码列表
     * @param principalIds 当前成员公开标识列表
     */
    public record Position(String id, String name, String description, String directoryKind, boolean active, long version,
            List<String> permissionCodes, List<String> principalIds) { }

    /** 职位分页查询结果。
     * @param items 当前页职位详情
     * @param total 符合查询的职位总数
     * @param page 从1开始的页码
     * @param size 页大小
     */
    public record PositionPage(List<Position> items, long total, int page, int size) { }
}
