package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicQueryServiceTest {
    private final AcademicRepository repository = mock(AcademicRepository.class);
    private final AcademicCatalogRepository catalog = mock(AcademicCatalogRepository.class);
    private final AcademicService service = new AcademicService(mock(AccessRepository.class), repository, catalog,
        mock(MutationRepository.class), new ObjectMapper());
    private final Actor actor = new Actor(7, "teacher", "campus", Set.of(2L),
        Set.of("student.read", "class.read", "report.read"));

    @Test void defaultsAndMineArePassedAsNarrowingQueries() {
        service.students(actor, new AcademicDtos.StudentQuery(null, null, null, null));
        verify(repository).students(actor, new AcademicDtos.StudentQuery("scope", null, 1, 50));
        service.classes(actor, new AcademicDtos.ClassQuery("mine", "campus", "teacher", "101", null, null, 1, 20, null, null, null, null, null, null, null, null, null, null));
        verify(catalog).classes(actor, new AcademicDtos.ClassQuery("mine", "campus", "teacher", "101", null, null, 1, 20, null, null, null, null, null, null, null, null, null, null));
        service.studentClasses(actor, "student", new AcademicDtos.StudentQuery("mine", null, null, null));
        verify(catalog).studentClasses(actor, "student", new AcademicDtos.StudentQuery("mine", null, 1, 50));
    }
    @Test void partialReversedOrUnboundedPeriodsFailBeforeQuerying() {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        assertThrows(BusinessException.class, () -> service.classes(actor,
            new AcademicDtos.ClassQuery("mine", null, null, null, from, null, 1, 20, null, null, null, null, null, null, null, null, null, null)));
        assertThrows(BusinessException.class, () -> service.classes(actor,
            new AcademicDtos.ClassQuery("mine", null, null, null, from, from, 1, 20, null, null, null, null, null, null, null, null, null, null)));
        assertThrows(BusinessException.class, () -> service.classes(actor,
            new AcademicDtos.ClassQuery("mine", null, null, null, from, from.plusSeconds(367L * 86400), 1, 20, null, null, null, null, null, null, null, null, null, null)));
        assertThrows(BusinessException.class, () -> service.students(actor,
            new AcademicDtos.StudentQuery("all", null, 1, 20)));
        verifyNoInteractions(repository, catalog);
    }
    @Test void normalAcademicUsersGetOptionsWithoutAccessAdministrationPermission() {
        service.options(actor, "campus");
        verify(catalog).options(actor, "campus");
        Actor forbidden = new Actor(8, "other", "all", Set.of(), Set.of("system.access.manage"));
        assertThrows(BusinessException.class, () -> service.options(forbidden, null));
        verify(catalog, never()).options(forbidden, null);
    }
    @Test void personalMetricsUseShanghaiCalendarMonthAndExplicitView() {
        service.teachingMetrics(actor, "2026-10", "mine");
        verify(repository).teachingMetrics(actor, new AcademicDtos.TeachingPeriod("2026-10", "mine",
            Instant.parse("2026-09-30T16:00:00Z"), Instant.parse("2026-10-31T16:00:00Z")));
        assertThrows(BusinessException.class, () -> service.teachingMetrics(actor, "2026-10", "all"));
    }
}
