package cn.siyue.core;

import cn.siyue.core.CoursePublicationDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Fixed teaching-version endpoints, separate from commerce product publication.
 * JWT-authenticated employee permissions and catalog scope are enforced by the reader and publication service.
 * @see CoursePublicationService
 * @see CoursePublicationReader
 */
@RestController
@RequestMapping("/api/v2/academic/courses/{courseId}")
public class CoursePublicationController {
    private final AccessRepository access;
    private final CoursePublicationService service;
    private final CoursePublicationReader reader;
    /** Injects JWT actor resolution, publication writes and publication reads.
     * @param access repository that resolves the authenticated issuer and subject
     * @param service versioned teaching publication operations
     * @param reader publication snapshot reader
     */
    public CoursePublicationController(AccessRepository access,CoursePublicationService service,CoursePublicationReader reader) {this.access=access;this.service=service;this.reader=reader;}
    /** Reads current teaching publication state and the latest immutable snapshot.
     * @param jwt authenticated employee JWT
     * @param courseId public course identifier
     * @return publication status and the latest snapshot, if one exists
     * @throws BusinessException if the actor lacks course read/publication permission or cannot access the course
     */
    @GetMapping("/publication")
    public ApiResponse<View> get(@AuthenticationPrincipal Jwt jwt,@PathVariable String courseId) {
        return ApiResponse.success(reader.get(actor(jwt),courseId));
    }
    /** Publishes the current teaching version with an idempotent command.
     * @param jwt authenticated employee JWT
     * @param courseId public course identifier
     * @param key request idempotency key
     * @param input expected course version
     * @return published immutable teaching snapshot and updated course version
     * @throws JsonProcessingException if request or receipt serialization fails
     * @throws BusinessException if permission, campus scope, course state, version, lessons, or retry checks fail
     */
    @PostMapping("/publish")
    public ApiResponse<View> publish(@AuthenticationPrincipal Jwt jwt,@PathVariable String courseId,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Input input) throws JsonProcessingException {
        return ApiResponse.success(service.publish(actor(jwt),courseId,key,input));
    }
    private Actor actor(Jwt jwt) {return access.resolve(jwt.getIssuer().toString(),jwt.getSubject());}
}
