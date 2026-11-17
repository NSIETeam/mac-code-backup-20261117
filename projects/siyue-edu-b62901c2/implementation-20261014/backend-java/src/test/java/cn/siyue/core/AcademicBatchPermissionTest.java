package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicBatchPermissionTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final AcademicBatchEnrollmentRepository batches = mock(AcademicBatchEnrollmentRepository.class);
    private final AcademicServiceSupport support = new AcademicServiceSupport(access,
        mock(AcademicRepository.class), mock(AcademicCatalogRepository.class), mutations, new ObjectMapper());
    private final Actor legacy = new Actor(7, "legacy", "campus", Set.of(1L),
        Set.of("enrollment.batchCreate"));
    private final AcademicDtos.EnrollmentBatchInput input = new AcademicDtos.EnrollmentBatchInput(
        "class", List.of("student"), null, null, null, null);

    @Test void legacyBatchGrantRetainsScopedAuthorizationAndAuditWithoutOrderRead() throws Exception {
        when(access.lockActive(7)).thenReturn(legacy);
        when(mutations.hash(anyString())).thenReturn("hash");
        when(batches.create(eq(legacy), eq("class"), anyList(), any())).thenReturn(List.of());
        support.enrollBatch(legacy, "batch-permission-key-12345", input, batches);
        verify(batches).authorize(eq(legacy), eq("class"), anyList(), isNull());
        verify(batches).create(eq(legacy), eq("class"), anyList(), any());
        verify(mutations).complete(eq(legacy), argThat(write ->
            write.action().equals("academic.enrollment.batchCreate")));
    }

    @Test void revokedLegacyGrantCannotReplayOrWrite() {
        when(access.lockActive(7)).thenReturn(new Actor(7, "legacy", "campus", Set.of(1L), Set.of()));
        assertThrows(BusinessException.class, () -> support.enrollBatch(legacy,
            "batch-permission-key-12345", input, batches));
        verifyNoInteractions(mutations);
        verify(batches, never()).authorize(any(), anyString(), anyList(), any());
        verify(batches, never()).create(any(), anyString(), anyList(), any());
    }

    @Test void nativeManagementStillRequiresOrderRead() {
        Actor nativeManager = new Actor(7, "native", "campus", Set.of(1L), Set.of("enrollment.manage"));
        assertThrows(BusinessException.class, () -> support.enrollBatch(nativeManager,
            "batch-permission-key-12345", input, batches));
        verifyNoInteractions(access, batches, mutations);
    }

    @Test void legacyGrantCannotBypassClassOrStudentScope() {
        when(access.lockActive(7)).thenReturn(legacy);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "scope"))
            .when(batches).authorize(eq(legacy), eq("class"), anyList(), isNull());
        assertThrows(BusinessException.class, () -> support.enrollBatch(legacy,
            "batch-permission-key-12345", input, batches));
        verifyNoInteractions(mutations);
        verify(batches, never()).create(any(), anyString(), anyList(), any());
    }
}
