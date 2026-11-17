package cn.siyue.core;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Search modes never redefine a public student identifier as a business student number. */
public final class CustomerSearchDtos {
    private CustomerSearchDtos() { }
    /** 客户身份搜索模式和搜索值及分页；外部学号和学员公开标识使用不同模式，默认auto。 */
    public record Query(@Pattern(regexp="auto|name|phone|student_number|student_id|opportunity_id|lead_id|email|customer_id") String searchType,
            @Size(max=254) String query,@Min(1) @Max(100000) Integer page,@Min(1) @Max(100) Integer size) {
        /**
         * 补齐自动搜索模式及分页，不把外部学号转换成学员公开标识。
         * @param searchType 搜索模式，null使用auto
         * @param query 搜索文本
         * @param page 页码，null使用1
         * @param size 每页条数，null使用50
         */
        public Query { searchType=searchType==null?"auto":searchType; page=page==null?1:page; size=size==null?50:size; }
    }
    /** 学员姓名索引有界重建输入，从内部主键游标之后处理最多200条。 */
    public record Rebuild(@Min(0) long afterId,@Min(1) @Max(200) int limit) { }
    /** 学员姓名搜索索引的当前覆盖数量、密钥版本数及索引格式版本。 */
    public record NameIndexStatus(long totalStudents,long indexedStudents,long pendingStudents,int keyVersionCount,int indexVersion) { }
    /** 一次姓名索引重建处理量、下一游标和剩余标记，并附当前整体覆盖状态。 */
    public record NameIndexRebuild(int processed,long nextAfterId,boolean hasMore,NameIndexStatus status) { }
}
