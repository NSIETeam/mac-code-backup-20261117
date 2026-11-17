package cn.siyue.core;

import cn.siyue.core.LeadPolicyDtos.ApplyInput;
import cn.siyue.core.LeadPolicyDtos.LeadVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeadPolicyServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final LeadPolicyRepository repository = mock(LeadPolicyRepository.class);
    private final LeadRepository leads = mock(LeadRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final LeadPolicyService service = new LeadPolicyService(access, repository, leads, mutations,
        new ObjectMapper().findAndRegisterModules());

    @Test void previewRequiresCustomerReadBeforeQueryingCandidates() {
        Actor actor = new Actor(8, "manager", "all", Set.of(), Set.of("crm.lead.assign"));
        assertThrows(BusinessException.class, () -> service.preview(actor, 1, 20));
        verifyNoInteractions(repository);
    }

    @Test void exactLegacyPreviewGrantReadsOnlyScopedCandidateQuery() {
        Actor actor = new Actor(9, "policy-preview", "campus", Set.of(3L), Set.of("leadPolicy.preview"));
        var policy = new LeadPolicyDtos.Policy(14, 21, 4, 48, 2, java.time.Instant.EPOCH, null);
        when(repository.get()).thenReturn(policy);
        when(repository.candidates(any())).thenReturn(new LeadPolicyRepository.Page<>(List.of(), 0));

        var preview = service.preview(actor, 1, 25);

        assertEquals(2, preview.policy().version());
        assertEquals(0, preview.total());
        verify(repository).candidates(argThat(query -> query.actor().equals(actor) && query.page() == 1
            && query.size() == 25));
    }

    @Test void exactLegacyPublishGrantCanUpdatePolicyButDoesNotGrantPreview() throws Exception {
        Actor actor = new Actor(10, "policy-publisher", "all", Set.of(), Set.of("leadPolicy.publish"));
        when(access.lockActive(actor.id())).thenReturn(actor);
        var current = new LeadPolicyDtos.Policy(14, 21, 4, 48, 1, java.time.Instant.EPOCH, null);
        var updated = new LeadPolicyDtos.Policy(7, 10, 8, 48, 2, java.time.Instant.EPOCH, actor.publicId());
        when(repository.lockPolicy()).thenReturn(current);
        when(repository.update(eq(actor), any())).thenReturn(updated);
        when(mutations.hash(anyString())).thenReturn("policy-hash");

        assertEquals(updated, service.update(actor, "legacy-policy-publish-0001",
            new LeadPolicyDtos.PolicyInput(7, 10, 8, 1)));
        assertThrows(BusinessException.class, () -> service.preview(actor, 1, 25));
        verify(repository).update(eq(actor), any());
    }

    @Test void policyUpdateRejectsScopeRevocationAfterAuthentication() {
        Set<String> permissions = Set.of("crm.lead.assign", "crm.lead.policy.manage");
        Actor authenticated = new Actor(8, "manager", "all", Set.of(), permissions);
        when(access.lockActive(8)).thenReturn(new Actor(8, "manager", "campus", Set.of(1L), permissions));
        BusinessException error = assertThrows(BusinessException.class, () -> service.update(authenticated,
            "lead-policy-scope-revoked-001", new LeadPolicyDtos.PolicyInput(7, 14, 24, 1)));
        assertEquals(ErrorCode.FORBIDDEN, error.code());
        verifyNoInteractions(repository, leads, mutations);
    }

    @Test void policyApplyRejectsDuplicateSelectionBeforeLockingOrWriting() {
        Actor actor = new Actor(8, "manager", "all", Set.of(), Set.of("crm.lead.assign",
            "crm.customer.read", "crm.lead.policy.manage"));
        ApplyInput input = new ApplyInput(1, List.of(new LeadVersion("LEAD_1", 1, "warning"),
            new LeadVersion("LEAD_1", 1, "warning")));
        assertThrows(BusinessException.class, () -> service.apply(actor,
            "lead-policy-apply-duplicate-123", input));
        verifyNoInteractions(access, repository, leads, mutations);
    }
}
