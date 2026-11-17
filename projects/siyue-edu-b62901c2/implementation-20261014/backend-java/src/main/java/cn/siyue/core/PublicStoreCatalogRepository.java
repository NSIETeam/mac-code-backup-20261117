package cn.siyue.core;

import cn.siyue.core.CommerceCheckoutDtos.PublicStoreCatalog;
import cn.siyue.core.CommerceCheckoutDtos.PublicStoreClass;
import cn.siyue.core.CommerceCheckoutDtos.PublicStoreProduct;
import cn.siyue.core.CommerceCheckoutDtos.PublicStorePromotion;
import cn.siyue.core.CommerceCheckoutDtos.PublicStoreSession;
import cn.siyue.core.PublicStorefrontDtos.Content;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only projection of currently public course products and promotions. */
@Repository
public class PublicStoreCatalogRepository {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final ObjectMapper mapper;

    public PublicStoreCatalogRepository(JdbcTemplate jdbc, PiiCipher cipher, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.mapper = mapper;
    }

    public PublicStoreCatalog catalog() {
        Map<String, ProductBuilder> products = new LinkedHashMap<>();
        List<ClassRow> classes = jdbc.query("SELECT co.public_id course_id,co.course_type,co.price,co.version course_version,"
            + "co.teaching_published AND EXISTS(SELECT v.id FROM core_course_publication v WHERE v.course_id=co.id AND v.course_version=co.version) teaching_current,p.version product_version,p.storefront_course_version,p.storefront_title,p.storefront_grade,"
            + "p.storefront_subject,p.storefront_course_type,p.storefront_price,p.storefront_image,p.storefront_description,"
            + "c.public_id class_id,c.name class_name,c.id class_internal_id,c.duration_minutes,"
            + "cp.public_id campus_public_id,cp.name_cipher campus_name_cipher,c.capacity FROM core_commerce_product p "
            + "JOIN core_course co ON co.id=p.course_id AND co.active=1 AND co.deleted=0 "
            + "JOIN core_commerce_product_class pc ON pc.product_id=p.id AND pc.active=1 AND pc.deleted=0 "
            + "JOIN core_class c ON c.id=pc.class_id AND c.active=1 AND c.deleted=0 "
            + "JOIN core_campus cp ON cp.id=c.campus_id AND cp.active=1 AND cp.deleted=0 "
            + "WHERE p.published=1 AND p.deleted=0 AND p.storefront_title IS NOT NULL ORDER BY co.public_id,c.public_id",
            (rs, row) -> new ClassRow(rs.getString("course_id"), rs.getString("course_type"),
                rs.getBigDecimal("price"), rs.getLong("course_version"), rs.getLong("product_version"),
                (Long) rs.getObject("storefront_course_version"), new Content(rs.getString("storefront_title"),
                    rs.getString("storefront_grade"), rs.getString("storefront_subject"),
                    rs.getString("storefront_course_type"), rs.getBigDecimal("storefront_price"),
                    rs.getString("storefront_image"), rs.getString("storefront_description")),
                rs.getString("class_id"), rs.getString("class_name"),
                rs.getLong("class_internal_id"), rs.getString("campus_public_id"),
                cipher.decrypt(rs.getString("campus_name_cipher")),
                rs.getInt("capacity"), rs.getInt("duration_minutes"),rs.getBoolean("teaching_current")));
        for (ClassRow row : classes) { addClass(products, row); }
        return new PublicStoreCatalog(products.values().stream().map(ProductBuilder::build).toList(),
            promotions(), decor(), false, "manual_verified_receipt", "complete_group_registration");
    }

    private void addClass(Map<String, ProductBuilder> products, ClassRow row) {
        ProductBuilder product = products.computeIfAbsent(row.courseId(), ignored -> new ProductBuilder(row));
        int seats = new SeatAvailabilityRepository(jdbc, cipher).remaining(row.classInternalId());
        List<SessionSeat> sessions = sessions(row.classInternalId(), row.capacity(), 0);
        product.classes().add(new PublicStoreClass(row.classId(), row.courseId(), row.className(), row.campusId(),
            row.campusName(), row.durationMinutes(), seats,
            sessions.stream().map(SessionSeat::output).toList()));
    }

    private List<PublicStorePromotion> promotions() {
        LocalDate today = LocalDate.now(SHANGHAI);
        List<PromotionRow> rows = jdbc.query("SELECT p.id,p.public_id,p.type,p.name,p.description,p.start_date,p.end_date,"
            + "p.discount_amount,p.threshold_amount,p.group_size,p.valid_hours,p.stack_coupon "
            + "FROM core_commerce_promotion p WHERE p.published=1 AND p.deleted=0 "
            + "AND p.start_date<=? AND p.end_date>=? AND p.type IN ('group','joint') ORDER BY p.id",
            (rs, row) -> new PromotionRow(rs.getLong("id"), rs.getString("public_id"), rs.getString("type"),
                rs.getString("name"), rs.getString("description"), rs.getDate("start_date").toLocalDate(), rs.getDate("end_date").toLocalDate(),
                rs.getBigDecimal("discount_amount"), rs.getBigDecimal("threshold_amount"),
                (Integer) rs.getObject("group_size"), (Integer) rs.getObject("valid_hours"),
                rs.getBoolean("stack_coupon")), today, today);
        List<PublicStorePromotion> result = new ArrayList<>();
        for (PromotionRow row : rows) { result.add(publicPromotion(row)); }
        return List.copyOf(result);
    }

