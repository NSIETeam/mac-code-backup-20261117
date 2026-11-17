package cn.siyue.core;

import static cn.siyue.core.CouponOfferDtos.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CouponOfferRepository {
    private final JdbcTemplate jdbc;
    private final CouponClaimRepository claims;
    private final PiiCipher cipher;
    public CouponOfferRepository(JdbcTemplate jdbc,CouponClaimRepository claims,PiiCipher cipher) {this.jdbc=jdbc;this.claims=claims;this.cipher=cipher;}
    public Options options(Actor actor,int page,int size) {
        String where="co.active=1 AND co.deleted=0";List<Object> args=new ArrayList<>();
        if(actor.scope().equals("assigned")) {
            where+=" AND EXISTS (SELECT c.id FROM core_class c WHERE c.course_id=co.id AND c.deleted=0 AND (c.teacher_id=? "
                + "OR EXISTS (SELECT s.id FROM core_session s WHERE s.class_id=c.id AND s.teacher_id=? AND s.deleted=0)))";
            args.add(actor.id());args.add(actor.id());
        }
        long total=jdbc.queryForObject("SELECT COUNT(co.id) FROM core_course co WHERE "+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
        List<Choice> courses=jdbc.query("SELECT co.public_id,co.name FROM core_course co WHERE "+where+" ORDER BY co.id LIMIT ? OFFSET ?",
            (rs,n)->new Choice(rs.getString(1),rs.getString(2)),args.toArray());
        String campuses="SELECT cp.public_id,cp.name_cipher FROM core_campus cp WHERE cp.active=1 AND cp.deleted=0";
        Object[] campusArgs=new Object[0];
        if(actor.scope().equals("campus")) {campuses+=" AND cp.id IN (SELECT campus_id FROM core_principal_campus WHERE principal_id=? AND deleted=0)";campusArgs=new Object[]{actor.id()};}
        List<Choice> campus=jdbc.query(campuses+" ORDER BY cp.id LIMIT 1000",(rs,n)->new Choice(rs.getString(1),cipher.decrypt(rs.getString(2))),campusArgs);
        return new Options(courses,campus,total,page,size);
    }
    public Page list(Actor actor,int page,int size) {
        ScopeSql.Clause scope=readScope(actor);List<Object> args=new ArrayList<>(scope.args());
        long total=jdbc.queryForObject("SELECT COUNT(p.id) FROM core_coupon_offer p WHERE p.deleted=0 AND ("+scope.sql()+")",Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        List<Long> ids=jdbc.query("SELECT p.id FROM core_coupon_offer p WHERE p.deleted=0 AND ("+scope.sql()+") ORDER BY p.id DESC LIMIT ? OFFSET ?",
            (rs,n)->rs.getLong(1),args.toArray());return new Page(ids.stream().map(this::read).toList(),total,page,size);
    }
    public Offer read(long id) {
        return jdbc.queryForObject("SELECT p.public_id,p.name,p.description,p.template,c.public_id,p.start_date,p.end_date,p.discount_amount,"
            + "p.threshold_amount,p.total_limit,p.max_per_user,p.published,p.version FROM core_coupon_offer p LEFT JOIN core_campus c ON c.id=p.campus_id WHERE p.id=?",
            (rs,n)->new Offer(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getDate(6).toLocalDate(),
                rs.getDate(7).toLocalDate(),rs.getBigDecimal(8),rs.getBigDecimal(9),rs.getInt(10),rs.getInt(11),courseIds(id),rs.getBoolean(12),
                rs.getLong(13),claims.claims(id,null),claims.directUse(id,null)),id);
    }
    private List<String> courseIds(long id) {
        return jdbc.queryForList("SELECT c.public_id FROM core_coupon_offer_course x JOIN core_course c ON c.id=x.course_id "
            + "WHERE x.offer_id=? AND x.deleted=0 ORDER BY c.public_id",String.class,id);
    }
    public long authorized(Actor actor,String publicId,boolean lock) {
        Long id=jdbc.query("SELECT id FROM core_coupon_offer WHERE public_id=? AND deleted=0"+(lock?" FOR UPDATE":""),
            (rs,n)->rs.getLong(1),publicId).stream().findFirst().orElseThrow(()->invalid("优惠券不存在"));
        CouponClaimRepository.Offer offer=claims.offer(id,false);writeScope(actor,offer.campusId(),offer.ownerId());return id;
    }
    public Offer save(Actor actor,String publicId,Input input) {
        Long id=publicId==null?null:authorized(actor,publicId,true);
        if(id==null&&input.expectedVersion()!=null) throw invalid("新优惠券不接受已有版本");
        if(id!=null) {
            CouponClaimRepository.Offer old=claims.offer(id,false);version(old.version(),input.expectedVersion());
            if(input.total()<claims.claims(id,null)+claims.directUse(id,null)) throw invalid("发行量不能小于已领取和使用数量");
        }
        Long campus=campus(actor,input.campusId());List<Long> courseIds=resolveCourses(actor,input.courseIds());
        String scope=courseIds.isEmpty()?"全部商品":"指定课程";
        if(id==null) {
            publicId="COUPON_"+UUID.randomUUID();
            jdbc.update("INSERT INTO core_coupon_offer(public_id,owner_id,campus_id,name,description,template,scope_name,start_date,end_date,"
                + "discount_amount,threshold_amount,total_limit,max_per_user) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",publicId,actor.id(),campus,
                input.name().trim(),input.description()==null?"":input.description(),input.template(),scope,input.startDate(),input.endDate(),
                input.discountAmount(),input.thresholdAmount(),input.total(),input.maxPerUser());
            id=jdbc.queryForObject("SELECT id FROM core_coupon_offer WHERE public_id=?",Long.class,publicId);
        } else jdbc.update("UPDATE core_coupon_offer SET campus_id=?,name=?,description=?,template=?,scope_name=?,start_date=?,end_date=?,"
            + "discount_amount=?,threshold_amount=?,total_limit=?,max_per_user=?,published=0,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
            campus,input.name().trim(),input.description()==null?"":input.description(),input.template(),scope,input.startDate(),input.endDate(),
            input.discountAmount(),input.thresholdAmount(),input.total(),input.maxPerUser(),id);
        jdbc.update("UPDATE core_coupon_offer_course SET deleted=1,updated_at=UTC_TIMESTAMP(3) WHERE offer_id=?",id);
        for(Long course:courseIds) jdbc.update("INSERT INTO core_coupon_offer_course(offer_id,course_id) VALUES(?,?) "
            + "ON DUPLICATE KEY UPDATE deleted=0,updated_at=UTC_TIMESTAMP(3)",id,course);
        return read(id);
    }
    public Offer publication(Actor actor,String publicId,long expected,boolean publish) {
        long id=authorized(actor,publicId,true);CouponClaimRepository.Offer current=claims.offer(id,false);version(current.version(),expected);
        if(publish) {
            if(current.published()) throw invalid("优惠券已经发布");
            if(current.end().isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")))) throw invalid("优惠券已过期");
            resolveCourses(actor,courseIds(id));
            jdbc.update("UPDATE core_coupon_offer SET published=1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",id);
        } else jdbc.update("UPDATE core_coupon_offer SET published=0,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",id);
        return read(id);
    }
    private Long campus(Actor actor,String publicId) {
        if(publicId==null||publicId.isBlank()) {writeScope(actor,null,actor.id());return null;}
        Long id=jdbc.query("SELECT id FROM core_campus WHERE public_id=? AND active=1 AND deleted=0",(rs,n)->rs.getLong(1),publicId)
            .stream().findFirst().orElseThrow(()->invalid("校区不存在或已停用"));writeScope(actor,id,actor.id());return id;
    }
    private List<Long> resolveCourses(Actor actor,List<String> publicIds) {
        List<Long> result=new ArrayList<>();
        for(String publicId:publicIds) {
            List<Long> rows=jdbc.query("SELECT co.id FROM core_course co WHERE co.public_id=? AND co.active=1 AND co.deleted=0",
                (rs,n)->rs.getLong(1),publicId);
            if(rows.size()!=1) throw invalid("适用课程不存在或已停用");
            if(actor.scope().equals("assigned")&&jdbc.queryForObject("SELECT COUNT(c.id) FROM core_class c WHERE c.course_id=? AND c.deleted=0 "
                    + "AND (c.teacher_id=? OR EXISTS (SELECT s.id FROM core_session s WHERE s.class_id=c.id AND s.teacher_id=? AND s.deleted=0))",
                    Integer.class,rows.get(0),actor.id(),actor.id())==0) throw new BusinessException(ErrorCode.FORBIDDEN,"适用课程超出权限范围");
            result.add(rows.get(0));
        }
        return result;
    }
    private ScopeSql.Clause readScope(Actor actor) {
        return switch(actor.scope()) {
            case "all","own" -> new ScopeSql.Clause("1=1",List.of());
            case "campus" -> new ScopeSql.Clause("p.campus_id IS NULL OR p.campus_id IN (SELECT campus_id FROM core_principal_campus WHERE principal_id=? AND deleted=0)",List.of(actor.id()));
            case "assigned" -> new ScopeSql.Clause("p.owner_id=?",List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN,"账号数据范围无效");
        };
    }
    private void writeScope(Actor actor,Long campus,long owner) {
        if(actor.scope().equals("all")) return;
        if(campus==null) throw new BusinessException(ErrorCode.FORBIDDEN,"全校优惠券需要全部数据范围");
        if(actor.scope().equals("campus")&&jdbc.queryForObject("SELECT COUNT(id) FROM core_principal_campus WHERE principal_id=? AND campus_id=? AND deleted=0",
            Integer.class,actor.id(),campus)>0) return;
        if((actor.scope().equals("own")||actor.scope().equals("assigned"))&&owner==actor.id()) return;
        throw new BusinessException(ErrorCode.FORBIDDEN,"优惠券超出操作范围");
    }
    private void version(long current,Long expected) {if(expected==null||current!=expected) throw invalid("优惠券版本已变化，请刷新");}
    private BusinessException invalid(String text) {return new BusinessException(ErrorCode.INVALID_INPUT,text);}
}
