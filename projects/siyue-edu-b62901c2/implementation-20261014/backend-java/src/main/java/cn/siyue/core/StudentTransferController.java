package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Exposes target-class options and permanent batch transfers under /api/v2/academic. Delegates enrollment
 * compatibility, capacity, authorization and atomic placement changes to StudentTransferService.
 */
@RestController
@RequestMapping("/api/v2/academic")
@Validated
public class StudentTransferController {
    private final AccessRepository access;
    private final StudentTransferService service;
    /**
     * Creates the academic HTTP adapter with local identity resolution and its business service.
     * @param access resolves the JWT issuer and subject to an active local account and current grants
     * @param service implements the scoped business operations and their transaction/replay rules
     */
    public StudentTransferController(AccessRepository access,StudentTransferService service) {
        this.access=access;this.service=service;
    }
    /**
     * Lists paginated target-class options permitted for transferring the selected student.
     * @param jwt accepted JWT resolving to an active caller with enrollment.manage, enrollment.transfer or
     *     enrollment.batchTransfer
     * @param studentId public student identifier used to filter compatible targets
     * @param page one-based page number
     * @param size requested page size, from 1 through 100
     * @return success response containing scoped compatible target options
     * @throws BusinessException if no active local account matches the JWT, or if permission, pagination, student
     *     scope, or target checks fail
     */
    @GetMapping("/students/{studentId}/transfer-options")
    public ApiResponse<StudentTransferDtos.Options> options(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String studentId,
            @RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="100") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.options(actor(jwt),studentId,page,size));
    }
    /**
     * Moves one to 500 active enrollments to a scoped target class as one audited, idempotent operation.
     * @param jwt accepted JWT resolving to an active caller with enrollment.manage or enrollment.batchTransfer;
     *     one-item transfers also accept enrollment.transfer
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input 1–500 unique enrollment IDs with expected versions at least 1, target class ID, and nonblank
     *     transfer reason of at most 500 characters
     * @return success response containing the resulting enrollment placements and transfer identifiers
     * @throws BusinessException when no active local account matches the JWT, or when permission, input, version,
     *     capacity, class compatibility, or scope checks fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/enrollments/batch-transfer")
    public ApiResponse<StudentTransferDtos.Receipt> transfer(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,@RequestBody @Valid StudentTransferDtos.Input input)
            throws JsonProcessingException {
        return ApiResponse.success(service.transfer(actor(jwt),key,input));
    }
    private Actor actor(Jwt jwt) {return access.resolve(jwt.getIssuer().toString(),jwt.getSubject());}
}
