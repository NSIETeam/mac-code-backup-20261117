package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttendanceCorrectionServiceTest {
    private final AccessRepository access=mock(AccessRepository.class);
    private final AttendanceCorrectionRepository repository=mock(AttendanceCorrectionRepository.class);
    private final MutationRepository mutations=mock(MutationRepository.class);
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final AttendanceCorrectionService service=new AttendanceCorrectionService(access,repository,mutations,mapper);
    private final Actor actor=new Actor(1,"teacher","all",Set.of(),Set.of("attendance.manage"));
    private final AttendanceCorrectionDtos.Target target=new AttendanceCorrectionDtos.Target("student","session");
    private final AttendanceCorrectionDtos.Input input=new AttendanceCorrectionDtos.Input("class",1,"absent",null,"误录");
    @Test void midnightUsesShanghaiOriginalRegistrationDay() {
        Instant created=Instant.parse("2026-10-14T15:59:59Z");
        assertDoesNotThrow(()->AttendanceCorrectionRepository.requireOpenWindow(created,created.plusMillis(999)));
        assertThrows(BusinessException.class,()->AttendanceCorrectionRepository.requireOpenWindow(created,
            Instant.parse("2026-10-14T16:00:00Z")));
        assertThrows(BusinessException.class,()->AttendanceCorrectionRepository.requireOpenWindow(created,
            Instant.parse("2026-10-15T01:00:00Z")));
        assertDoesNotThrow(()->AttendanceCorrectionRepository.requireOpenWindow(
            Instant.parse("2026-10-14T16:00:00Z"),Instant.parse("2026-10-15T15:59:59Z")));
    }
    @Test void readOnlyAndUnauthorizedRosterCannotWrite() throws Exception {
        Actor reader=new Actor(1,"reader","all",Set.of(),Set.of("attendance.read"));
        assertThrows(BusinessException.class,()->service.correct(reader,target,"correction-key-123456",input));
        verifyNoInteractions(access,repository,mutations);
        when(access.lockActive(1)).thenReturn(actor);
        when(repository.authorize(actor,target,"class"))
            .thenThrow(new BusinessException(ErrorCode.NOT_FOUND,"无授权"));
        assertThrows(BusinessException.class,()->service.correct(actor,target,"correction-key-123456",input));
        verifyNoInteractions(mutations);
        verify(repository,never()).correct(any(),any(),any(),any());
    }
    @Test void permissionIsRecheckedAfterLock() {
        when(access.lockActive(1)).thenReturn(new Actor(1,"teacher","all",Set.of(),Set.of()));
        assertThrows(BusinessException.class,()->service.correct(actor,target,"correction-key-123456",input));
        verifyNoInteractions(repository,mutations);
    }
    @Test void acceptsExactLegacyAttendanceGrantForVersionedCorrection() throws Exception {
        Actor legacyTeacher=new Actor(1,"teacher","all",Set.of(),Set.of("session.attendance"));
        when(access.lockActive(1)).thenReturn(legacyTeacher);
        var member=new AcademicRepository.AttendanceMember(2,3,4,5,6L,"class","student");
        when(repository.authorize(legacyTeacher,target,"class")).thenReturn(member);
        when(mutations.hash(anyString())).thenReturn("legacy-correction-hash");
        var receipt=new AttendanceCorrectionDtos.Receipt("att","correction","class","session","student",
            "absent",null,2,"teacher",Instant.parse("2026-10-14T01:00:00Z"),"0.00");
        when(repository.correct(legacyTeacher,member,target,input)).thenReturn(receipt);

        assertEquals(receipt,service.correct(legacyTeacher,target,"correction-key-123456",input));
        verify(access).lockActive(1);
        verify(repository).correct(legacyTeacher,member,target,input);
    }
    @Test void invalidReasonAndVersionCannotReachPersistence() {
        assertThrows(BusinessException.class,()->service.correct(actor,target,"correction-key-123456",
            new AttendanceCorrectionDtos.Input("class",0,"absent",null,"误录")));
        assertThrows(BusinessException.class,()->service.correct(actor,target,"correction-key-123456",
            new AttendanceCorrectionDtos.Input("class",1,"absent",null," ")));
        verifyNoInteractions(access,repository,mutations);
    }
    @Test void authorizedRetryReturnsSameCorrectionWithoutReapplyingMoney() throws Exception {
        when(access.lockActive(1)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("hash");
        var receipt=new AttendanceCorrectionDtos.Receipt("att","correction","class","session","student",
            "absent",null,2,"teacher",Instant.parse("2026-10-14T01:00:00Z"),"0.00");
        when(mutations.replay(actor,"correction-key-123456","academic.attendance.correct","hash"))
            .thenReturn(mapper.writeValueAsString(receipt));
        assertEquals(receipt,service.correct(actor,target,"correction-key-123456",input));
        verify(repository).authorize(actor,target,"class");
        verify(repository,never()).correct(any(),any(),any(),any());
        verify(mutations,never()).complete(any(),any());
    }
}
