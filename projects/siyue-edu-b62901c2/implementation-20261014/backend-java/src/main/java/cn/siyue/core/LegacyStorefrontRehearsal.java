package cn.siyue.core;

import static cn.siyue.core.LegacyCatalogRehearsal.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exact-source storefront and checkout facts; invoked inside the financial import transaction. */
final class LegacyStorefrontRehearsal {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private static final Set<String> TABLES=Set.of("core_principal","core_campus","core_course","core_class","core_student","core_commerce_product","core_commerce_promotion","core_commerce_checkout");
    LegacyStorefrontRehearsal(JdbcTemplate jdbc,PiiCipher cipher) {this.jdbc=jdbc;this.cipher=cipher;}
    int importRows(JsonNode state) {
        require(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"商城历史导入需要财务事务");
        int count=0;
        for(JsonNode course:array(state,"courses")) if(course.path("storefront").isObject()&&!course.path("storefront").isEmpty()) count+=product(state,course);
        for(String kind:List.of("group","joint")) for(JsonNode offer:optionalArray(state.path("activities"),kind)) count+=promotion(offer,kind);
        for(JsonNode checkout:optionalArray(state,"commerceCheckouts")) count+=checkout(state,checkout);
        count+=decor(state.path("decor"));
        require(count<1000,"商城迁移超过单事务限制");return count;
    }
    private int decor(JsonNode source) {
        if(source.isMissingNode()||source.isNull()||source.isObject()&&source.isEmpty()) return 0;
        require(source.isObject()&&source.path("draft").isObject(),"历史装修缺少准确草稿");
        long version=positive(source,"version");JsonNode draft=source.path("draft"),published=source.path("published");
        var type=new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>() { };
        var validator=new StorefrontDecorValidator(jdbc,false);validator.clean(mapper.convertValue(draft,type));
        Long publishedVersion=null;java.sql.Timestamp publishedAt=null;
        if(!published.isMissingNode()&&!published.isNull()) {
            require(published.isObject(),"历史装修发布内容无效");validator.clean(mapper.convertValue(published,type));
            publishedVersion=positive(source,"publishedVersion");require(publishedVersion<=version,"历史装修发布版本超出草稿");
            publishedAt=date(source,"publishedAt");
        } else require(!source.hasNonNull("publishedVersion")&&!source.hasNonNull("publishedAt"),"未发布装修含发布元数据");
        long actor=id("core_principal",text(source,"updatedBy"));
        jdbc.update("INSERT INTO core_public_storefront_config(singleton_key,decor,draft_decor,version,published_version,published_at,updated_by,created_at,updated_at) "
            + "VALUES(1,?,?,?,?,?,?,?,?)",publishedVersion==null?"{}":published.toString(),draft.toString(),version,publishedVersion,publishedAt,actor,
            date(source,"updatedAt"),date(source,"updatedAt"));return 1;
    }
    private int product(JsonNode state,JsonNode course) {
        JsonNode storefront=course.path("storefront"),content=storefront.path("content");
        require(content.isObject(),"商品缺少发布内容");
        String courseId=text(course,"id"),status=text(storefront,"status");require(Set.of("上架","下架").contains(status),"商品状态未映射");
        boolean published=contains(array(state,"shelved"),courseId)&&!contains(array(state,"hiddenShelved"),courseId)&&status.equals("上架");
        String type=content.path("courseType").asText(course.path("publishedSnapshot").path("courseType").asText());
        require(Set.of("onsite","live").contains(type),"商品缺少发布课程类型");
        jdbc.update("INSERT INTO core_commerce_product(public_id,course_id,owner_id,name,published,version,storefront_title,storefront_grade,"
            + "storefront_subject,storefront_course_type,storefront_price,storefront_image,storefront_description,storefront_course_version,created_at,updated_at) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)","STORE_"+courseId,id("core_course",courseId),id("core_principal",text(storefront,"publishedBy")),
            text(content,"title"),published,positive(storefront,"courseVersion"),text(content,"title"),text(content,"grade"),text(content,"subject"),type,
            money(content,content.has("priceCents")?"priceCents":"priceMinor"),content.path("image").asText(null),content.path("description").asText(null),
            positive(storefront,"courseVersion"),date(storefront,"publishedAt"),storefront.hasNonNull("unpublishedAt")?date(storefront,"unpublishedAt"):date(storefront,"publishedAt"));
        long productId=id("core_commerce_product","STORE_"+courseId);int count=1;
        for(JsonNode selected:array(storefront,"classIds")) {
            long classId=id("core_class",selected.asText());
            require(jdbc.queryForObject("SELECT COUNT(id) FROM core_class WHERE id=? AND course_id=?",Integer.class,classId,id("core_course",courseId))==1,"商品班级不属于课程");
            jdbc.update("INSERT INTO core_commerce_product_class(product_id,class_id) VALUES(?,?)",productId,classId);count++;
        }
        return count;
    }
    private int promotion(JsonNode offer,String type) {
        require(text(offer,"type").equals(type),"活动类型与来源集合不符");
        boolean published=offer.path("published").asBoolean();
        require(!published||text(offer,"status").equals("进行中"),"活动发布状态不一致");
        jdbc.update("INSERT INTO core_commerce_promotion(public_id,owner_id,type,name,description,start_date,end_date,discount_amount,threshold_amount,"
            + "group_size,valid_hours,stack_coupon,participants,total_limit,max_per_user,published,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            text(offer,"id"),id("core_principal",text(offer,"createdBy")),type,text(offer,"name"),offer.path("description").asText(""),
            java.sql.Date.valueOf(text(offer,"start")),java.sql.Date.valueOf(text(offer,"end")),money(offer,"discountMinor"),money(offer,"thresholdMinor"),
            type.equals("group")?positive(offer,"groupSize"):null,type.equals("group")?positive(offer,"validHours"):null,
            offer.path("stackCoupon").asBoolean(),participants(offer),positive(offer,"total"),positive(offer,"maxPerUser"),published,
            positive(offer,"version"),date(offer,"createdAt"),date(offer,"updatedAt"));
        int count=1;long promotion=id("core_commerce_promotion",text(offer,"id"));
        for(JsonNode selection:array(offer,"selections")) for(JsonNode selected:array(selection,"classIds")) {
            long classId=id("core_class",selected.asText());
            require(jdbc.queryForObject("SELECT COUNT(c.id) FROM core_class c JOIN core_course co ON co.id=c.course_id "
                + "WHERE c.id=? AND co.public_id=?",Integer.class,classId,text(selection,"courseId"))==1,"活动班级不属于选择的课程");
            if(offer.hasNonNull("campusId")) require(jdbc.queryForObject("SELECT COUNT(c.id) FROM core_class c JOIN core_campus cp ON cp.id=c.campus_id "
                + "WHERE c.id=? AND cp.public_id=?",Integer.class,classId,text(offer,"campusId"))==1,"活动班级不属于活动校区");
            jdbc.update("INSERT INTO core_commerce_promotion_class(promotion_id,class_id) VALUES(?,?)",promotion,classId);count++;
        }
        return count;
    }
    private static JsonNode optionalArray(JsonNode row,String key) {return row.has(key)?array(row,key):JsonNodeFactory.instance.arrayNode();}
    private String participants(JsonNode offer) {
        List<String> values=new ArrayList<>();for(JsonNode value:optionalArray(offer,"participants")) values.add(value.asText());
        return String.join(",",values);
    }
    private int checkout(JsonNode state,JsonNode row) {
        String status=text(row,"status"),type=text(row,"type");
        require(status.equals("accepted")&&type.equals("standard")&&!row.hasNonNull("promotionId"),"商城历史仅已映射标准已核对申请，其他状态须补精确转换");
        JsonNode quote=row.path("quote"),buyers=array(row,"buyers");require(buyers.size()==array(row,"studentIds").size(),"商城已核对学生数量不符");
        String hash=text(row,"quoteHash");require(hash.matches("[a-f0-9]{64}"),"商城报价摘要无效");
        jdbc.update("INSERT INTO core_commerce_checkout(public_id,checkout_type,status,buyer_count,quote_hash,quote_cipher,expires_at,"
            + "base_amount,discount_amount,total_amount,created_by_id,visitor_hash,accepted_by_id,accepted_at,version,created_at,updated_at) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",text(row,"id"),type,status,buyers.size(),hash,cipher.encrypt(quote.toString()),date(row,"expiresAt"),
            money(quote,"baseCents"),money(quote,"discountCents"),money(quote,"amountCents"),optionalId("core_principal",row,"createdBy"),
            row.hasNonNull("visitorHash")?HexFormat.of().parseHex(text(row,"visitorHash")):null,id("core_principal",text(row,"acceptedBy")),
            date(row,"acceptedAt"),positive(row,"version"),date(row,"createdAt"),date(row,"acceptedAt"));
        long checkout=id("core_commerce_checkout",text(row,"id"));int count=1;
        for(JsonNode campus:array(row,"campusIds")) {jdbc.update("INSERT INTO core_commerce_checkout_campus(checkout_id,campus_id) VALUES(?,?)",checkout,id("core_campus",campus.asText()));count++;}
        for(int index=0;index<buyers.size();index++) {
            JsonNode buyer=buyers.get(index);long student=id("core_student",row.path("studentIds").get(index).asText());
            jdbc.update("INSERT INTO core_commerce_checkout_buyer(checkout_id,buyer_index,selected_student_id,resolved_student_id,name_cipher,phone_cipher,grade,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?)",checkout,index,optionalId("core_student",buyer,"studentId"),student,cipher.encrypt(text(buyer,"name")),
                cipher.encrypt(text(buyer,"phone")),buyer.path("grade").asText(null),date(row,"createdAt"),date(row,"acceptedAt"));count++;
        }
        int index=0;for(JsonNode line:array(quote,"lines")) {line(state,row,line,index++);count++;}
        jdbc.update("INSERT INTO core_commerce_checkout_event(public_id,checkout_id,actor_id,event_type,occurred_at,created_at,updated_at) VALUES(?,?,?,'accepted',?,?,?)",
            "CEVT_LEGACY_"+text(row,"id"),checkout,id("core_principal",text(row,"acceptedBy")),date(row,"acceptedAt"),date(row,"acceptedAt"),date(row,"acceptedAt"));
        validateAmounts(quote);return count+1;
    }
    private void line(JsonNode state,JsonNode checkout,JsonNode line,int index) {
        JsonNode klass=find(array(state,"classes"),text(line,"classId")),course=find(array(state,"courses"),text(line,"courseId"));
        require(legacyClassSignature(klass).equals(text(line,"classSignature")),"历史申请排期已变化，须保留原排期后再迁移");
        require(legacyProductSignature(course).equals(text(line,"productSignature")),"历史申请商品已变化，须保留原商品后再迁移");
        long classId=id("core_class",text(line,"classId")),courseId=id("core_course",text(line,"courseId"));
        long product=id("core_commerce_product","STORE_"+text(line,"courseId"));
        require(text(klass,"courseId").equals(text(course,"id"))&&text(klass,"campusId").equals(text(line,"campusId")),"商城报价课程班级校区不符");
        require(!line.hasNonNull("couponId")&&money(line,"couponDiscountCents").signum()==0,"历史商城叠加券须先补核销映射");
        String signature=new CommerceCheckoutClassFingerprintRepository(jdbc,new MutationRepository(jdbc,cipher),mapper).signature(classId);
        jdbc.update("INSERT INTO core_commerce_checkout_line(checkout_id,line_index,buyer_index,product_id,course_id,class_id,campus_id,base_amount,"
            + "coupon_discount,promotion_discount,amount,product_version,course_version,class_version,class_signature,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id("core_commerce_checkout",text(checkout,"id")),index,line.path("buyerIndex").intValue(),product,courseId,classId,id("core_campus",text(line,"campusId")),
            money(line,"baseCents"),money(line,"couponDiscountCents"),money(line,"promotionDiscountCents"),money(line,"amountCents"),
            positive(course.path("storefront"),"courseVersion"),positive(course,"version"),positive(klass,"version"),signature,date(checkout,"createdAt"),date(checkout,"createdAt"));
    }
    private void validateAmounts(JsonNode quote) {
        for(String key:List.of("baseCents","amountCents")) {
            BigDecimal sum=BigDecimal.ZERO;for(JsonNode line:array(quote,"lines")) sum=sum.add(money(line,key));
            require(sum.compareTo(money(quote,key))==0,"历史商城报价总额与明细不符");
        }
        BigDecimal cuts=BigDecimal.ZERO;for(JsonNode line:array(quote,"lines")) cuts=cuts.add(money(line,"couponDiscountCents")).add(money(line,"promotionDiscountCents"));
        require(cuts.compareTo(money(quote,"discountCents"))==0,"历史商城折扣与明细不符");
    }
    private String legacyClassSignature(JsonNode klass) {
        ObjectNode value=JsonNodeFactory.instance.objectNode();for(String key:List.of("courseId","campusId","durationMinutes")) value.set(key,klass.path(key));
        var sessions=value.putArray("schedule");for(JsonNode source:array(klass,"schedule")) {
            ObjectNode session=sessions.addObject();for(String key:List.of("id","lessonId","date","time","campusId","room","teacherId","status")) {
                JsonNode val=source.get(key);if(key.equals("teacherId")&&source.hasNonNull("substituteTeacherId")) val=source.get("substituteTeacherId");
                if((val==null||val.isNull()||val.asText().isEmpty())&&Set.of("campusId","room","teacherId").contains(key)) val=klass.get(key);
                if(val!=null) session.set(key,val);
            }
        }
        return hash(value.toString());
    }
    private String legacyProductSignature(JsonNode course) {
        ObjectNode value=JsonNodeFactory.instance.objectNode();for(String key:List.of("version","curriculumVersion","priceCents")) if(course.has(key)) value.set(key,course.get(key));
        value.set("publishedAt",course.path("storefront").path("publishedAt"));List<String> ids=new ArrayList<>();
        for(JsonNode id:array(course.path("storefront"),"classIds")) ids.add(id.asText());ids.sort(String::compareTo);
        var classes=value.putArray("classIds");ids.forEach(classes::add);return hash(value.toString());
    }
    static int countRows(JsonNode state) {
        int count=0;
        for(JsonNode course:array(state,"courses")) if(course.path("storefront").isObject()&&!course.path("storefront").isEmpty()) count+=1+array(course.path("storefront"),"classIds").size();
        for(String kind:List.of("group","joint")) for(JsonNode offer:optionalArray(state.path("activities"),kind)) {
            count++;for(JsonNode selection:array(offer,"selections")) count+=array(selection,"classIds").size();
        }
        for(JsonNode checkout:optionalArray(state,"commerceCheckouts")) count+=2+array(checkout,"campusIds").size()+array(checkout,"buyers").size()+array(checkout.path("quote"),"lines").size();
        if(state.path("decor").isObject()&&!state.path("decor").isEmpty()) count++;
        return count;
    }
    private JsonNode find(JsonNode values,String publicId) {
        for(JsonNode value:values) if(text(value,"id").equals(publicId)) return value;
        throw new IllegalArgumentException("商城关联来源记录缺失");
    }
    private boolean contains(JsonNode values,String id) {for(JsonNode value:values) if(value.isTextual()?value.asText().equals(id):value.path("courseId").asText().equals(id)) return true;return false;}
    private Long optionalId(String table,JsonNode row,String key) {return row.hasNonNull(key)?id(table,text(row,key)):null;}
    private long id(String table,String publicId) {
        require(TABLES.contains(table),"商城迁移关联表不支持");
        return jdbc.query("SELECT id FROM "+table+" WHERE public_id=? AND deleted=0",(rs,n)->rs.getLong(1),publicId)
            .stream().findFirst().orElseThrow(()->new IllegalArgumentException("商城迁移缺少准确关联："+table));
    }
    private void require(boolean condition,String text) {if(!condition) throw new IllegalArgumentException(text);}
}
