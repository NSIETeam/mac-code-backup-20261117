package cn.siyue.core;

import cn.siyue.core.CommerceDtos.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Transaction-bound catalog with class scope, relational membership and optimistic versions. */
@Repository
public class CommerceRepository {
    private final JdbcTemplate jdbc;
    public CommerceRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    record ClassLink(long id,long courseId,String publicId) { }
    record Target(long id,long version) { }
    private ScopeSql.Clause visible(Actor actor,String kind) {
        ScopeSql.Clause scope=ScopeSql.classes(actor);
        return new ScopeSql.Clause("EXISTS (SELECT 1 FROM core_commerce_"+kind+"_class x WHERE x."
            +kind+"_id=p.id AND x.active=1) AND NOT EXISTS (SELECT 1 FROM core_commerce_"+kind
            +"_class x JOIN core_class c ON c.id=x.class_id WHERE x."+kind
            +"_id=p.id AND x.active=1 AND NOT ("+scope.sql()+"))",scope.args());
    }
    private Target target(Actor actor,String id,String kind,boolean lock) {
        var scope=visible(actor,kind);var args=new ArrayList<Object>();args.add(id);args.addAll(scope.args());
        var rows=jdbc.query("SELECT p.id,p.version FROM core_commerce_"+kind+" p WHERE p.public_id=? AND ("
            +scope.sql()+")"+(lock?" FOR UPDATE":""),(rs,n)->new Target(rs.getLong(1),rs.getLong(2)),args.toArray());
        if(rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND,"商品或活动不存在或无权访问"); }
        return rows.get(0);
    }
    /** Rechecks scope before a previously stored receipt can be returned. */
    public void authorize(Actor actor,String id,String kind) { target(actor,id,kind,false); }
    public ProductPage products(Actor actor,int page,int pageSize) {
        return products(actor,page,pageSize,false);
    }
    public ProductPage products(Actor actor,int page,int pageSize,boolean publishedOnly) {
        var scope=publishedOnly?null:visible(actor,"product");
        String where=publishedOnly?"p.published=1 AND p.deleted=0":scope.sql();
        var args=new ArrayList<Object>();if(scope!=null)args.addAll(scope.args());
        int total=jdbc.queryForObject("SELECT COUNT(*) FROM core_commerce_product p WHERE "+where,Integer.class,args.toArray());
        args.add(pageSize);args.add((page-1)*pageSize);
        var ids=jdbc.query("SELECT p.id FROM core_commerce_product p WHERE "+where+" ORDER BY p.id DESC LIMIT ? OFFSET ?",
            (rs,n)->rs.getLong(1),args.toArray());
        return new ProductPage(ids.stream().map(this::product).toList(),total,page,pageSize);
    }
    public PromotionPage promotions(Actor actor,int page,int pageSize) {
        return promotions(actor,page,pageSize,null);
    }
    public PromotionPage promotions(Actor actor,int page,int pageSize,String type) {
        var scope=visible(actor,"promotion");var args=new ArrayList<>(scope.args());
        String where=scope.sql();
        if(type!=null) { where+=" AND p.type=?";args.add(type); }
        int total=jdbc.queryForObject("SELECT COUNT(*) FROM core_commerce_promotion p WHERE "+where,Integer.class,args.toArray());
        args.add(pageSize);args.add((page-1)*pageSize);
        var ids=jdbc.query("SELECT p.id FROM core_commerce_promotion p WHERE "+where+" ORDER BY p.id DESC LIMIT ? OFFSET ?",
            (rs,n)->rs.getLong(1),args.toArray());
        return new PromotionPage(ids.stream().map(this::promotion).toList(),total,page,pageSize);
    }
    public ClassPage courseClasses(Actor actor,String courseId,int page,int pageSize) {
        var scope=ScopeSql.classes(actor);var args=new ArrayList<Object>();args.add(courseId);args.addAll(scope.args());
        String where="co.public_id=? AND c.active=1 AND c.deleted=0 AND co.active=1 AND co.deleted=0 AND ("+scope.sql()+")";
        Integer total=jdbc.queryForObject("SELECT COUNT(*) FROM core_class c JOIN core_course co ON co.id=c.course_id WHERE "+where,Integer.class,args.toArray());
        args.add(pageSize);args.add((page-1)*pageSize);
        var rows=jdbc.query("SELECT c.public_id,co.public_id course_public_id,c.name,c.room,CASE WHEN EXISTS (SELECT 1 FROM core_session s WHERE s.class_id=c.id AND s.deleted=0 AND s.starts_at>UTC_TIMESTAMP(3)) THEN 'upcoming' ELSE 'current' END phase FROM core_class c JOIN core_course co ON co.id=c.course_id WHERE "+where+" ORDER BY c.public_id LIMIT ? OFFSET ?",
            (rs,n)->new ClassChoice(rs.getString("public_id"),rs.getString("course_public_id"),rs.getString("name"),rs.getString("phase"),rs.getString("room")),args.toArray());
        return new ClassPage(rows,total==null?0:total,page,pageSize);
    }
    private List<ClassLink> classes(Actor actor,Selection selection) {
        var scope=ScopeSql.classes(actor);var result=new ArrayList<ClassLink>();
        for(String id:selection.classIds().stream().sorted().toList()) {
            var args=new ArrayList<Object>(List.of(id,selection.courseId()));args.addAll(scope.args());
            var rows=jdbc.query("SELECT c.id,c.course_id,c.public_id FROM core_class c JOIN core_course co ON co.id=c.course_id "
                +"WHERE c.public_id=? AND co.public_id=? AND c.active=1 AND c.deleted=0 AND co.active=1 AND co.deleted=0 AND ("
                +scope.sql()+") FOR UPDATE",(rs,n)->new ClassLink(rs.getLong(1),rs.getLong(2),rs.getString(3)),args.toArray());
            if(rows.size()!=1) { throw new BusinessException(ErrorCode.INVALID_INPUT,"所选班级无效、不属于该课程或超出权限范围"); }
            result.add(rows.get(0));
        }
        return result;
    }
    public Product saveProduct(Actor actor,String publicId,ProductInput input) {
        Target current=publicId==null?null:target(actor,publicId,"product",true);
        checkVersion(current,input.expectedVersion());
        var links=classes(actor,new Selection(input.courseId(),input.classIds()));
        long courseId=links.get(0).courseId();
        if(input.published() && jdbc.queryForObject("SELECT COUNT(*) FROM core_course c WHERE c.id=? AND c.teaching_published=1 "
                +"AND EXISTS(SELECT v.id FROM core_course_publication v WHERE v.course_id=c.id AND v.course_version=c.version)",Integer.class,courseId)!=1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"课程须先发布当前教学版本，再上架商城商品");
        }
        if(current==null) {
            publicId="PRODUCT_"+UUID.randomUUID();
            try {
                jdbc.update("INSERT INTO core_commerce_product(public_id,course_id,owner_id,name,published) VALUES(?,?,?,?,?)",
                    publicId,courseId,actor.id(),input.name().trim(),input.published());
            } catch(DuplicateKeyException ex) { throw new BusinessException(ErrorCode.ALREADY_EXISTS,"该课程已有商城商品，请编辑现有商品"); }
            current=new Target(jdbc.queryForObject("SELECT id FROM core_commerce_product WHERE public_id=?",Long.class,publicId),1);
        } else {
            long oldCourse=jdbc.queryForObject("SELECT course_id FROM core_commerce_product WHERE id=?",Long.class,current.id());
            if(oldCourse!=courseId) { throw new BusinessException(ErrorCode.INVALID_INPUT,"已有商品不能变更所属课程"); }
            requirePromotionCoverage(current.id(),links,input.published());
            jdbc.update("UPDATE core_commerce_product SET name=?,published=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                input.name().trim(),input.published(),current.id());
        }
        replaceLinks("product",current.id(),links);
        return product(current.id());
    }
    /** Changes publication only, deriving product text from the locked course rather than caller input. */
    public Product publishCourse(Actor actor,AcademicCatalogRepository.CourseStatusRow course,
            PublicationInput input,boolean publish) {
        var ids=jdbc.queryForList("SELECT public_id FROM core_commerce_product WHERE course_id=? AND deleted=0 FOR UPDATE",
            String.class,course.id());
        String id=ids.isEmpty()?null:ids.get(0);
        Product current=id==null?null:product(target(actor,id,"product",false).id());
        if(!publish) {
            if(current==null) { throw new BusinessException(ErrorCode.INVALID_INPUT,"课程尚未上架"); }
            long productId=target(actor,id,"product",false).id();
            var links=jdbc.query("SELECT c.id,c.course_id,c.public_id FROM core_commerce_product_class pc "
                +"JOIN core_class c ON c.id=pc.class_id WHERE pc.product_id=? AND pc.active=1",
                (rs,n)->new ClassLink(rs.getLong(1),rs.getLong(2),rs.getString(3)),productId);
            requirePromotionCoverage(productId,links,false);
            jdbc.update("UPDATE core_commerce_product SET published=0,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",productId);
            return product(productId);
        }
        if(!course.active()) { throw new BusinessException(ErrorCode.INVALID_INPUT,"课程已停用"); }
        List<String> selected=input.classIds();
        if(selected==null) {
            var scope=ScopeSql.classes(actor);var args=new ArrayList<Object>();args.add(course.id());args.addAll(scope.args());
            selected=jdbc.queryForList("SELECT c.public_id FROM core_class c WHERE c.course_id=? AND c.active=1 "
                +"AND c.deleted=0 AND ("+scope.sql()+") ORDER BY c.public_id LIMIT 1001",String.class,args.toArray());
        }
        if(selected.isEmpty()||selected.size()>1000) { throw new BusinessException(ErrorCode.INVALID_INPUT,"请选择1至1000个适用班级"); }
        String name=jdbc.queryForObject("SELECT name FROM core_course WHERE id=?",String.class,course.id());
        Product saved=saveProduct(actor,id,new ProductInput(course.publicId(),name,selected,true,current==null?null:current.version()));
        jdbc.update("UPDATE core_commerce_product p JOIN core_course c ON c.id=p.course_id "
            +"SET p.storefront_title=c.name,p.storefront_grade=c.grade,p.storefront_subject=c.subject,"
            +"p.storefront_course_type=c.course_type,p.storefront_price=c.price,p.storefront_course_version=c.version "
            +"WHERE p.public_id=?",saved.id());
        return saved;
    }
    private void requirePromotionCoverage(long productId,List<ClassLink> links,boolean published) {
        var required=jdbc.queryForList("SELECT DISTINCT pc.class_id FROM core_commerce_promotion_class pc "
            +"JOIN core_commerce_promotion p ON p.id=pc.promotion_id JOIN core_class c ON c.id=pc.class_id "
            +"JOIN core_commerce_product product ON product.course_id=c.course_id "
            +"WHERE product.id=? AND pc.active=1 AND p.published=1 AND p.end_date>=DATE(UTC_TIMESTAMP(3)+INTERVAL 8 HOUR)",Long.class,productId);
        if(!required.isEmpty() && (!published || !links.stream().map(ClassLink::id).toList().containsAll(required))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"仍有已发布活动使用所选班级，请先调整或停用活动");
        }
    }
    public Promotion savePromotion(Actor actor,String publicId,PromotionInput input) {
        Target current=publicId==null?null:target(actor,publicId,"promotion",true);
        checkVersion(current,input.expectedVersion());
        var links=new ArrayList<ClassLink>();
        for(Selection selection:input.selections().stream().sorted(java.util.Comparator.comparing(Selection::courseId)).toList()) {
            lockPublishedProduct(selection.courseId());
            var selected=classes(actor,selection);requirePublished(selected);links.addAll(selected);
        }
        if(current==null) {
            publicId="PROMOTION_"+UUID.randomUUID();
            jdbc.update("INSERT INTO core_commerce_promotion(public_id,owner_id,type,name,description,start_date,end_date,discount_amount,"
                +"group_size,valid_hours,stack_coupon,participants,published) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                publicId,actor.id(),input.type(),input.name().trim(),description(input),input.startDate(),input.endDate(),input.discountAmount(),
                input.groupSize(),input.validHours(),input.stackCoupon(),String.join(",",input.participants()),input.published());
            current=new Target(jdbc.queryForObject("SELECT id FROM core_commerce_promotion WHERE public_id=?",Long.class,publicId),1);
        } else {
            String type=jdbc.queryForObject("SELECT type FROM core_commerce_promotion WHERE id=?",String.class,current.id());
            if(!type.equals(input.type())) { throw new BusinessException(ErrorCode.INVALID_INPUT,"活动类型不能变更"); }
            jdbc.update("UPDATE core_commerce_promotion SET name=?,description=?,start_date=?,end_date=?,discount_amount=?,group_size=?,"
                +"valid_hours=?,stack_coupon=?,participants=?,published=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                input.name().trim(),description(input),input.startDate(),input.endDate(),input.discountAmount(),input.groupSize(),input.validHours(),
                input.stackCoupon(),String.join(",",input.participants()),input.published(),current.id());
        }
        replaceLinks("promotion",current.id(),links);return promotion(current.id());
    }
    private void lockPublishedProduct(String courseId) {
        var rows=jdbc.queryForList("SELECT p.id FROM core_commerce_product p JOIN core_course co ON co.id=p.course_id "
            +"WHERE co.public_id=? AND p.published=1 FOR UPDATE",Long.class,courseId);
        if(rows.isEmpty()) { throw new BusinessException(ErrorCode.INVALID_INPUT,"活动只能选择已上架商品的班级"); }
    }
    private void requirePublished(List<ClassLink> links) {
        for(ClassLink link:links) {
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM core_commerce_product_class pc JOIN core_commerce_product p ON p.id=pc.product_id "
                +"WHERE pc.class_id=? AND pc.active=1 AND p.published=1",Integer.class,link.id());
            if(count==null || count!=1) { throw new BusinessException(ErrorCode.INVALID_INPUT,"活动班级必须属于该商品当前可售范围"); }
        }
    }
    private void replaceLinks(String kind,long id,List<ClassLink> links) {
        jdbc.update("UPDATE core_commerce_"+kind+"_class SET active=0 WHERE "+kind+"_id=?",id);
        for(ClassLink link:links) {
            jdbc.update("INSERT INTO core_commerce_"+kind+"_class("+kind+"_id,class_id,active) VALUES(?,?,1) "
                +"ON DUPLICATE KEY UPDATE active=1",id,link.id());
        }
    }
    private Product product(long id) {
        return jdbc.queryForObject("SELECT p.public_id,p.name,p.published,p.version,co.public_id course_public_id FROM core_commerce_product p JOIN core_course co ON co.id=p.course_id WHERE p.id=?",
            (rs,n)->new Product(rs.getString("public_id"),rs.getString("course_public_id"),rs.getString("name"),
                selectionMap("product",id).values().stream().flatMap(List::stream).toList(),rs.getBoolean("published"),rs.getLong("version")),id);
    }
    private Promotion promotion(long id) {
        var selections=selectionMap("promotion",id).entrySet().stream().map(e->new Selection(e.getKey(),e.getValue())).toList();
        return jdbc.queryForObject("SELECT public_id,type,name,description,start_date,end_date,discount_amount,group_size,valid_hours,stack_coupon,participants,published,version FROM core_commerce_promotion WHERE id=?",(rs,n)->new Promotion(rs.getString("public_id"),
            rs.getString("type"),rs.getString("name"),rs.getString("description"),rs.getDate("start_date").toLocalDate(),rs.getDate("end_date").toLocalDate(),
            rs.getBigDecimal("discount_amount"),(Integer)rs.getObject("group_size"),(Integer)rs.getObject("valid_hours"),
            rs.getBoolean("stack_coupon"),Arrays.asList(rs.getString("participants").split(",")),selections,
            rs.getBoolean("published"),rs.getLong("version")),id);
    }
    private Map<String,List<String>> selectionMap(String kind,long id) {
        Map<String,List<String>> result=new LinkedHashMap<>();
        jdbc.query("SELECT co.public_id course_id,c.public_id class_id FROM core_commerce_"+kind+"_class x "
            +"JOIN core_class c ON c.id=x.class_id JOIN core_course co ON co.id=c.course_id WHERE x."+kind
            +"_id=? AND x.active=1 ORDER BY co.public_id,c.public_id",rs->{
                result.computeIfAbsent(rs.getString("course_id"),k->new ArrayList<>()).add(rs.getString("class_id"));
            },id);
        return result;
    }
    private void checkVersion(Target current,Long expected) {
        if(current==null && expected!=null) { throw new BusinessException(ErrorCode.INVALID_INPUT,"新建时不能指定已有版本"); }
        if(current!=null && (expected==null || expected!=current.version())) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE,"商品或活动已更新，请刷新后重试");
        }
    }
    private String description(PromotionInput input) {
        return input.description() == null ? "" : input.description().trim();
    }
    /** Commerce checkout callers must supply a product and class; ordinary academic enrollment has no implicit mall restriction. */
    public void requireSellableClass(String productId,String classId) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM core_commerce_product p JOIN core_commerce_product_class pc ON pc.product_id=p.id "
            +"JOIN core_class c ON c.id=pc.class_id JOIN core_course co ON co.id=p.course_id "
            +"WHERE p.public_id=? AND c.public_id=? AND p.published=1 AND pc.active=1 AND c.active=1 AND c.deleted=0 AND co.active=1 AND co.deleted=0",
            Integer.class,productId,classId);
        if(count==null || count!=1) { throw new BusinessException(ErrorCode.INVALID_INPUT,"该班级未在此商品上架销售"); }
    }
    /** Eligibility check is additive to pricing, payment and customer-qualification rules. */
    public void requirePromotionClass(String promotionId,String classId,java.time.LocalDate day) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM core_commerce_promotion p JOIN core_commerce_promotion_class pc ON pc.promotion_id=p.id "
            +"JOIN core_class c ON c.id=pc.class_id JOIN core_commerce_product product ON product.course_id=c.course_id "
            +"JOIN core_commerce_product_class sale ON sale.product_id=product.id AND sale.class_id=c.id "
            +"JOIN core_course co ON co.id=c.course_id WHERE p.public_id=? AND c.public_id=? AND p.published=1 AND pc.active=1 "
            +"AND p.start_date<=? AND p.end_date>=? AND sale.active=1 AND product.published=1 AND c.active=1 AND c.deleted=0 AND co.active=1 AND co.deleted=0",
            Integer.class,promotionId,classId,day,day);
        if(count==null || count!=1) { throw new BusinessException(ErrorCode.INVALID_INPUT,"该活动当前不适用于所选班级"); }
    }
}
