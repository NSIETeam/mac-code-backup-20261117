package cn.siyue.core;

import static cn.siyue.core.CouponOfferDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exposes coupon definitions and selection options and applies versioned coupon lifecycle changes.
 * Writes reload the active caller and use READ_COMMITTED transactions for the offer, course links,
 * retry receipt and audit. Reads are read-only and use repository-specific visibility rules;
 * all transaction declarations roll back for checked exceptions.
 */
@Service
public class CouponOfferService {
    private final AccessRepository access;
    private final CouponOfferRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    private final Validator validator;
    /**
     * Creates the coupon management transaction boundary.
     * @param access reloads and locks active callers before writes
     * @param repository resolves coupon scopes, courses and issuance constraints
     * @param mutations records command hashes, replay receipts and audit events
     * @param mapper serializes commands and offer receipts
     * @param validator checks coupon and publication DTO constraints
     */
    public CouponOfferService(AccessRepository access,CouponOfferRepository repository,MutationRepository mutations,ObjectMapper mapper,Validator validator) {
        this.access=access;this.repository=repository;this.mutations=mutations;this.mapper=mapper;this.validator=validator;
    }
    /**
     * Reads active course and campus choices for coupon or storefront configuration.
     * Assigned scope limits courses to the caller's teaching assignments; campus scope limits campus choices
     * by membership. Course choices are paginated; campus choices are limited to 1000 rows.
     * @param actor caller with any of {@code promotion.create}, {@code promotion.publish},
     *     {@code commerce.read} or {@code store.design}
     * @param page one-based course page number from 1 through 100000
     * @param size course page size from 1 through 100
     * @return course choices, campus choices and course pagination metadata
     * @throws BusinessException when none of the required grants is present or pagination is invalid
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public Options options(Actor actor,int page,int size) {
        actor.requireAny("promotion.create","promotion.publish","commerce.read","store.design");
        if(page<1||page>100000||size<1||size>100) throw invalid("分页无效");return repository.options(actor,page,size);
    }
    /**
     * Lists coupon definitions and usage counts under coupon read scope.
     * All and own scopes see all offers; campus scope sees global offers and member campuses;
     * assigned scope sees offers owned by the caller.
     * @param actor caller with any of {@code promotion.create}, {@code promotion.publish} or {@code commerce.read}
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return offers ordered by newest identifier, with total and pagination metadata
     * @throws BusinessException when permission, data scope or pagination is invalid
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public Page list(Actor actor,int page,int size) {
        actor.requireAny("promotion.create","promotion.publish","commerce.read");
        if(page<1||page>100000||size<1||size>100) throw invalid("分页无效");return repository.list(actor,page,size);
    }
    /**
     * Creates a coupon draft or updates an existing offer and returns it to unpublished state.
     * The active caller and write scope are checked before replay. New offers require no expected version;
     * updates require the current version and cannot reduce issuance below existing claims plus direct uses.
     * @param actor caller requiring {@code promotion.create}; global offers require all scope, campus offers
     *     require all scope, campus membership, or ownership for own/assigned scope
     * @param id existing offer public identifier, or null to create an offer
     * @param key idempotency key matching {@code [A-Za-z0-9._:-]{16,128}}
     * @param input coupon fields, valid date range, issuance limits and distinct active course identifiers;
     *     a positive threshold must exceed the discount, and an empty course list applies to all courses
     * @return saved offer and usage counts, or the original receipt for an identical retry
     * @throws BusinessException when caller, permission, key, offer scope, DTO, version, issuance totals,
     *     campus or course eligibility checks fail, or the key conflicts with an earlier request
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Offer save(Actor actor,String id,String key,Input input) throws JsonProcessingException {
        actor=access.lockActive(actor.id());actor.require("promotion.create");validate(input);requireKey(key);
        if(id!=null) repository.authorized(actor,id,false);
        String action=id==null?"commerce.coupon.create":"commerce.coupon.update";
        String hash=mutations.hash(mapper.writeValueAsString(java.util.List.of(id==null?"":id,input)));
        String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null) {Offer stored=mapper.readValue(replay,Offer.class);repository.authorized(actor,stored.id(),false);return stored;}
        Offer result=repository.save(actor,id,input);complete(actor,key,action,hash,result);return result;
    }
    /**
     * Publishes or withdraws an authorized coupon at its expected version.
     * Publishing rejects already published or expired offers and revalidates active course eligibility;
     * withdrawal increments the version, while publication retains it. Both actions persist their own replay
     * receipt and audit in the same transaction; retries recheck active permission and offer write scope.
     * @param actor caller requiring {@code promotion.publish} and write access to the offer
     * @param id offer public identifier
     * @param key idempotency key matching {@code [A-Za-z0-9._:-]{16,128}}
     * @param input expected offer version, at least 1
     * @param publish true to publish, false to withdraw
     * @return resulting offer and usage counts, or the stored receipt for an identical retry
     * @throws BusinessException when caller, permission, scope, key, version or publication-state checks fail,
     *     or publication encounters an expired offer or an unavailable or unauthorized course
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Offer publication(Actor actor,String id,String key,Publication input,boolean publish) throws JsonProcessingException {
        actor=access.lockActive(actor.id());actor.require("promotion.publish");requireKey(key);repository.authorized(actor,id,false);
        if(input==null||!validator.validate(input).isEmpty()) throw invalid("发布版本无效");
        String action="commerce.coupon."+(publish?"publish":"unpublish");
        String hash=mutations.hash(mapper.writeValueAsString(java.util.List.of(id,input)));
        String replay=mutations.replay(actor,key,action,hash);if(replay!=null) return mapper.readValue(replay,Offer.class);
        Offer result=repository.publication(actor,id,input.expectedVersion(),publish);complete(actor,key,action,hash,result);return result;
    }
    private void complete(Actor actor,String key,String action,String hash,Offer result) throws JsonProcessingException {
        mutations.complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(result),result.id()));
    }
    private void validate(Input input) {
        if(input==null||!validator.validate(input).isEmpty()) throw invalid("优惠券参数无效");
        if(input.endDate().isBefore(input.startDate())||input.maxPerUser()>input.total()) throw invalid("有效期或发行量无效");
        if(input.thresholdAmount().signum()>0&&input.thresholdAmount().compareTo(input.discountAmount())<=0) throw invalid("满减门槛须大于优惠金额");
        if(input.courseIds().stream().distinct().count()!=input.courseIds().size()) throw invalid("适用课程重复");
    }
    private void requireKey(String key) {if(key==null||!key.matches("[A-Za-z0-9._:-]{16,128}")) throw invalid("幂等键无效");}
    private BusinessException invalid(String text) {return new BusinessException(ErrorCode.INVALID_INPUT,text);}
}
