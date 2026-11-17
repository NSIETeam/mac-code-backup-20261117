package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.siyue.core.AcademicRescheduleDtos.RequestInput;
import cn.siyue.core.AcademicRescheduleDtos.RequestReceipt;
import cn.siyue.core.AcademicRescheduleDtos.ReviewInput;
import cn.siyue.core.AcademicRescheduleDtos.ListQuery;
import cn.siyue.core.AcademicRescheduleDtos.RequestPage;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AcademicRescheduleServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final AcademicRepository academicRepository = mock(AcademicRepository.class);
    private final AcademicRescheduleRepository requests = mock(AcademicRescheduleRepository.class);
    private final AcademicService academic = mock(AcademicService.class);
    private final AcademicRescheduleMutations mutations = mock(AcademicRescheduleMutations.class);
    private final AcademicRescheduleService service = new AcademicRescheduleService(access, academicRepository,
        requests, academic, mutations);

    @Test void teacherCanRequestTheirOwnFutureSessionWithoutMovingIt() throws Exception {
        Actor teacher = new Actor(7, "teacher", "all", Set.of(), Set.of("session.reschedule.request"));
        when(access.lockActive(7)).thenReturn(teacher);
        Instant start = Instant.parse("2027-03-01T09:00:00Z");
        var row = new AcademicRepository.SessionScheduleRow(3, 4, 1, 1, 7, "session-1", "class-1",
            "Room 1", start, start.plusSeconds(3600), "scheduled", 2, 60);
        when(academicRepository.scheduleRow(teacher, "session-1")).thenReturn(row);
        when(mutations.hash(any())).thenReturn("request-hash");
        when(mutations.replay(teacher, "request-key-000001", "academic.session.reschedule.request",
            "request-hash", RequestReceipt.class)).thenReturn(null);
        when(requests.classVersion(4)).thenReturn(5L);
        RequestReceipt receipt = new RequestReceipt("RSRQ_1", "pending", "session-1",
            Instant.parse("2027-03-02T09:00:00Z"), 1);
        when(requests.create(teacher, row, 5, receipt.requestedStartsAt(), "病假")).thenReturn(receipt);
        when(mutations.serialize(receipt)).thenReturn("receipt-json");
        RequestInput input = new RequestInput("class-1", "session-1", "2027-03-02", "17:00–18:00", "病假", 2);

        assertEquals(receipt, service.request(teacher, "request-key-000001", input));
        verify(requests).lockTimetable(4, 3);
        verify(requests).create(teacher, row, 5, receipt.requestedStartsAt(), "病假");
        verify(academic, never()).moveSession(any(), anyString(), anyString(), any());
    }

    @Test void wrongTeacherAndNonReviewerCannotAct() {
        Actor teacher = new Actor(7, "teacher", "all", Set.of(), Set.of("session.reschedule.request"));
        when(access.lockActive(7)).thenReturn(teacher);
        Instant start = Instant.parse("2027-03-01T09:00:00Z");
        var row = new AcademicRepository.SessionScheduleRow(3, 4, 1, 1, 8, "session-1", "class-1",
            "Room 1", start, start.plusSeconds(3600), "scheduled", 2, 60);
        when(academicRepository.scheduleRow(teacher, "session-1")).thenReturn(row);
        assertThrows(BusinessException.class, () -> service.request(teacher, "request-key-000001",
            new RequestInput("class-1", "session-1", "2027-03-02", "17:00–18:00", "病假", 2)));
        verify(requests, never()).create(any(), any(), anyLong(), any(), anyString());

        Actor requester = new Actor(9, "requester", "all", Set.of(), Set.of("session.reschedule.request"));
        assertThrows(BusinessException.class, () -> service.review(requester, "request-1", "review-key-000001",
            new ReviewInput("approve", null, 1)));
        verifyNoInteractions(mutations);
    }

    @Test void requesterCanReadOwnQueueAndPermissionIsRecheckedAfterActorLock() {
        Actor requester = new Actor(17, "teacher-17", "own", Set.of(), Set.of("session.reschedule.request"));
        RequestPage page = new RequestPage(java.util.List.of(), 0, 1, 50);
        when(access.lockActive(17)).thenReturn(requester);
        when(requests.listVisible(requester, new ListQuery("pending", 1, 50))).thenReturn(page);

        assertEquals(page, service.list(requester, new ListQuery("pending", 1, 50)));
        verify(requests).listVisible(requester, new ListQuery("pending", 1, 50));

        Actor revoked = new Actor(17, "teacher-17", "own", Set.of(), Set.of());
        when(access.lockActive(17)).thenReturn(revoked);
        assertThrows(BusinessException.class, () -> service.list(requester, new ListQuery(null, 1, 50)));
        verify(requests, never()).listVisible(revoked, new ListQuery(null, 1, 50));
    }

    @Test void requestRejectsInvalidBusinessDateAndWrongDurationBeforeTakingTimetableLocks() {
        Actor teacher = new Actor(7, "teacher", "all", Set.of(), Set.of("session.reschedule.request"));
        when(access.lockActive(7)).thenReturn(teacher);
        Instant start = Instant.parse("2027-03-01T09:00:00Z");
        var row = new AcademicRepository.SessionScheduleRow(3, 4, 1, 1, 7, "session-1", "class-1",
            "Room 1", start, start.plusSeconds(3600), "scheduled", 2, 60);
        when(academicRepository.scheduleRow(teacher, "session-1")).thenReturn(row);

        BusinessException invalidDate = assertThrows(BusinessException.class, () -> service.request(teacher,
            "request-date-key-000001", new RequestInput("class-1", "session-1", "2027-02-30",
                "17:00–18:00", "病假", 2)));
        assertEquals(ErrorCode.INVALID_INPUT, invalidDate.code());
        BusinessException wrongDuration = assertThrows(BusinessException.class, () -> service.request(teacher,
            "request-duration-key-0001", new RequestInput("class-1", "session-1", "2027-03-02",
                "17:00–17:30", "病假", 2)));
        assertEquals(ErrorCode.INVALID_INPUT, wrongDuration.code());
        verify(requests, never()).lockTimetable(anyLong(), anyLong());
        verify(requests, never()).create(any(), any(), anyLong(), any(), anyString());
    }
}
