package cn.siyue.core;

import cn.siyue.core.AcademicDtos.StudentInput;
import cn.siyue.core.CommerceCheckoutDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static cn.siyue.core.CommerceCheckoutValues.*;

/** Stores staff checkout quotes and creates their enrollment/order group atomically. */
@Repository
public class CommerceCheckoutRepository {
    record Buyer(int index, Long selectedStudent, Long resolvedStudent, String name,
            String phone, String grade) { }
    private record BuyerProfile(long id, String publicId, String name, String phone, String grade,
            Long campusId, Long ownerId, Long assigneeId) { }
    record ProductClass(long productId, String productPublicId, long productVersion,
            long courseId, String coursePublicId, String courseName, String grade, String subject,
            BigDecimal price, long courseVersion, long classId, String classPublicId, long campusId,
            String campusPublicId, String className, int capacity, long classVersion) { }
    record Line(int index, int buyerIndex, ProductClass item, Long promotionId,
            BigDecimal base, BigDecimal couponDiscount, BigDecimal promotionDiscount,
            BigDecimal amount, String signature, CommerceCheckoutCouponRepository.Snapshot coupon) { }
    record Promotion(long id, String publicId, String type, long version, LocalDate start,
            LocalDate end, BigDecimal discount, BigDecimal threshold, Integer groupSize,
            Integer validHours, boolean stackCoupon, int totalLimit, int maxPerUser,
            int usageCount, Set<String> courseIds, Set<String> classIds) { }
    private record PromotionTerms(String publicId, String type, long version, LocalDate start,
            LocalDate end, BigDecimal discount, BigDecimal threshold, Integer groupSize,
            Integer validHours, boolean stackCoupon, int totalLimit, int maxPerUser,
            List<String> courseIds, List<String> classIds) { }
    private record PromotionBase(long id, String publicId, String type, long version, LocalDate start,
            LocalDate end, BigDecimal discount, BigDecimal threshold, Integer groupSize,
            Integer validHours, boolean stackCoupon, int totalLimit, int maxPerUser, int usageCount) { }
    record QuoteSnapshot(String type, String promotionId, List<String> buyers,
            List<String> lines, BigDecimal baseAmount, BigDecimal discountAmount, BigDecimal totalAmount) { }
    record QuoteBuild(Actor actor, CreateInput input, List<Buyer> buyers, List<Line> lines,
            Promotion promotion, String promotionSignature, String quoteHash, QuoteSnapshot quoteSnapshot,
            BigDecimal base, BigDecimal discount, BigDecimal total, Instant now, Instant expires) { }
    private record QuoteHashInput(CreateInput input, List<Buyer> buyers, List<Line> lines,
            BigDecimal base, BigDecimal discount, BigDecimal total) { }
    private record Lesson(long id, int sequence, String title) { }
    private record EnrollmentOrder(String orderId, String enrollmentId) { }
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final StudentProfileRepository students;
    private final ObjectMapper mapper;
    private final MutationRepository mutations;
    private final CommerceCheckoutPersistenceRepository persistence;
    private final CommerceCheckoutPaymentRepository payments;
    private final CommerceCheckoutClassFingerprintRepository fingerprints;
    private final CommerceCheckoutReceiptRepository receiptRepository;

