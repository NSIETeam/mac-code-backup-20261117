package cn.siyue.core;

import cn.siyue.core.CommerceCheckoutDtos.CreateInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import jakarta.validation.ConstraintViolation;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Enforces the Node public-store request allowlist before converting JSON to DTOs. */
@Component
public class PublicStoreRequestParser {
    private static final Set<String> REQUEST_FIELDS = Set.of("type", "promotionId", "buyers", "items",
        "expectedQuoteHash");
    private static final Set<String> BUYER_FIELDS = Set.of("studentId", "name", "phone", "grade");
    private static final Set<String> ITEM_FIELDS = Set.of("buyerIndex", "classId", "couponId");
    private final ObjectMapper mapper;
    private final Validator validator;

    public PublicStoreRequestParser(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    public CreateInput parse(JsonNode root) {
        requireObject(root);
        requireAllowed(root, REQUEST_FIELDS);
        validateBuyers(root.path("buyers"));
        validateItems(root.path("items"));
        CreateInput input = convert(root);
        Set<ConstraintViolation<CreateInput>> violations = validator.validate(input);
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "公开商城申请字段无效");
        }
        return input;
    }

    private void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "公开商城申请格式无效");
        }
    }

    private void validateBuyers(JsonNode buyers) {
        if (!buyers.isArray()) { return; }
        for (JsonNode buyer : buyers) {
            requireObject(buyer);
            requireAllowed(buyer, BUYER_FIELDS);
        }
    }

    private void validateItems(JsonNode items) {
        if (!items.isArray()) { return; }
        for (JsonNode item : items) {
            requireObject(item);
            requireAllowed(item, ITEM_FIELDS);
            if (item.has("couponId") && !item.get("couponId").isNull()
                    && !item.get("couponId").asText().isBlank()) {
                throw new BusinessException(ErrorCode.IDENTITY_VERIFICATION_REQUIRED,
                    "公开报价不允许指定未经核对的学生优惠券");
            }
        }
    }

    private void requireAllowed(JsonNode node, Set<String> allowed) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "公开商城申请包含不支持的字段");
            }
        });
    }

    private CreateInput convert(JsonNode node) {
        try { return mapper.treeToValue(node, CreateInput.class); }
        catch (JsonProcessingException error) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "公开商城申请格式无效");
        }
    }
}
