package cn.siyue.core;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

/** CRM consultation runtime projections and commands. */
public final class ConsultationDtos {
    private ConsultationDtos() { }

    /** 新增客户咨询记录的渠道、正文、可选校区/活动/商机及下次跟进时间。 */
    public record Input(@NotBlank @Size(max=120) String customerId,
            @NotBlank @Pattern(regexp="phone|wechat|visit|online|other") String channel,
            @NotBlank @Size(max=5000) String note,
            @Size(max=120) String campusId, @Size(max=120) String campaignId,
            @Size(max=120) String opportunityId, Instant nextFollowAt) { }

    /** 咨询记录的个人/团队、客户、活动、负责人和时间筛选，默认个人范围第1页每页50条。 */
    public record Query(@Pattern(regexp="mine|team") String scope,
            @Size(max=120) String customerId, @Size(max=120) String campaignId,
            @Size(max=120) String ownerId, @Pattern(regexp="recorded") String status,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Min(1) @Max(100000) Integer page, @Min(1) @Max(100) Integer pageSize) {
        /**
         * 补齐咨询查询的个人范围及分页默认值。
         * @param scope 个人或团队范围，null使用mine
         * @param customerId 可选客户标识
         * @param campaignId 可选活动标识
         * @param ownerId 可选负责人标识
         * @param status 可选recorded状态筛选
         * @param from 可选发生时间起点
         * @param to 可选发生时间终点
         * @param page 页码，null使用1
         * @param pageSize 每页条数，null使用50
         */
        public Query {
            if(scope==null)scope="mine";
            if(page==null)page=1;
            if(pageSize==null)pageSize=50;
        }
    }

    /** 咨询记录详情及追加式历史，保留关联触点、商机和实际发生时间。 */
    public record Consultation(String id, String customerId, String campusId, String ownerId,
            String title, String channel, String note, String campaignId, String opportunityId,
            String touchpointId, Instant nextFollowAt, Instant occurredAt, String status, long version,
            List<History> history) { }
    /** 咨询记录历史事件的操作者、类型、说明与发生时间。 */
    public record History(String id, String actorId, String kind, String detail, Instant occurredAt) { }
    /** 同一可见范围和筛选条件下的咨询分页及总数。 */
    public record Page(List<Consultation> items, long total, int page, int pageSize) { }

    /** 咨询关联商机产生的通知投影，保留创建时间、已读时间及行版本。 */
    public record Notice(String id, String opportunityId, String consultationId, String title,
            String text, Instant createdAt, Instant readAt, long version) { }
    /** 当前接收人可读取的咨询通知分页。 */
    public record NoticePage(List<Notice> items,long total,int page,int pageSize) { }
    /** 通知已读状态及分页筛选，默认只查未读、第1页每页50条。 */
    public record NoticeQuery(@Pattern(regexp="unread|read|all") String status,
            @Min(1) @Max(100000) Integer page, @Min(1) @Max(100) Integer pageSize) {
        /**
         * 补齐咨询通知的未读筛选及分页默认值。
         * @param status 未读/已读/全部，null使用unread
         * @param page 页码，null使用1
         * @param pageSize 每页条数，null使用50
         */
        public NoticeQuery {
            if(status==null)status="unread";
            if(page==null)page=1;
            if(pageSize==null)pageSize=50;
        }
    }
    /** 通知标记已读时的预期行版本，避免覆盖并发状态变化。 */
    public record MarkRead(@Min(1) long expectedVersion) { }
}
