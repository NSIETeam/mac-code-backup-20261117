package cn.siyue.core;

import cn.siyue.core.PublicStorefrontDtos.Configuration;
import cn.siyue.core.PublicStorefrontDtos.Content;
import cn.siyue.core.PublicStorefrontDtos.ContentInput;
import cn.siyue.core.PublicStorefrontDtos.DecorInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and updates commerce storefront copy and singleton decoration independently of academic course facts.
 * Employee operations use commerce/design grants; the public configuration read exposes only published decoration.
 */
@Service
public class PublicStorefrontService {
    private final AccessRepository access;
    private final CommerceRepository commerce;
    private final MutationRepository mutations;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** Creates the storefront service with actor locking, product scope, mutation, persistence, and JSON dependencies.
     * @param access active-principal lookup and lock for write-time authorization rechecks
     * @param commerce product authorization and scope checks
     * @param mutations idempotency receipt and audit persistence
     * @param jdbc storefront content and decoration persistence
     * @param mapper serializer for decoration and retry receipts
     */
    public PublicStorefrontService(AccessRepository access, CommerceRepository commerce,
            MutationRepository mutations, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.access = access;
        this.commerce = commerce;
        this.mutations = mutations;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Reads editable public copy for a product visible to the actor.
     * @param actor employee caller requiring {@code commerce.read}
     * @param productId public ID of the authorized product
     * @return storefront title, grade, subject, type, price, image, and description
     * @throws BusinessException when permission or product scope is denied, or no storefront copy exists
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Content get(Actor actor, String productId) {
        actor.require("commerce.read");
        commerce.authorize(actor, productId, "product");
        return jdbc.query("SELECT storefront_title,storefront_grade,storefront_subject,storefront_course_type,"
            + "storefront_price,storefront_image,storefront_description FROM core_commerce_product "
            + "WHERE public_id=? AND deleted=0", (rs, row) -> new Content(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getString(6), rs.getString(7)), productId)
                .stream().filter(value -> value.title() != null).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "商品不存在"));
    }

    /** Updates product storefront copy with optimistic versioning and an idempotent audit receipt.
     * The displayed price must remain equal to the current academic course price.
     * @param actor employee caller requiring {@code commerce.manage}; active status and product scope are rechecked
     * @param productId public ID of the authorized product
     * @param key idempotency key of 16–128 letters, digits, dots, underscores, colons, or hyphens
     * @param input bounded title/grade/subject/type/image/description, course-equal price, and expected product version
     * @return saved storefront copy, or the prior result for an identical retry
     * @throws BusinessException when permission, scope, key, input, price, or product version is invalid
     * @throws JsonProcessingException when request hashing or replay-result serialization fails
     */
    @Transactional(rollbackFor = Exception.class)
    public Content update(Actor actor, String productId, String key, ContentInput input)
            throws JsonProcessingException {
        actor = access.lockActive(actor.id());
        actor.require("commerce.manage");
        commerce.authorize(actor, productId, "product");
        requireKey(key);
        String action = "commerce.storefront.content.update";
        String hash = mutations.hash(mapper.writeValueAsString(java.util.List.of(productId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, Content.class); }
        ProductTarget target = jdbc.query("SELECT p.id,p.version,c.version course_version,c.price FROM core_commerce_product p "
            + "JOIN core_course c ON c.id=p.course_id WHERE p.public_id=? AND p.deleted=0 AND c.deleted=0 FOR UPDATE",
            (rs, row) -> new ProductTarget(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4)),
            productId).stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "商品不存在"));
        if (target.productVersion() != input.expectedVersion()) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE, "商品已更新，请刷新后重试");
        }
        if (input.price().movePointRight(2).compareTo(target.coursePrice().movePointRight(2)) != 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "店铺价格必须与当前课程价格一致");
        }
        Content result = new Content(input.title().trim(), input.grade().trim(), input.subject().trim(),
            input.courseType(), input.price(), blankToNull(input.image()), blankToNull(input.description()));
        jdbc.update("UPDATE core_commerce_product SET storefront_title=?,storefront_grade=?,storefront_subject=?,"
            + "storefront_course_type=?,storefront_price=?,storefront_image=?,storefront_description=?,"
            + "storefront_course_version=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND version=?",
            result.title(), result.grade(), result.subject(), result.courseType(), result.price(), result.image(),
            result.description(), target.courseVersion(), target.id(), target.productVersion());
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(result), productId));
        return result;
    }

    /** Reads the published decoration for anonymous storefront rendering; no employee identity is required.
     * @return current published decoration, or an empty decoration when no configuration row exists
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Configuration configuration() {
        return jdbc.query("SELECT decor FROM core_public_storefront_config WHERE singleton_key=1",
            (rs, row) -> new Configuration(map(rs.getString(1)))).stream().findFirst()
                .orElseGet(() -> new Configuration(Map.of()));
    }

    /** Reads published and draft decoration versions for an all-scope design administrator.
     * @param actor employee caller requiring {@code store.design} with all-data scope
     * @return published decoration, draft decoration, current version, and published version
     * @throws BusinessException when the design grant or global scope is missing
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public Configuration draft(Actor actor) {
        decorAccess(actor);return decorState();
    }
    private Configuration decorState() {
        return jdbc.query("SELECT decor,draft_decor,version,published_version FROM core_public_storefront_config WHERE singleton_key=1",
            (rs,row)->new Configuration(map(rs.getString(1)),rs.getString(2)==null?null:map(rs.getString(2)),rs.getLong(3),rs.getObject(4,Long.class)))
            .stream().findFirst().orElse(new Configuration(Map.of(),null,0,null));
    }
    /** Saves a validated storefront decoration draft without publishing it.
     * @param actor employee caller requiring {@code store.design} with all-data scope; active status is rechecked
     * @param key idempotency key of 16–128 letters, digits, dots, underscores, colons, or hyphens
     * @param input optional draft decoration and nonnegative expected singleton version
     * @return configuration with the saved draft and unchanged published version, or the prior retry result
     * @throws BusinessException when permission, scope, key, version, or decoration validation fails
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Configuration updateDecor(Actor actor,String key,DecorInput input) throws JsonProcessingException {
        return writeDecor(actor,key,input,false);
    }
    /** Publishes a validated storefront decoration using the current singleton version and an audit receipt.
     * @param actor employee caller requiring {@code store.design} with all-data scope; active status is rechecked
     * @param key idempotency key of 16–128 letters, digits, dots, underscores, colons, or hyphens
     * @param input draft decoration and nonnegative expected singleton version
     * @return configuration with the updated published and draft versions, or the prior retry result
     * @throws BusinessException when permission, scope, key, version, or decoration validation fails
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Configuration publishDecor(Actor actor,String key,DecorInput input) throws JsonProcessingException {
        return writeDecor(actor,key,input,true);
    }
    private Configuration writeDecor(Actor actor,String key,DecorInput input,boolean publish) throws JsonProcessingException {
        actor=access.lockActive(actor.id());decorAccess(actor);requireKey(key);
        if(input==null||input.expectedVersion()==null||input.expectedVersion()<0) throw new BusinessException(ErrorCode.INVALID_INPUT,"装修版本无效");
        String action="commerce.storefront.decor."+(publish?"publish":"update");
        String hash=mutations.hash(mapper.writeValueAsString(input));String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null) return mapper.readValue(replay,Configuration.class);
        jdbc.queryForObject("SELECT id FROM core_campus_configuration_lock WHERE lock_name='campus-directory' FOR UPDATE",Long.class);
        Configuration current=decorState();if(current.version()!=input.expectedVersion()) throw new BusinessException(ErrorCode.PREVIEW_STALE,"装修版本已变化，请刷新");
        var validator=new StorefrontDecorValidator(jdbc);Map<String,Object> draft=validator.clean(input.decor()==null?current.draft():input.decor());
        Map<String,Object> published=publish?validator.published(draft):current.decor();long version=current.version()+1;
        jdbc.update("INSERT INTO core_public_storefront_config(id,decor,draft_decor,version,published_version,published_at,updated_by) VALUES(1,?,?,?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE decor=VALUES(decor),draft_decor=VALUES(draft_decor),version=VALUES(version),published_version=VALUES(published_version),"
            + "published_at=IF(VALUES(published_at) IS NULL,published_at,VALUES(published_at)),updated_by=VALUES(updated_by),updated_at=UTC_TIMESTAMP(3)",
            mapper.writeValueAsString(published),mapper.writeValueAsString(draft),version,publish?Long.valueOf(version):current.publishedVersion(),
            publish?java.sql.Timestamp.from(java.time.Instant.now()):null,actor.id());
        Configuration result=decorState();mutations.complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(result),"public-storefront"));return result;
    }
    private void decorAccess(Actor actor) {
        actor.require("store.design");if(!actor.scope().equals("all")) throw new BusinessException(ErrorCode.FORBIDDEN,"商城装修需要全部数据范围");
    }

    private Map<String,Object> map(String json) {
        try { return mapper.readValue(json, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)); }
        catch (JsonProcessingException error) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "店铺装饰配置损坏"); }
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Idempotency-Key 格式无效");
        }
    }
    private record ProductTarget(long id, long productVersion, long courseVersion, java.math.BigDecimal coursePrice) { }
}
