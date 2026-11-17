package cn.siyue.core;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;

/** Editable, version-bound public storefront content. */
public final class PublicStorefrontDtos {
    private PublicStorefrontDtos() { }
    /** 编辑商品对外课程文案、标签、授课渠道与价格的版本化输入；与教学课程发布操作分开。 */
    public record ContentInput(@NotBlank @Size(max=100) String title,
            @NotBlank @Size(max=40) String grade, @NotBlank @Size(max=40) String subject,
            @NotBlank @Pattern(regexp="onsite|live") String courseType,
            @NotNull @DecimalMin("0.00") @Digits(integer=12,fraction=2) BigDecimal price,
            @Size(max=2000) String image, @Size(max=2000) String description,
            @NotNull @jakarta.validation.constraints.Min(1) Long expectedVersion) { }
    /** 商城课程对外显示的标题、年级、学科、渠道、价格和图文内容。 */
    public record Content(String title, String grade, String subject, String courseType,
            BigDecimal price, String image, String description) { }
    /** 商城装修的已发布内容、可选草稿及管理版本/发布版本；公开读取可省略管理信息。 */
    public record Configuration(Map<String,Object> decor,Map<String,Object> draft,long version,Long publishedVersion) {
        /**
         * 构造不携带草稿及管理版本的公开装修投影，version为0、publishedVersion为空。
         * @param decor 已发布装修内容
         */
        public Configuration(Map<String,Object> decor) {this(decor,null,0,null);}
    }
    /** 保存或发布商城装修时提交的文档及预期管理版本，版本0支持初次配置。 */
    public record DecorInput(Map<String,Object> decor,@NotNull @jakarta.validation.constraints.Min(0) Long expectedVersion) { }

}