    public CommerceCheckoutRepository(JdbcTemplate jdbc, PiiCipher cipher,
            StudentProfileRepository students, ObjectMapper mapper, MutationRepository mutations) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.students = students;
        this.mapper = mapper;
        this.mutations = mutations;
        this.persistence = new CommerceCheckoutPersistenceRepository(jdbc, cipher, mapper);
        this.payments = new CommerceCheckoutPaymentRepository(jdbc, cipher);
        this.fingerprints = new CommerceCheckoutClassFingerprintRepository(jdbc, mutations, mapper);
        this.receiptRepository = new CommerceCheckoutReceiptRepository(jdbc);
    }

    /** Creates an immutable, expiring quote without reserving class capacity. */
    public CheckoutReceipt create(Actor actor, CreateInput input) {
        QuoteBuild quote = buildQuote(actor, input);
        String quoteHash = quote.quoteHash();
        if (input.expectedQuoteHash() != null && !input.expectedQuoteHash().equals(quoteHash)) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE, "已展示的商城报价已变化");
        }
        CheckoutState row = insertCheckout(quote, null);
        return receiptRepository.receipt(row, false);
    }

    /** Returns a visitor-bound quote without creating an order or checkout row. */
    public PublicStoreQuote publicQuote(CreateInput input, String visitorHash) {
        validatePublicInput(input);
        QuoteBuild quote = buildQuote(null, input);
        return publicQuote(quote, visitorHash);
    }

    /** Persists a visitor-bound application only after the displayed quote is revalidated. */
    public CheckoutReceipt createPublic(CreateInput input, String visitorHash) {
        validatePublicInput(input);
        QuoteBuild quote = buildQuote(null, input);
        String boundHash = visitorQuoteHash(visitorHash, quote.quoteHash());
        if (!boundHash.equals(input.expectedQuoteHash())) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE, "已展示报价发生变化或缺少校验值，请重新报价");
        }
        QuoteBuild bound = new QuoteBuild(null, quote.input(), quote.buyers(), quote.lines(), quote.promotion(),
            quote.promotionSignature(), boundHash, quote.quoteSnapshot(), quote.base(), quote.discount(),
            quote.total(), quote.now(), quote.expires());
        CheckoutState row = insertCheckout(bound, visitorHash);
        return receiptRepository.receipt(row, false);
    }

    /** Reads one public request only when its signed visitor-session fingerprint matches. */
    public CheckoutReceipt publicDetail(String checkoutId, String visitorHash) {
        return publicDetail(checkoutId, List.of(visitorHash));
    }

    /** Reads a migrated receipt using only fingerprints derived server-side from the verified visitor cookie. */
    public CheckoutReceipt publicDetail(String checkoutId, List<String> visitorHashes) {
        return receiptRepository.receipt(persistence.publicDetail(checkoutId, visitorHashes), false);
    }

    private PublicStoreQuote publicQuote(QuoteBuild quote, String visitorHash) {
        String boundHash = visitorQuoteHash(visitorHash, quote.quoteHash());
        return new PublicStoreQuote(boundHash, quote.input().type(), quote.base(), quote.discount(), quote.total(),
            quote.lines().stream().map(line -> new QuoteLine(line.index(), line.buyerIndex(),
                line.item().classPublicId(), line.item().coursePublicId(), line.item().campusPublicId(),
                line.base(), line.couponDiscount(), line.promotionDiscount(), line.amount())).toList(), false);
    }

    private String visitorQuoteHash(String visitorHash, String quoteHash) {
        return mutations.hash("public-store:" + visitorHash + ":" + quoteHash);
    }

    private void validatePublicInput(CreateInput input) {
        validateCreate(input);
        if (input.buyers().stream().anyMatch(buyer -> buyer.studentId() != null
                || buyer.name() == null || buyer.phone() == null)) {
            throw new BusinessException(ErrorCode.IDENTITY_VERIFICATION_REQUIRED,
                "公开申请不能指定已有学生编号，须填写申请人信息");
        }
    }

    private QuoteBuild buildQuote(Actor actor, CreateInput input) {
        validateCreate(input);
        List<Buyer> buyers = resolveQuoteBuyers(actor, input.buyers());
        List<ProductClass> items = resolveClasses(actor, input.items());
        Promotion promotion = resolvePromotion(input, items, buyers.size(), false);
        List<Line> lines = priceLines(actor, input, buyers, items, promotion);
        new CommerceCheckoutAdmissionRepository(jdbc,cipher).requireQuoteCapacity(lines);
        BigDecimal base = sum(lines.stream().map(Line::base).toList());
        BigDecimal discount = sum(lines.stream().map(line -> line.couponDiscount().add(line.promotionDiscount())).toList());
        BigDecimal total = sum(lines.stream().map(Line::amount).toList());
        if (base.subtract(discount).compareTo(total) != 0) { invalid("商城报价金额分摊不一致"); }
        String promotionSignature = promotion == null ? null : promotionSignature(promotion);
        QuoteSnapshot snapshot = quoteSnapshot(new QuoteHashInput(input, buyers, lines, base, discount, total));
        String quoteHash = quoteHash(snapshot);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        return new QuoteBuild(actor, input, buyers, lines, promotion, promotionSignature, quoteHash, snapshot,
            base, discount, total, now, expiry(input.type(), promotion, now));
    }

    /** Rechecks quotes and creates all students, unpaid orders and pending enrollments in one transaction. */
    public CheckoutReceipt accept(Actor actor, String checkoutId, AcceptInput input) {
        CheckoutState checkout = checkout(actor, checkoutId, true);
        if (checkout.version() != input.expectedVersion()) { throw stale("商城申请已更新"); }
        if (!checkout.status().equals("awaiting_verification")) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "商城申请已经核对或已取消");
        }
        if (!Instant.now().isBefore(checkout.expiresAt().toInstant())) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE, "商城申请已过期，请重新报价");
        }
        List<Buyer> buyers = buyers(checkout.id(), true);
        List<Line> lines = lines(checkout.id(), true);
        verifyQuote(checkout, buyers, lines);
        List<String> selected = selectedStudents(input, buyers.size());
        Map<Integer, BuyerProfile> profiles = resolveAcceptedBuyers(actor, checkout.id(), buyers, selected);
        ensureUniqueStudents(profiles);
        new CommerceCheckoutAdmissionRepository(jdbc,cipher).lockClasses(lines);
        verifyCurrentLines(actor, lines, checkout);
        Promotion promotion = lockAndVerifyPromotion(checkout, lines, buyers.size());
        checkPromotionQuota(promotion, profiles, checkout.id(), true);
        persistResolvedStudents(checkout.id(), profiles);
        List<EnrollmentOrder> results = new ArrayList<>();
        for (Line line : lines) {
            BuyerProfile profile = profiles.get(line.buyerIndex());
            new CommerceCheckoutAdmissionRepository(jdbc,cipher).requireStudentCapacity(profile.id(),line.item().classId());
            results.add(createOrderAndEnrollment(actor, checkout, line, profile));
        }
        acceptCheckout(actor, checkout, promotion);
        return receiptRepository.receipt(checkout(actor, checkoutId, false), false);
    }

    /** Confirms exact aggregate offline proceeds, with one append-only receipt per positive order balance. */
    public CheckoutReceipt confirmReceipt(Actor actor, String checkoutId, ConfirmReceiptInput input) {
        CheckoutState checkout = checkout(actor, checkoutId, true);
        if (checkout.version() != input.expectedVersion()) { throw stale("商城申请已更新"); }
        if (!checkout.status().equals("accepted")) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "请先核对全部学生并生成报名订单");
        }
        List<Line> lines = lines(checkout.id(), true);
        verifyCurrentLines(actor, lines, checkout);
        Promotion promotion = lockAndVerifyPromotion(checkout, lines, checkout.buyerCount());
        Map<Integer, BuyerProfile> profiles = resolvedProfilesByIndex(checkout.id());
        checkPromotionQuota(promotion, profiles, checkout.id(), false);
        List<CheckoutOrderBalance> orders = payments.lockCheckoutOrders(checkout.id());
        BigDecimal due = sum(orders.stream().map(CheckoutOrderBalance::due).toList());
        BigDecimal amount = money(input.amount());
        if (amount.compareTo(due) != 0) { throw new BusinessException(ErrorCode.INVALID_INPUT,
            "已核对到账金额须等于整单剩余应收"); }
        byte[] refHash = cipher.fingerprint("checkout_ref", input.reference().trim());
        payments.ensureUniqueReceipt(checkout.id(), refHash, amount, input.method());
        if (due.signum() == 0) {
            payments.confirmZeroDue(actor, checkout, refHash);
        } else {
            payments.confirmOrders(actor, checkout, orders, input, refHash);
        }
        return receiptRepository.receipt(checkout(actor, checkoutId, false), false);
    }

    /** Reads one checkout only through creator or complete campus scope. */
    public CheckoutReceipt detail(Actor actor, String checkoutId) {
        return receiptRepository.receipt(checkout(actor, checkoutId, false), false);
    }

    /** Staff-only identity review under the checkout and student write scopes. */
    public IntakeDetail intake(Actor actor,String id) {
        CheckoutState state=checkout(actor,id,false);
        List<IntakeBuyer> rows=buyers(state.id(),false).stream().map(b->new IntakeBuyer(b.index(),b.name(),b.phone(),
            b.grade(),b.selectedStudent()==null?null:publicStudentId(b.selectedStudent()),intakeMatches(actor,b))).toList();
        return new IntakeDetail(receiptRepository.receipt(state,false),rows);
    }
    private List<IntakeStudent> intakeMatches(Actor actor,Buyer buyer) {
        var indexes=cipher.exactBlindIndexes("student_name",StudentIdentityMutationLock.identityKey(buyer.name(),buyer.phone()));
        String terms=String.join(" OR ",indexes.keySet().stream().map(key->"(i.key_id=? AND i.value_hash=?)").toList());
        List<Object> args=new ArrayList<>();indexes.forEach((key,value)->{args.add(key);args.add(value);});
        var scope=ScopeSql.studentWrite(actor);args.addAll(scope.args());
        return jdbc.query("SELECT DISTINCT s.public_id,s.name_cipher,s.grade FROM core_student s WHERE s.deleted=0 "
            +"AND EXISTS(SELECT i.id FROM core_student_identity_index i WHERE i.student_id=s.id AND i.deleted=0 AND ("
            +terms+")) AND ("+scope.sql()+") ORDER BY s.public_id",
            (rs,row)->new IntakeStudent(rs.getString(1),cipher.decrypt(rs.getString(2)),rs.getString(3)),args.toArray());
    }

    /** Lists checkout requests under the same authorization predicate used by detail and mutation. */
    public CheckoutList list(Actor actor, int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) { invalid("分页参数无效"); }
        String scope = CommerceCheckoutScope.sql(actor);
        Object[] scopeArgs = CommerceCheckoutScope.args(actor);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM core_commerce_checkout p WHERE p.deleted=0 AND "
            + scope, Long.class, scopeArgs);
        List<String> ids = jdbc.query("SELECT p.public_id FROM core_commerce_checkout p WHERE p.deleted=0 AND "
            + scope + " ORDER BY p.created_at DESC,p.id DESC LIMIT ? OFFSET ?", (rs, row) -> rs.getString(1),
            concat(scopeArgs, size, (long) (page - 1) * size));
        return new CheckoutList(ids.stream().map(id -> receiptRepository.receipt(checkout(actor, id, false), false)).toList(),
            total == null ? 0 : total, page, size);
    }

    private void validateCreate(CreateInput input) {
        if (input == null || input.buyers() == null || input.items() == null
                || input.buyers().isEmpty() || input.buyers().size() > 50 || input.items().isEmpty()
                || input.items().size() > 100) { invalid("商城申请人数或购买行无效"); }
        if (input.type().equals("standard") != (input.promotionId() == null || input.promotionId().isBlank())) {
            invalid("普通购买不得附加活动，活动购买必须选择对应活动");
        }
        if (input.expectedQuoteHash() != null && !input.expectedQuoteHash().matches("[a-f0-9]{64}")) {
            invalid("商城报价校验值格式无效");
        }
        if (input.buyers().stream().anyMatch(java.util.Objects::isNull)
                || input.items().stream().anyMatch(java.util.Objects::isNull)) { invalid("商城申请行不能为空"); }
    }

    private List<Buyer> resolveQuoteBuyers(Actor actor, List<BuyerInput> inputs) {
        List<Buyer> result = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            BuyerInput input = inputs.get(index);
            if (input.studentId() != null && !input.studentId().isBlank()) {
                if (actor == null) {
                    throw new BusinessException(ErrorCode.FORBIDDEN, "公开申请不能指定已有学生编号");
                }
                if (input.name() != null || input.phone() != null || input.grade() != null) {
                    invalid("选择已有学生时不能同时提交学生身份草稿");
                }
                BuyerProfile profile = visibleStudent(actor, input.studentId(), false);
                result.add(new Buyer(index, profile.id(), null, profile.name(), profile.phone(), profile.grade()));
            } else {
                String name = clean(input.name(), 80, "学生姓名");
                String phone = normalizePhone(input.phone());
                result.add(new Buyer(index, null, null, name, phone, optional(input.grade(), 40)));
            }
        }
        return java.util.Collections.unmodifiableList(result);
    }

    private List<ProductClass> resolveClasses(Actor actor, List<LineInput> inputs) {
        Set<String> keys = new HashSet<>();
        Set<Integer> buyers = new HashSet<>();
        List<ProductClass> result = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            LineInput input = inputs.get(index);
            String key = input.buyerIndex() + ":" + input.classId();
            if (!keys.add(key)) { invalid("同一学生不能重复购买同一班级"); }
            buyers.add(input.buyerIndex());
            ProductClass item = productClass(actor, input.classId(), false);
            result.add(item);
        }
        // Keep request order: different buyers may legitimately select the same group class.
        return java.util.Collections.unmodifiableList(result);
    }

    private Promotion resolvePromotion(CreateInput input, List<ProductClass> items, int buyerCount,
            boolean lock) {
        if (input.type().equals("standard")) { return null; }
        List<PromotionBase> found = jdbc.query("SELECT p.id,p.public_id,p.type,p.version,p.start_date,p.end_date,"
            + "p.discount_amount,p.threshold_amount,p.group_size,p.valid_hours,p.stack_coupon,p.total_limit,"
            + "p.max_per_user,p.usage_count FROM core_commerce_promotion p WHERE p.public_id=? AND p.type=? "
            + "AND p.published=1" + (lock ? " FOR UPDATE" : ""), (rs, row) -> new PromotionBase(rs.getLong("id"),
                rs.getString("public_id"), rs.getString("type"), rs.getLong("version"),
                rs.getDate("start_date").toLocalDate(), rs.getDate("end_date").toLocalDate(),
                rs.getBigDecimal("discount_amount"), rs.getBigDecimal("threshold_amount"),
                (Integer) rs.getObject("group_size"), (Integer) rs.getObject("valid_hours"),
                rs.getBoolean("stack_coupon"), rs.getInt("total_limit"), rs.getInt("max_per_user"),
                rs.getInt("usage_count")),
            input.promotionId(), input.type());
        if (found.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "商城活动不存在或已下架"); }
        Promotion promotion = promotion(found.get(0));
        validatePromotion(promotion, input.type(), items, buyerCount);
        return promotion;
    }

    private void validatePromotion(Promotion promotion, String type, List<ProductClass> items, int buyerCount) {
        LocalDate today = LocalDate.now(SHANGHAI);
        if (today.isBefore(promotion.start()) || today.isAfter(promotion.end())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "商城活动不在有效期内");
        }
        if (items.stream().anyMatch(item -> !promotion.classIds().contains(item.classPublicId()))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "班级不在活动适用范围");
        }
        if (type.equals("group")) {
            if (promotion.groupSize() == null || promotion.groupSize() != buyerCount
                    || items.size() != buyerCount || items.stream().map(ProductClass::classId).distinct().count() != 1
                    || items.stream().anyMatch(item -> item.price().compareTo(promotion.threshold()) < 0)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "拼团须满足整团人数、同班和优惠门槛");
            }
        } else {
            Set<String> selectedCourses = items.stream().map(ProductClass::coursePublicId)
                .collect(java.util.stream.Collectors.toSet());
            if (buyerCount != 1 || !selectedCourses.equals(promotion.courseIds())
                    || selectedCourses.size() != items.size()
                    || items.stream().map(ProductClass::price).reduce(ZERO, BigDecimal::add)
                        .compareTo(promotion.threshold()) < 0) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "联报须由一名学生选择活动内每门不同课程");
            }
        }
    }

    private List<Line> priceLines(Actor actor,CreateInput input, List<Buyer> buyers,
            List<ProductClass> items, Promotion promotion) {
        if (items.stream().anyMatch(item -> item.price().signum() < 0)) { invalid("商品价格无效"); }
        List<Line> lines = new ArrayList<>();
        int index = 0;
        var coupons=new CommerceCheckoutCouponRepository(jdbc,cipher,mapper);
        for (ProductClass item : items) {
            LineInput request = input.items().get(index);
            if (request.buyerIndex() < 0 || request.buyerIndex() >= buyers.size()) { invalid("购买行未对应有效学生"); }
            if(request.couponId()!=null&&promotion!=null&&!promotion.stackCoupon()) invalid("此活动不允许叠加优惠券");
            var coupon=coupons.quote(actor,request.couponId(),buyers.get(request.buyerIndex()),item);
            BigDecimal afterCoupon=item.price().subtract(coupon.discount());
            BigDecimal discount = ZERO;
            if (promotion != null && promotion.type().equals("group")) {
                discount = promotion.discount().min(afterCoupon);
            }
            lines.add(new Line(index++, request.buyerIndex(), item, promotion == null ? null : promotion.id(),
                money(item.price()), coupon.discount(), money(discount), money(afterCoupon.subtract(discount)),
                fingerprints.signature(item.classId()),coupon.snapshot()));
        }
        if (promotion != null && promotion.type().equals("joint")) { applyJointDiscount(lines, promotion); }
        if (lines.stream().map(Line::buyerIndex).distinct().count() != buyers.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "每位学生至少选择一个课程班级");
        }
        return List.copyOf(lines);
    }

    private void applyJointDiscount(List<Line> lines, Promotion promotion) {
        long totalCents = lines.stream().map(Line::amount).map(CommerceCheckoutValues::cents).mapToLong(Long::longValue).sum();
        long cut = Math.min(totalCents, cents(promotion.discount()));
        long[] shares = allocate(cut, lines.stream().map(Line::amount).map(CommerceCheckoutValues::cents)
            .toList());
        for (int i = 0; i < lines.size(); i++) {
            Line old = lines.get(i);
            BigDecimal promotionCut = BigDecimal.valueOf(shares[i], 2);
            lines.set(i, new Line(old.index(), old.buyerIndex(), old.item(), old.promotionId(), old.base(),
                old.couponDiscount(), promotionCut, old.amount().subtract(promotionCut), old.signature(),old.coupon()));
        }
    }

    private CheckoutState insertCheckout(QuoteBuild quote, String visitorHash) {
        return persistence.insert(quote, visitorHash);
    }

    private ProductClass productClass(Actor actor, String classPublicId, boolean lock) {
        ScopeSql.Clause scope = actor == null ? new ScopeSql.Clause("1=1", List.of()) : ScopeSql.classes(actor);
        List<Object> args = new ArrayList<>(List.of(classPublicId));
        args.addAll(scope.args());
        String sql = "SELECT p.id product_id,p.public_id product_public_id,p.version product_version,"
            + "co.id course_id,co.public_id course_public_id,co.name course_name,co.grade,co.subject,co.price,"
            + "co.version course_version,c.id class_id,c.public_id class_public_id,c.campus_id,cp.public_id campus_public_id,"
            + "c.name class_name,c.capacity,c.version class_version FROM core_class c "
            + "JOIN core_commerce_product_class pc ON pc.class_id=c.id AND pc.active=1 AND pc.deleted=0 "
            + "JOIN core_commerce_product p ON p.id=pc.product_id AND p.published=1 AND p.deleted=0 "
            + "JOIN core_course co ON co.id=c.course_id AND co.active=1 AND co.deleted=0 AND co.teaching_published=1 AND EXISTS(SELECT v.id FROM core_course_publication v WHERE v.course_id=co.id AND v.course_version=co.version) "
            + "JOIN core_campus cp ON cp.id=c.campus_id AND cp.active=1 AND cp.deleted=0 "
            + "WHERE c.public_id=? AND c.active=1 AND c.deleted=0 AND (" + scope.sql() + ")"
            + (lock ? " FOR UPDATE" : "");
        List<ProductClass> rows = jdbc.query(sql, (rs, row) -> new ProductClass(rs.getLong("product_id"),
            rs.getString("product_public_id"), rs.getLong("product_version"), rs.getLong("course_id"),
            rs.getString("course_public_id"), rs.getString("course_name"), rs.getString("grade"),
            rs.getString("subject"), rs.getBigDecimal("price"), rs.getLong("course_version"),
            rs.getLong("class_id"), rs.getString("class_public_id"), rs.getLong("campus_id"),
            rs.getString("campus_public_id"), rs.getString("class_name"), rs.getInt("capacity"),
            rs.getLong("class_version")), args.toArray());
        if (rows.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "班级未上架销售、不存在或不在当前范围"); }
        if (actor == null) {
            Integer current = jdbc.queryForObject("SELECT COUNT(*) FROM core_commerce_product p "
                + "JOIN core_course c ON c.id=p.course_id WHERE p.id=? AND p.storefront_title IS NOT NULL "
                + "AND p.storefront_course_version=c.version AND p.storefront_price=c.price "
                + "AND p.storefront_course_type=c.course_type", Integer.class, rows.get(0).productId());
            if (current == null || current != 1) { throw stale("销售或教学版本待重新发布"); }
        }
        return rows.get(0);
    }

    private BuyerProfile visibleStudent(Actor actor, String studentPublicId, boolean lock) {
        ScopeSql.Clause scope = ScopeSql.studentWrite(actor);
        List<Object> args = new ArrayList<>(List.of(studentPublicId));
        args.addAll(scope.args());
        String sql = "SELECT s.id,s.public_id,s.name_cipher,s.phone_cipher,s.grade,s.campus_id,s.owner_id,s.assignee_id "
            + "FROM core_student s WHERE s.public_id=? AND s.deleted=0 AND (" + scope.sql() + ")"
            + (lock ? " FOR UPDATE" : "");
        List<BuyerProfile> rows = jdbc.query(sql, (rs, row) -> new BuyerProfile(rs.getLong("id"),
            rs.getString("public_id"), cipher.decrypt(rs.getString("name_cipher")),
            cipher.decrypt(rs.getString("phone_cipher")), rs.getString("grade"),
            nullableLong(rs, "campus_id"), nullableLong(rs, "owner_id"), nullableLong(rs, "assignee_id")),
            args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "学生不存在或不在当前写入范围"); }
        return rows.get(0);
    }

    private List<String> selectedStudents(AcceptInput input, int buyerCount) {
        Map<Integer, String> selected = new HashMap<>();
        if (input.students() == null) { return java.util.Collections.nCopies(buyerCount, null); }
        for (AcceptStudent item : input.students()) {
            if (item.buyerIndex() < 0 || item.buyerIndex() >= buyerCount
                    || selected.putIfAbsent(item.buyerIndex(), item.studentId()) != null) {
                invalid("学生核对序号重复或无效");
            }
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < buyerCount; i++) { result.add(selected.get(i)); }
        return java.util.Collections.unmodifiableList(result);
    }

    private Map<Integer, BuyerProfile> resolveAcceptedBuyers(Actor actor, long checkoutId,
            List<Buyer> buyers, List<String> overrides) {
        Map<Integer, BuyerProfile> result = new HashMap<>();
        for (Buyer buyer : buyers) {
            String chosen = overrides.get(buyer.index());
            if (chosen == null && buyer.selectedStudent() != null) {
                chosen = publicStudentId(buyer.selectedStudent());
            }
            BuyerProfile profile = chosen == null
                ? createDraftStudent(actor, checkoutId, buyer) : visibleStudent(actor, chosen, true);
            if (!sameIdentity(buyer, profile)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "选择的学生档案与商城申请姓名手机号不一致");
            }
            result.put(buyer.index(), profile);
        }
        return result;
    }

    private BuyerProfile createDraftStudent(Actor actor, long checkoutId, Buyer buyer) {
        if (actor.scope().equals("assigned")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前数据范围不能创建商城申请学生档案");
        }
        List<String> campuses = jdbc.query("SELECT DISTINCT c.public_id FROM core_commerce_checkout_line l "
            + "JOIN core_campus c ON c.id=l.campus_id WHERE l.checkout_id=? AND l.buyer_index=? "
            + "AND l.deleted=0 ORDER BY c.public_id", (rs, row) -> rs.getString(1), checkoutId, buyer.index());
        if (campuses.size() != 1) { invalid("新建学生的购买行须属于同一校区"); }
        StudentInput input = new StudentInput(buyer.name(), buyer.grade(), buyer.phone(), campuses.get(0), null);
        var created = students.createStudent(actor, input);
        return visibleStudent(actor, created.id(), true);
    }

    private boolean sameIdentity(Buyer buyer, BuyerProfile profile) {
        return buyer.name().equals(profile.name()) && normalizePhone(buyer.phone()).equals(normalizePhone(profile.phone()));
    }

    private void ensureUniqueStudents(Map<Integer, BuyerProfile> profiles) {
        if (profiles.values().stream().map(BuyerProfile::id).distinct().count() != profiles.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "整团或联报不能重复使用同一学生身份");
        }
    }

    private void verifyCurrentLines(Actor actor, List<Line> lines, CheckoutState checkout) {
        for (Line line : lines) {
            new CommerceCheckoutCouponRepository(jdbc,cipher,mapper).current(actor,line.coupon());
            ProductClass current = productClass(actor, line.item().classPublicId(), true);
            if (current.productVersion() != line.item().productVersion()
                    || current.courseVersion() != line.item().courseVersion()
                    || current.classVersion() != line.item().classVersion()
                    || !current.campusPublicId().equals(line.item().campusPublicId())
                    || current.price().compareTo(line.base()) != 0
                    || !fingerprints.signature(current.classId()).equals(line.signature())) {
                throw new BusinessException(ErrorCode.PREVIEW_STALE, "商品价格、班级或排期已变化，请重新报价");
            }
        }
        verifyPromotionSignature(checkout);
    }

    private void verifyPromotionSignature(CheckoutState checkout) {
        if (checkout.promotionId() == null) { return; }
        Promotion promotion = promotionById(checkout.promotionId(), false);
        LocalDate today = LocalDate.now(SHANGHAI);
        if (promotion == null || !promotionSignature(promotion).equals(checkout.promotionSignature())
                || today.isBefore(promotion.start()) || today.isAfter(promotion.end())) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE, "商城活动条款或有效期已变化");
        }
    }

    private Promotion lockAndVerifyPromotion(CheckoutState checkout, List<Line> lines, int buyers) {
        if (checkout.promotionId() == null) { return null; }
        Promotion promotion = promotionById(checkout.promotionId(), true);
        if (promotion == null || !promotionSignature(promotion).equals(checkout.promotionSignature())) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE, "商城活动条款已变化");
        }
        validatePromotion(promotion, checkout.type(), lines.stream().map(Line::item).toList(), buyers);
        int use = checkout.type().equals("group") ? buyers : 1;
        return promotion;
    }

    private Promotion promotionById(long id, boolean lock) {
        List<PromotionBase> rows = jdbc.query("SELECT p.id,p.public_id,p.type,p.version,p.start_date,p.end_date,"
            + "p.discount_amount,p.threshold_amount,p.group_size,p.valid_hours,p.stack_coupon,p.total_limit,"
            + "p.max_per_user,p.usage_count FROM core_commerce_promotion p WHERE p.id=? AND p.published=1"
            + (lock ? " FOR UPDATE" : ""), (rs, row) -> new PromotionBase(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("type"), rs.getLong("version"), rs.getDate("start_date").toLocalDate(),
                rs.getDate("end_date").toLocalDate(), rs.getBigDecimal("discount_amount"),
                rs.getBigDecimal("threshold_amount"), (Integer) rs.getObject("group_size"),
                (Integer) rs.getObject("valid_hours"), rs.getBoolean("stack_coupon"), rs.getInt("total_limit"),
                rs.getInt("max_per_user"), rs.getInt("usage_count")), id);
        return rows.isEmpty() ? null : promotion(rows.get(0));
    }

    private void checkPromotionQuota(Promotion promotion, Map<Integer, BuyerProfile> buyers,
            long checkoutId, boolean reserve) {
        if (promotion == null) { return; }
        int usage = promotion.type().equals("group") ? buyers.size() : 1;
        if (reserve && promotion.usageCount() + usage > promotion.totalLimit()) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "活动优惠总量不足");
        }
        for (BuyerProfile buyer : buyers.values()) {
            Integer used = jdbc.queryForObject("SELECT COUNT(DISTINCT b.checkout_id) "
                + "FROM core_commerce_checkout_buyer b JOIN core_commerce_checkout_line l "
                + "ON l.checkout_id=b.checkout_id AND l.buyer_index=b.buyer_index "
                + "JOIN core_commerce_checkout c ON c.id=b.checkout_id "
                + "WHERE b.resolved_student_id=? AND l.promotion_id=? AND c.id<>? "
                + "AND c.status='accepted' AND c.deleted=0", Integer.class, buyer.id(), promotion.id(), checkoutId);
            if (used != null && used + 1 > promotion.maxPerUser()) {
                throw new BusinessException(ErrorCode.ALREADY_EXISTS, "学生已达到活动参与次数上限");
            }
        }
    }

    private EnrollmentOrder createOrderAndEnrollment(Actor actor, CheckoutState checkout, Line line,
            BuyerProfile student) {
        ProductClass item = productClass(actor, line.item().classPublicId(), true);
        if (student.ownerId() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "学生档案没有可用于订单归属的责任人");
        }
        String orderId = "ORD_" + UUID.randomUUID();
        jdbc.update("INSERT INTO core_crm_order(public_id,opportunity_id,customer_id,student_id,campus_id,"
            + "current_campus_id,attributed_owner_id,product_snapshot,total_amount,received_amount,refunded_amount,"
            + "status,refund_status,source_kind,commerce_checkout_id,subject_snapshot) "
            + "VALUES(?,NULL,NULL,?,?,?,?,?, ?,0,0,'pending','none','commerce_checkout',?,?)", orderId,
            student.id(), item.campusId(), item.campusId(), student.ownerId(), item.courseName(), line.amount(),
            checkout.id(), item.subject());
        long orderRowId = internalId("SELECT id FROM core_crm_order WHERE public_id=?", orderId);
        saveOrderTerms(orderRowId, item, line.amount());
        String enrollmentId = "ENR_" + UUID.randomUUID();
        jdbc.update("INSERT INTO core_enrollment(public_id,student_id,class_id,order_id,status,activated_at) "
            + "VALUES(?,?,?,?,'pending',NULL)", enrollmentId, student.id(), item.classId(), orderRowId);
        long enrollmentRowId = internalId("SELECT id FROM core_enrollment WHERE public_id=?", enrollmentId);
        saveEnrollmentLessons(enrollmentRowId, orderRowId, item.courseId(), line.amount());
        new CommerceCheckoutCouponRepository(jdbc,cipher,mapper).redeem(actor,line.coupon(),orderId);
        return new EnrollmentOrder(orderId, enrollmentId);
    }

    private void saveOrderTerms(long orderId, ProductClass item, BigDecimal amount) {
        var fees = new AcademicDtos.ClassFees(amount, ZERO, "unused_lessons", null, "after_enrollment");
        var terms = new CrmDtos.ClassOrderTerms(item.classPublicId(), item.className(), item.coursePublicId(),
            item.classVersion(), fees);
        new CrmClassOrderRepository(jdbc).save(orderId, terms);
    }

    private void saveEnrollmentLessons(long enrollmentId, long orderId, long courseId, BigDecimal total) {
        List<Lesson> lessons = jdbc.query("SELECT id,sequence_no,title FROM core_lesson "
            + "WHERE course_id=? AND deleted=0 ORDER BY sequence_no,id",
            (rs, row) -> new Lesson(rs.getLong("id"), rs.getInt("sequence_no"), rs.getString("title")), courseId);
        if (lessons.isEmpty()) { invalid("课程没有可关联的教学讲次"); }
        long totalCents = cents(total), perLesson = totalCents / lessons.size(), residual = totalCents % lessons.size();
        for (int index = 0; index < lessons.size(); index++) {
            Lesson lesson = lessons.get(index);
            BigDecimal unit = BigDecimal.valueOf(perLesson + (index < residual ? 1 : 0), 2);
            jdbc.update("INSERT INTO core_enrollment_lesson(public_id,enrollment_id,order_id,source_lesson_id,"
                + "sequence_no,title_snapshot,unit_amount) VALUES(?,?,?,?,?,?,?)", "ENRL_" + UUID.randomUUID(),
                enrollmentId, orderId, lesson.id(), lesson.sequence(), lesson.title(), unit);
        }
    }

    private void persistResolvedStudents(long checkoutId, Map<Integer, BuyerProfile> profiles) {
        profiles.forEach((index, profile) -> jdbc.update("UPDATE core_commerce_checkout_buyer "
            + "SET resolved_student_id=?,updated_at=UTC_TIMESTAMP(3) WHERE checkout_id=? AND buyer_index=?",
            profile.id(), checkoutId, index));
    }

    private void acceptCheckout(Actor actor, CheckoutState checkout, Promotion promotion) {
        int changed = jdbc.update("UPDATE core_commerce_checkout SET status='accepted',accepted_by_id=?,"
            + "accepted_at=UTC_TIMESTAMP(3),version=version+1,updated_at=UTC_TIMESTAMP(3) "
            + "WHERE id=? AND version=? AND status='awaiting_verification' AND deleted=0",
            actor.id(), checkout.id(), checkout.version());
        if (changed != 1) { throw stale("商城申请状态已变化"); }
        if (promotion != null) {
            int updated = jdbc.update("UPDATE core_commerce_promotion SET usage_count=usage_count+? "
                + "WHERE id=? AND usage_count<?", checkout.type().equals("group") ? checkout.buyerCount() : 1,
                promotion.id(), promotion.totalLimit());
            if (updated != 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "活动优惠总量不足"); }
        }
        payments.insertEvent(new CheckoutEventWrite(checkout.id(), actor.id(), "accepted", ZERO, null, null, null));
    }

    private List<String> promotionCourses(long promotionId) {
        return jdbc.query("SELECT DISTINCT co.public_id FROM core_commerce_promotion_class pc "
            + "JOIN core_class c ON c.id=pc.class_id JOIN core_course co ON co.id=c.course_id "
            + "WHERE pc.promotion_id=? AND pc.active=1 AND c.deleted=0 AND c.active=1 ORDER BY co.public_id",
            (rs, row) -> rs.getString(1), promotionId);
    }

    private Set<String> promotionClasses(long promotionId) {
        return Set.copyOf(jdbc.query("SELECT c.public_id FROM core_commerce_promotion_class pc "
            + "JOIN core_class c ON c.id=pc.class_id WHERE pc.promotion_id=? AND pc.active=1 "
            + "AND c.deleted=0 AND c.active=1 ORDER BY c.public_id", (rs, row) -> rs.getString(1), promotionId));
    }

    private Promotion promotion(PromotionBase base) {
        return new Promotion(base.id(), base.publicId(), base.type(), base.version(), base.start(), base.end(),
            base.discount(), base.threshold(), base.groupSize(), base.validHours(), base.stackCoupon(),
            base.totalLimit(), base.maxPerUser(), base.usageCount(), Set.copyOf(promotionCourses(base.id())),
            promotionClasses(base.id()));
    }

    private String promotionSignature(Promotion promotion) {
        PromotionTerms terms = new PromotionTerms(promotion.publicId(), promotion.type(), promotion.version(),
            promotion.start(), promotion.end(), promotion.discount(), promotion.threshold(), promotion.groupSize(),
            promotion.validHours(), promotion.stackCoupon(), promotion.totalLimit(), promotion.maxPerUser(),
            promotion.courseIds().stream().sorted().toList(), promotion.classIds().stream().sorted().toList());
        try { return mutations.hash(mapper.writeValueAsString(terms)); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Unable to hash promotion terms", error); }
    }

    private QuoteSnapshot quoteSnapshot(QuoteHashInput input) {
        return new QuoteSnapshot(input.input().type(), input.input().promotionId(),
            input.buyers().stream().map(b -> b.index() + ":" + b.name() + ":" + b.phone() + ":" + b.grade()).toList(),
            input.lines().stream().map(l -> l.buyerIndex() + ":" + l.item().classPublicId() + ":" + l.base()
                + ":" + l.couponDiscount() + ":" + l.promotionDiscount() + ":" + l.amount()
                + ":" + l.signature() + ":" + (l.coupon()==null?"":l.coupon().publicId()+":"+l.coupon().version())).toList(), input.base(), input.discount(), input.total());
    }

    private String quoteHash(QuoteSnapshot snapshot) {
        try { return mutations.hash(mapper.writeValueAsString(snapshot)); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Unable to hash checkout quote", error); }
    }

    private Instant expiry(String type, Promotion promotion, Instant now) {
        int hours = type.equals("group") && promotion != null ? promotion.validHours() : 24;
        Instant requested = now.plusSeconds((long) hours * 3600L);
        if (promotion == null) { return requested; }
        Instant end = promotion.end().plusDays(1).atStartOfDay(SHANGHAI).toInstant().minusMillis(1);
        return requested.isBefore(end) ? requested : end;
    }

    private List<Buyer> buyers(long checkoutId, boolean lock) {
        return jdbc.query("SELECT b.buyer_index,b.selected_student_id,b.resolved_student_id,b.name_cipher,"
            + "b.phone_cipher,b.grade FROM core_commerce_checkout_buyer b WHERE b.checkout_id=? "
            + "AND b.deleted=0 ORDER BY b.buyer_index" + (lock ? " FOR UPDATE" : ""), (rs, row) ->
                new Buyer(rs.getInt("buyer_index"), nullableLong(rs, "selected_student_id"),
                    nullableLong(rs, "resolved_student_id"), cipher.decrypt(rs.getString("name_cipher")),
                    cipher.decrypt(rs.getString("phone_cipher")), rs.getString("grade")), checkoutId);
    }

    private List<Line> lines(long checkoutId, boolean lock) {
        List<Line> result = jdbc.query("SELECT l.line_index,l.buyer_index,l.product_id,l.course_id,l.class_id,"
            + "l.campus_id,l.promotion_id,l.base_amount,l.coupon_discount,l.promotion_discount,l.amount,"
            + "l.product_version,l.course_version,l.class_version,l.class_signature,p.public_id product_public_id,"
            + "p.name product_name,co.public_id course_public_id,co.name course_name,co.grade,co.subject,"
            + "c.public_id class_public_id,c.name class_name,c.capacity,cp.public_id campus_public_id,l.coupon_offer_id,l.coupon_version,qo.public_id coupon_public_id "
            + "FROM core_commerce_checkout_line l JOIN core_commerce_product p ON p.id=l.product_id "
            + "JOIN core_course co ON co.id=l.course_id JOIN core_class c ON c.id=l.class_id "
            + "JOIN core_campus cp ON cp.id=l.campus_id LEFT JOIN core_coupon_offer qo ON qo.id=l.coupon_offer_id WHERE l.checkout_id=? AND l.deleted=0 "
            + "ORDER BY l.line_index" + (lock ? " FOR UPDATE" : ""), (rs, row) -> {
                ProductClass item = new ProductClass(rs.getLong("product_id"), rs.getString("product_public_id"),
                    rs.getLong("product_version"), rs.getLong("course_id"), rs.getString("course_public_id"),
                    rs.getString("course_name"), rs.getString("grade"), rs.getString("subject"),
                    rs.getBigDecimal("base_amount"), rs.getLong("course_version"), rs.getLong("class_id"),
                    rs.getString("class_public_id"), rs.getLong("campus_id"), rs.getString("campus_public_id"),
                    rs.getString("class_name"), rs.getInt("capacity"), rs.getLong("class_version"));
                return new Line(rs.getInt("line_index"), rs.getInt("buyer_index"), item,
                    nullableLong(rs, "promotion_id"), rs.getBigDecimal("base_amount"),
                    rs.getBigDecimal("coupon_discount"), rs.getBigDecimal("promotion_discount"),
                    rs.getBigDecimal("amount"), rs.getString("class_signature"),rs.getObject("coupon_offer_id")==null?null:
                    new CommerceCheckoutCouponRepository.Snapshot(rs.getLong("coupon_offer_id"),rs.getString("coupon_public_id"),rs.getLong("coupon_version")));
            }, checkoutId);
        return List.copyOf(result);
    }

    private CheckoutState checkout(Actor actor, String publicId, boolean lock) {
        String scope = CommerceCheckoutScope.sql(actor);
        List<Object> args = new ArrayList<>(List.of(publicId));
        args.addAll(java.util.Arrays.asList(CommerceCheckoutScope.args(actor)));
        List<CheckoutState> rows = jdbc.query("SELECT p.id,p.public_id,p.checkout_type,p.status,p.promotion_id,"
            + "p.buyer_count,p.quote_hash,p.quote_cipher,p.expires_at,p.promotion_signature,p.base_amount,"
            + "p.discount_amount,p.total_amount,p.version,p.created_by_id FROM core_commerce_checkout p "
            + "WHERE p.public_id=? AND p.deleted=0 AND (" + scope + ")" + (lock ? " FOR UPDATE" : ""),
            persistence::mapCheckoutState, args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "商城申请不存在或不在当前范围"); }
        return rows.get(0);
    }

    private void verifyQuote(CheckoutState checkout, List<Buyer> buyers, List<Line> lines) {
        if (buyers.size() != checkout.buyerCount() || lines.isEmpty()
                || sum(lines.stream().map(Line::base).toList()).compareTo(checkout.baseAmount()) != 0
                || sum(lines.stream().map(line -> line.couponDiscount().add(line.promotionDiscount())).toList())
                    .compareTo(checkout.discountAmount()) != 0
                || sum(lines.stream().map(Line::amount).toList()).compareTo(checkout.totalAmount()) != 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "商城报价快照金额不一致");
        }
    }

    private Map<Integer, BuyerProfile> resolvedProfilesByIndex(long checkoutId) {
        Map<Integer, BuyerProfile> result = new HashMap<>();
        jdbc.query("SELECT b.buyer_index,s.id,s.public_id,s.name_cipher,s.phone_cipher,s.grade,s.campus_id,"
            + "s.owner_id,s.assignee_id FROM core_commerce_checkout_buyer b "
            + "JOIN core_student s ON s.id=b.resolved_student_id WHERE b.checkout_id=? "
            + "AND b.resolved_student_id IS NOT NULL ORDER BY b.buyer_index", rs -> {
                BuyerProfile profile = new BuyerProfile(rs.getLong("id"), rs.getString("public_id"),
                    cipher.decrypt(rs.getString("name_cipher")), cipher.decrypt(rs.getString("phone_cipher")),
                    rs.getString("grade"), nullableLong(rs, "campus_id"), nullableLong(rs, "owner_id"),
                    nullableLong(rs, "assignee_id"));
                result.put(rs.getInt("buyer_index"), profile);
            }, checkoutId);
        return result;
    }

    private long internalId(String sql, String publicId) {
        Long id = jdbc.queryForObject(sql, Long.class, publicId);
        if (id == null) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "商城关联写入失败"); }
        return id;
    }

    private String publicStudentId(long id) {
        return jdbc.queryForObject("SELECT public_id FROM core_student WHERE id=? AND deleted=0", String.class, id);
    }
    private Long nullableLong(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        Number value = (Number) rs.getObject(name);
        return value == null ? null : value.longValue();
    }
    private Object[] concat(Object[] initial, Object... extra) {
        Object[] result = java.util.Arrays.copyOf(initial, initial.length + extra.length);
        System.arraycopy(extra, 0, result, initial.length, extra.length);
        return result;
    }
    private static BusinessException stale(String message) {
        return new BusinessException(ErrorCode.VERSION_CONFLICT, message);
    }
}
