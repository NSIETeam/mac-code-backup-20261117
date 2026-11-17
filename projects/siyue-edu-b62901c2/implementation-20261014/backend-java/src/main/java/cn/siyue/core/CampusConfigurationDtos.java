package cn.siyue.core;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Versioned campus settings and stable classroom identities. */
public final class CampusConfigurationDtos {
    private CampusConfigurationDtos() { }

    /** 校区配置中的教室输入；身份、容量和引用一致性由配置仓储检查。
     * @param id 已有教室公开标识；新增教室可省略
     * @param name 教室名称
     * @param capacity 实体座位容量，范围1至10000
     * @param virtualSeats 虚拟席位数，范围0至10000，可省略
     */
    public record RoomInput(@Size(max = 120) String id, @Size(max = 100) String name,
            @NotNull @Min(1) @Max(10000) Integer capacity,
            @Min(0) @Max(10000) Integer virtualSeats) { }
    /** 新建校区字段或版本化更新的部分字段；更新时省略字段保留原值。
     * @param name 校区名称，新建时由仓储要求非空
     * @param address 地址文本
     * @param phone 联系电话文本
     * @param rooms 教室配置列表；更新时提供的列表用于协调现有教室
     * @param active 是否启用，新建省略时默认启用
     * @param subjects 校区可用学科列表
     * @param grades 校区可用年级列表
     */
    public record CampusFields(@Size(max = 100) String name, @Size(max = 500) String address,
            @Size(max = 50) String phone, @Size(max = 200) List<@NotNull @Valid RoomInput> rooms,
            Boolean active, @Size(max = 100) List<@NotNull @Size(max = 100) String> subjects,
            @Size(max = 12) List<@NotNull @Size(max = 100) String> grades) { }
    /** 带行版本的校区配置补丁，关联班级和未来课次约束仍由服务端检查。
     * @param patch 待合并字段，不可为空
     * @param expectedVersion 调用者读取的当前校区版本
     */
    public record UpdateInput(@NotNull @Valid CampusFields patch, @Min(1) long expectedVersion) { }
    /** 校区配置返回的稳定教室身份与座位容量。
     * @param id 教室公开标识
     * @param name 教室名称
     * @param capacity 实体席位容量
     * @param virtualSeats 允许的虚拟席位数
     */
    public record Room(String id, String name, int capacity, int virtualSeats) { }
    /** 权限范围内的校区配置详情；敏感文本由服务端解密后返回。
     * @param id 校区公开标识
     * @param name 校区名称
     * @param address 校区地址
     * @param phone 联系电话
     * @param active 校区启用状态
     * @param version 当前行版本
     * @param rooms 当前有效教室配置
     * @param subjects 当前可用学科
     * @param grades 当前可用年级
     */
    public record Campus(String id, String name, String address, String phone, boolean active,
            long version, List<Room> rooms, List<String> subjects, List<String> grades) { }
    /** 经过校区范围限制的配置分页结果。
     * @param items 当前页校区详情
     * @param total 相同查询条件下的总数
     * @param page 从1开始的页码
     * @param size 请求的每页条数
     */
    public record CampusPage(List<Campus> items, long total, int page, int size) { }
}
