package cn.siyue.core;

import cn.siyue.core.CommerceDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic, authorized catalog publication with encrypted retry receipts and audit events. */
@Service
public class CommerceService {
    private final AccessRepository access;
    private final CommerceRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    private final Validator validator;
    /**
     * Creates the commerce application service and its validation/idempotency collaborators.
     * @param access locks and reloads the active principal before writes
     * @param repository applies commerce scope and persists catalog data
     * @param mutations stores request hashes and replay receipts
     * @param mapper serializes requests and receipts for idempotency
     * @param validator validates DTO constraints before persistence
     */
    public CommerceService(AccessRepository access,CommerceRepository repository,MutationRepository mutations,
            ObjectMapper mapper,Validator validator) {
        this.access=access;this.repository=repository;this.mutations=mutations;this.mapper=mapper;this.validator=validator;
    }
    /** Lists products in the caller's current scope; store.read alone sees published products only.
     * @param actor authenticated caller with commerce.read, commerce.manage, product.publish, or store.read
     * @param page one-based page number from 1 through 100000
     * @param pageSize number of rows from 1 through 100
     * @return page of scoped products and pagination metadata
     * @throws BusinessException when permission or pagination validation fails
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public ProductPage products(Actor actor,int page,int pageSize) {
        actor.requireAny("commerce.read", "commerce.manage", "product.publish", "store.read");
        pagination(page,pageSize);
        boolean publicOnly = actor.permissions().contains("store.read")
                && !actor.permissions().contains("commerce.read")
                && !actor.permissions().contains("commerce.manage")
                && !actor.permissions().contains("product.publish");
        return repository.products(actor,page,pageSize,publicOnly);
    }
    /** Lists all promotion types visible within the actor's commerce scope.
     * @param actor authenticated caller with commerce.read, commerce.manage, or product.publish
     * @param page one-based page number from 1 through 100000
     * @param pageSize number of rows from 1 through 100
     * @return page of scoped promotions
     * @throws BusinessException when permission or pagination validation fails
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public PromotionPage promotions(Actor actor,int page,int pageSize) {
        return promotions(actor,page,pageSize,null);
    }
    /** Lists scoped promotions, optionally restricted to a supported promotion type.
     * @param actor authenticated caller, required to have commerce.read
     * @param page one-based page number from 1 through 100000
     * @param pageSize number of rows from 1 through 100
     * @param type null for all types, otherwise {@code group} or {@code joint}
     * @return page of scoped promotions matching the type filter
     * @throws BusinessException when permission, pagination, or type validation fails
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public PromotionPage promotions(Actor actor,int page,int pageSize,String type) {
        actor.require("commerce.read");pagination(page,pageSize);
        if(type!=null && !List.of("group","joint").contains(type)) { invalid("活动类型无效"); }
        return repository.promotions(actor,page,pageSize,type);
    }
    /** Lists commerce-visible classes for one course.
     * @param actor authenticated caller, required to have commerce.read
     * @param courseId course public identifier matching letters, digits, underscore, or hyphen (1–120 chars)
     * @param page one-based page number from 1 through 100000
     * @param pageSize number of rows from 1 through 100
     * @return page of classes attached to the course and visible to the caller
     * @throws BusinessException when permission, pagination, or course identifier validation fails
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public ClassPage courseClasses(Actor actor,String courseId,int page,int pageSize) {
        actor.requireAny("commerce.read", "commerce.manage", "product.publish");pagination(page,pageSize);
        if(courseId==null||!courseId.matches("[A-Za-z0-9_-]{1,120}")) { invalid("课程编号无效"); }
        return repository.courseClasses(actor,courseId,page,pageSize);
    }
    /** Creates or updates a product and persists an idempotent mutation receipt.
     * @param actor authenticated caller; active status and commerce.manage are rechecked under lock
     * @param id null to create, otherwise the product public identifier to update
     * @param key Idempotency-Key containing 16–128 allowed ASCII characters
     * @param input validated product data; duplicate class selections are rejected
     * @return saved product, or the stored result for an identical retry
     * @throws BusinessException for invalid fields, missing permission, out-of-scope product, or key/payload conflict
     * @throws JsonProcessingException if request or receipt serialization fails
     */
    @Transactional(rollbackFor=Exception.class)
    public Product saveProduct(Actor actor,String id,String key,ProductInput input) throws JsonProcessingException {
        validate(input);selection(new Selection(input.courseId(),input.classIds()));requireKey(key);
        actor=access.lockActive(actor.id());actor.require("commerce.manage");
        if(id!=null) { repository.authorize(actor,id,"product"); }
        String action=id==null?"commerce.product.create":"commerce.product.update";
        String hash=mutations.hash(mapper.writeValueAsString(List.of(id==null?"":id,input)));
        String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null) {
            Product stored=mapper.readValue(replay,Product.class);
            repository.authorize(actor,stored.id(),"product");return stored;
        }
        Product result=repository.saveProduct(actor,id,input);
        mutations.complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(result),result.id()));
        return result;
    }
    /** Creates or updates a group/joint promotion after enforcing its type-specific business rules.
     * @param actor authenticated caller; active status and commerce.manage are rechecked under lock
     * @param id null to create, otherwise the promotion public identifier to update
     * @param key Idempotency-Key containing 16–128 allowed ASCII characters
     * @param input promotion data; dates, participants, selections, and group/joint fields are validated
     * @return saved promotion, or the stored result for an identical retry
     * @throws BusinessException for invalid promotion rules, missing permission, out-of-scope promotion, or key/payload conflict
     * @throws JsonProcessingException if request or receipt serialization fails
     */
    @Transactional(rollbackFor=Exception.class)
    public Promotion savePromotion(Actor actor,String id,String key,PromotionInput input) throws JsonProcessingException {
        validate(input);promotionRules(input);requireKey(key);
        actor=access.lockActive(actor.id());actor.require("commerce.manage");
        if(id!=null) { repository.authorize(actor,id,"promotion"); }
        String action=id==null?"commerce.promotion.create":"commerce.promotion.update";
        String hash=mutations.hash(mapper.writeValueAsString(List.of(id==null?"":id,input)));
        String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null) {
            Promotion stored=mapper.readValue(replay,Promotion.class);
            repository.authorize(actor,stored.id(),"promotion");return stored;
        }
        Promotion result=repository.savePromotion(actor,id,input);
        mutations.complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(result),result.id()));
        return result;
    }
    private void validate(Object input) {
        if(input==null || !validator.validate(input).isEmpty()) { invalid("商品或活动参数无效"); }
    }
    private void selection(Selection selection) {
        if(new HashSet<>(selection.classIds()).size()!=selection.classIds().size()) { invalid("同一课程不能重复选择班级"); }
    }
    private void promotionRules(PromotionInput input) {
        if(input.endDate().isBefore(input.startDate())) { invalid("活动结束日期不能早于开始日期"); }
        if(input.published() && input.endDate().isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")))) { invalid("已过期的活动不能发布"); }
        if(new HashSet<>(input.participants()).size()!=input.participants().size()) { invalid("参与用户类型不能重复"); }
        if(input.selections().stream().map(Selection::courseId).distinct().count()!=input.selections().size()) { invalid("不能重复选择同一课程"); }
        input.selections().forEach(this::selection);
        if(input.type().equals("group")) { groupRules(input); }
        else if(input.selections().size()<2 || input.groupSize()!=null || input.validHours()!=null) { invalid("联报至少选择两门不同课程且不设置拼团人数或有效小时"); }
    }
    private void groupRules(PromotionInput input) {
        if(input.groupSize()==null || input.validHours()==null) { invalid("拼团需要成团人数和有效期"); }
    }
    private void pagination(int page,int pageSize) {
        if(page<1 || page>100000 || pageSize<1 || pageSize>100) { invalid("分页参数无效"); }
    }
    private void requireKey(String key) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{16,128}")) { invalid("Idempotency-Key 格式无效"); }
    }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_INPUT,message); }
}
