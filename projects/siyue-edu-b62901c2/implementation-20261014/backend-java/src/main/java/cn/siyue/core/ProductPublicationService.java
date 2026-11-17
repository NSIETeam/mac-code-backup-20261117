package cn.siyue.core;

import cn.siyue.core.CommerceDtos.Product;
import cn.siyue.core.CommerceDtos.PublicationInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Narrow publication boundary for legacy product publishers, without catalog editing authority. */
@Service
public class ProductPublicationService {
    private final AccessRepository access;
    private final AcademicCatalogRepository catalog;
    private final CommerceRepository commerce;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /**
     * Wires the publication transaction and its idempotent response store.
     * @param access current identity and permission reader with principal locking
     * @param catalog course scope, status and version locking repository
     * @param commerce product publication and replay authorization repository
     * @param mutations request fingerprint, replay and audit persistence
     * @param mapper serializer for request fingerprints and saved product responses
     */
    public ProductPublicationService(AccessRepository access,AcademicCatalogRepository catalog,
            CommerceRepository commerce,MutationRepository mutations,ObjectMapper mapper) {
        this.access=access;this.catalog=catalog;this.commerce=commerce;this.mutations=mutations;
        this.mapper=mapper;
    }

    /**
     * Publishes or withdraws a course product after locking the current identity and course.
     * A matching retry returns its original product response only after current product access is checked.
     * @param actor authenticated caller; active status and permissions are reloaded inside the transaction
     * @param courseId public course identifier within the caller's authorized catalog scope
     * @param key caller-scoped idempotency key, 16 to 128 permitted identifier characters
     * @param input expected positive course version and optional nonempty, unique class selection
     * @param publish true to publish the product; false to withdraw it
     * @return persisted product state, or the previously committed response for an authorized matching retry
     * @throws BusinessException if input, permission, scope, version or idempotency checks fail
     * @throws JsonProcessingException if fingerprint or response serialization fails; the transaction rolls back
     */
    @Transactional(rollbackFor=Exception.class)
    public Product publish(Actor actor,String courseId,String key,PublicationInput input,boolean publish)
            throws JsonProcessingException {
        validate(courseId,key,input);
        actor=access.lockActive(actor.id());
        actor.requireAny("product.publish","commerce.manage");
        var course=catalog.lockCourseStatus(actor,courseId);
        String action=publish?"product.publish":"product.unpublish";
        String hash=mutations.hash(mapper.writeValueAsString(List.of(courseId,input)));
        String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null) {
            Product result=mapper.readValue(replay,Product.class);
            commerce.authorize(actor,result.id(),"product");
            return result;
        }
        if(input.expectedVersion()!=course.version()) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE,"课程已更新，请刷新后重试");
        }
        Product result=commerce.publishCourse(actor,course,input,publish);
        mutations.complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(result),result.id()));
        return result;
    }

    private boolean validClasses(List<String> ids) {
        return !ids.isEmpty()&&ids.size()<=1000&&new HashSet<>(ids).size()==ids.size()
            &&ids.stream().allMatch(id->id!=null&&id.matches("[A-Za-z0-9_-]{1,120}"));
    }
    private void validate(String courseId,String key,PublicationInput input) {
        if(courseId==null||!courseId.matches("[A-Za-z0-9_-]{1,120}")
                ||key==null||!key.matches("[A-Za-z0-9._:-]{16,128}")
                ||input==null||input.expectedVersion()==null||input.expectedVersion()<1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"商品发布参数无效");
        }
        if(input.classIds()!=null&&!validClasses(input.classIds())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"班级范围无效或重复");
        }
    }
}
