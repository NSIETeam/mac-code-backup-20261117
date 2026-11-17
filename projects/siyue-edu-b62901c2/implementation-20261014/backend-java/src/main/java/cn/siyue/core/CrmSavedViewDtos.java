package cn.siyue.core;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Personal queue views contain only whitelisted search filters, never customer details. */
public final class CrmSavedViewDtos {
    private CrmSavedViewDtos() { }
    /**
     * 提交个人筛选预设，服务按资源类型验证筛选白名单和名称唯一性。
     * @param resourceType 预设所属队列或客户查询资源
     * @param name 非空且最多 80 字符的名称
     * @param filters 该资源允许的筛选键值；不作为任意 SQL 或权限声明执行
     */
    public record Create(@NotBlank @Pattern(regexp = "leads|tasks|taskBatches|opportunities|serviceTickets|customers")
            String resourceType, @NotBlank @Size(max = 80) String name,
            @NotNull Map<@NotBlank @Size(max = 32) String,
                @NotBlank @Size(max = 120) String> filters) { }
    /**
     * 替换个人预设的名称与筛选，不更改所属员工或资源类型。
     * @param name 新名称，非空且最多 80 字符
     * @param filters 所属资源允许的筛选键值
     * @param expectedVersion 编辑时读取的正整数版本
     */
    public record Update(@NotBlank @Size(max = 80) String name,
            @NotNull Map<@NotBlank @Size(max = 32) String,
                @NotBlank @Size(max = 120) String> filters, @Min(1) long expectedVersion) { }
    /**
     * 按版本逻辑删除本人预设的请求。
     * @param expectedVersion 删除确认时读取的正整数版本
     */
    public record Delete(@Min(1) long expectedVersion) { }
    /**
     * 当前个人筛选预设及并发修改版本，不包含查询结果记录。
     * @param id 预设公开标识
     * @param resourceType 预设所属资源
     * @param name 保存的预设名称
     * @param filters 保存的已校验筛选键值
     * @param version 当前版本，修改或删除时作为期望版本提交
     */
    public record View(String id, String resourceType, String name,
            Map<String, String> filters, long version) { }
}
