package cn.siyue.core;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Max;
import java.time.Instant;
import java.util.List;

/** External school numbers retain their original namespace and spelling. */
public final class StudentIdentifierDtos {
    private StudentIdentifierDtos() { }
    /** 外部学号操作的客户/学员路径上下文，客户用于核验家庭关联而非替代学员身份。 */
    public record Target(String customerId, String studentId) { }
    /** 外部学号列表按启用状态及分页筛选；all包含已停用记录。 */
    public record Query(@Pattern(regexp="active|inactive|all") String status,
            @Min(1) @Max(100000) Integer page, @Min(1) @Max(100) Integer size) { }
    /** 新增外部学号的命名空间、原始拼写和学员预期版本，唯一性由业务服务校验。 */
    public record Create(@NotBlank @Size(max=100) String namespace, @NotBlank @Size(max=120) String value,
            @Min(1) long expectedStudentVersion) { }
    /** 停用外部学号所需的学号记录预期版本及原因，保留原记录历史。 */
    public record Deactivate(@Min(1) long expectedVersion, @NotBlank @Size(max=1000) String reason) { }
    /** 授权范围内的外部学号详情，保留命名空间、原值、启用状态与创建/停用时间。 */
    public record Identifier(String id, String studentId, String namespace, String value, boolean active,
            long version, Instant createdAt, Instant deactivatedAt) { }
    /** 学员外部学号分页，并返回学员当前版本供后续新增操作校验。 */
    public record Page(List<Identifier> items, long total, int page, int size, long studentVersion) { }
    /** 仓储解析后的学员内部主键、公开标识及版本，供受控学号操作使用。 */
    public record Link(long studentId, String studentPublicId, long studentVersion) { }
    /** 外部学号记录总数、当前密钥索引齐备/待补数量及没有可用搜索索引的启用学号数量。 */
    public record IndexStatus(long totalIdentifiers, long indexedIdentifiers, long pendingIdentifiers, long missingSearchable) { }
    /** 一次有界学号索引重建的处理量、下一主键游标、是否剩余及当前总体状态。 */
    public record RebuildResult(int processed, long nextAfterId, boolean hasMore, IndexStatus status) { }
}
