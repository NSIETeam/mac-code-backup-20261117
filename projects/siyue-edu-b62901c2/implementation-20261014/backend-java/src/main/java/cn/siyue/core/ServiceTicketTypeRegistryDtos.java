package cn.siyue.core;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Contracts for atomically replacing the legacy global service-ticket type directory. */
public final class ServiceTicketTypeRegistryDtos {
    private ServiceTicketTypeRegistryDtos() { }

    /** 工单类型全局目录中的一个类型定义。
     * @param id 以小写字母开头的2至40位小写字母数字下划线编码
     * @param name 非空显示名，至多100字符
     * @param active 显式启用状态
     */
    public record TypeValue(@NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{1,39}") String id,
            @NotBlank @Size(max = 100) String name, @NotNull Boolean active) { }

    /** 按期望版本原子替换完整工单类型目录。
     * @param types 1至30个类型定义，服务拒绝重复编码及无效替换
     * @param expectedVersion 目录期望版本
     */
    public record ReplaceInput(@NotNull @Size(min = 1, max = 30) List<@NotNull @Valid TypeValue> types,
            @Min(1) long expectedVersion) { }

    /** 工单类型目录及整体乐观锁版本。
     * @param types 完整目录类型列表
     * @param version 当前目录版本
     */
    public record RegistryState(List<TypeValue> types, long version) { }
}