    private PublicStorePromotion publicPromotion(PromotionRow row) {
        List<String> courses = jdbc.query("SELECT DISTINCT co.public_id FROM core_commerce_promotion_class pc "
            + "JOIN core_class c ON c.id=pc.class_id JOIN core_course co ON co.id=c.course_id "
            + "WHERE pc.promotion_id=? AND pc.active=1 AND c.deleted=0 AND c.active=1 ORDER BY co.public_id",
            (rs, index) -> rs.getString(1), row.id());
        List<String> classes = jdbc.query("SELECT c.public_id FROM core_commerce_promotion_class pc "
            + "JOIN core_class c ON c.id=pc.class_id WHERE pc.promotion_id=? AND pc.active=1 "
            + "AND c.deleted=0 AND c.active=1 ORDER BY c.public_id", (rs, index) -> rs.getString(1), row.id());
        return new PublicStorePromotion(row.publicId(), row.type(), row.name(), row.start(), row.end(),
            row.discount(), row.threshold(), row.groupSize(), row.validHours(), row.stackCoupon(), courses, classes,
            row.description(), row.type().equals("group") ? "整团登记，满员后统一核对收款" : "同一学生联报活动内全部课程");
    }

    private Map<String,Object> decor() {
        return jdbc.query("SELECT decor FROM core_public_storefront_config WHERE singleton_key=1",
            (rs, row) -> readMap(rs.getString(1))).stream().findFirst().orElse(Map.of());
    }

    private Map<String,Object> readMap(String json) {
        try { return mapper.readValue(json, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)); }
        catch (JsonProcessingException error) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "商城装饰配置损坏"); }
    }

    private List<SessionSeat> sessions(long classId, int classCapacity, int occupied) {
        return jdbc.query("SELECT s.public_id,s.starts_at,s.ends_at,COALESCE(s.room,c.room) room,s.status,"
            + "LEAST(COALESCE(NULLIF(s.class_capacity_override,0),c.capacity),"
            + "COALESCE(NULLIF(s.capacity_override,0),c.capacity),COALESCE(r.capacity,c.capacity)) seat_limit,"
            + "COALESCE(r.virtual_seats,0) virtual_seats FROM core_session s "
            + "JOIN core_class c ON c.id=s.class_id LEFT JOIN core_campus_room r "
            + "ON r.campus_id=COALESCE(s.campus_id,c.campus_id) AND r.name=COALESCE(s.room,c.room) AND r.deleted=0 "
            + "WHERE s.class_id=? AND s.deleted=0 ORDER BY s.starts_at,s.id",
            (rs, row) -> session(rs, rs.getInt("seat_limit"), rs.getInt("virtual_seats"), occupied), classId);
    }

    private SessionSeat session(ResultSet rs, int capacity, int virtualSeats, int occupied) throws SQLException {
        Instant start = rs.getTimestamp("starts_at").toInstant();
        Instant end = rs.getTimestamp("ends_at").toInstant();
        java.time.ZonedDateTime localStart = start.atZone(SHANGHAI);
        java.time.ZonedDateTime localEnd = end.atZone(SHANGHAI);
        String time = localStart.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")) + "–"
            + localEnd.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"));
        PublicStoreSession output = new PublicStoreSession(rs.getString("public_id"), start, end,
            localStart.toLocalDate(), time, rs.getString("room"), rs.getString("status"));
        return new SessionSeat(output, capacity, virtualSeats, occupied);
    }

    private record ClassRow(String courseId, String sourceCourseType, BigDecimal sourcePrice, long courseVersion,
            long productVersion, Long contentCourseVersion, Content content, String classId, String className,
            long classInternalId, String campusId, String campusName, int capacity, int durationMinutes,boolean teachingCurrent) { }
    private record PromotionRow(long id, String publicId, String type, String name, String description, LocalDate start,
            LocalDate end, BigDecimal discount, BigDecimal threshold, Integer groupSize, Integer validHours,
            boolean stackCoupon) { }
    private record SessionSeat(PublicStoreSession output, int capacity, int virtualSeats, int occupied) { }

    private static final class ProductBuilder {
        private final ClassRow course;
        private final List<PublicStoreClass> classes = new ArrayList<>();
        private ProductBuilder(ClassRow course) { this.course = course; }
        private List<PublicStoreClass> classes() { return classes; }
        private PublicStoreProduct build() {
            Content content = course.content();
            boolean available = course.teachingCurrent() && course.contentCourseVersion() != null
                && course.contentCourseVersion() == course.courseVersion()
                && content.price() != null && content.price().compareTo(course.sourcePrice()) == 0
                && content.courseType().equals(course.sourceCourseType()) && !classes.isEmpty();
            String reason = available ? null : "销售或教学版本待重新发布，暂不能提交报名申请";
            return new PublicStoreProduct(course.courseId(), content.title(), content.title(), content.grade(),
                content.subject(), content.courseType(), content.price().movePointRight(2).longValueExact(),
                course.contentCourseVersion() == null ? 0 : course.contentCourseVersion(), available, reason,
                content.image(), content.description(), classes.stream().map(PublicStoreClass::id).toList(),
                List.copyOf(classes));
        }
    }
}
