package cn.siyue.core;

import cn.siyue.core.LeadPolicyDtos.ApplyInput;
import cn.siyue.core.LeadPolicyDtos.Candidate;
import cn.siyue.core.LeadPolicyDtos.LeadVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the legacy-compatible reminder and recycle policy as an auditable system employee. */
@Component
@ConditionalOnProperty(name = "siyue.crm.policy.worker.enabled", havingValue = "true")
public class LeadPolicyWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(LeadPolicyWorker.class);
    private final AccessRepository access;
    private final LeadPolicyService service;
    private final String issuer;
    private final String subject;

    public LeadPolicyWorker(AccessRepository access, LeadPolicyService service,
            @Value("${siyue.crm.policy.worker.issuer}") String issuer,
            @Value("${siyue.crm.policy.worker.subject}") String subject) {
        this.access = access;
        this.service = service;
        this.issuer = issuer;
        this.subject = subject;
    }

    /** Checks a bounded page once a minute; every selected action is revalidated by the service. */
    @Scheduled(fixedDelayString = "${siyue.crm.policy.worker.interval.ms:60000}", initialDelay = 60000)
    public void execute() {
        String previousTrace = MDC.get("traceId");
        MDC.put("traceId", UUID.randomUUID().toString());
        try {
            Actor actor = access.resolve(issuer, subject);
            var preview = service.preview(actor, 1, 100);
            for (Candidate candidate : preview.items()) {
                apply(actor, preview.policy().version(), candidate);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Lead policy worker run failed category={}", exception.getClass().getName());
        } finally {
            if (previousTrace == null) { MDC.remove("traceId"); }
            else { MDC.put("traceId", previousTrace); }
        }
    }

    private void apply(Actor actor, long policyVersion, Candidate candidate) {
        try {
            ApplyInput input = new ApplyInput(policyVersion,
                java.util.List.of(new LeadVersion(candidate.leadId(), candidate.version(), candidate.kind())));
            service.apply(actor, key(policyVersion, candidate), input);
        } catch (RuntimeException | JsonProcessingException exception) {
            LOGGER.warn("Lead policy candidate was not applied; category={}; retry=next-run",
                exception.getClass().getName());
        }
    }

    private String key(long policyVersion, Candidate candidate) {
        String material = policyVersion + "|" + candidate.leadId() + "|" + candidate.version() + "|"
            + candidate.kind() + "|" + candidate.activityAt();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return "policy-worker-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
