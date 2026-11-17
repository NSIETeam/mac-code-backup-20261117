package cn.siyue.core;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditServiceTest {
    private final AuditRepository repository=mock(AuditRepository.class);
    private final AuditService service=new AuditService(repository);

    @Test void nonGlobalActorCannotSearchAnotherEmployee() {
        Actor actor=new Actor(4,"staff-4","own",Set.of(),Set.of("audit.read"));
        var query=new AuditDtos.AuditQuery("staff-5",null,null,null,null,1,50);
        assertThrows(BusinessException.class,()->service.search(actor,query));
        verifyNoInteractions(repository);
    }

    @Test void dateWindowMustBeBoundedAndComplete() {
        Actor actor=new Actor(4,"staff-4","all",Set.of(),Set.of("audit.read"));
        Instant from=Instant.parse("2026-01-01T00:00:00Z");
        assertThrows(BusinessException.class,()->service.search(actor,
            new AuditDtos.AuditQuery(null,null,null,from,null,1,50)));
        assertThrows(BusinessException.class,()->service.search(actor,
            new AuditDtos.AuditQuery(null,null,null,from,from.plus(Duration.ofDays(94)),1,50)));
        verifyNoInteractions(repository);
    }

    @Test void globalActorCanReadPagedAuditEvents() {
        Actor actor=new Actor(4,"staff-4","all",Set.of(),Set.of("audit.read"));
        var page=new AuditDtos.AuditPage(List.of(),0,1,20);
        var query=new AuditDtos.AuditQuery(null,"academic.class.update",null,null,null,1,20);
        when(repository.search(actor,query)).thenReturn(page);
        assertEquals(page,service.search(actor,query));
        verify(repository).search(actor,query);
    }

    @Test void exactLegacyAuditQueryGrantKeepsOwnActorScope() {
        Actor actor = new Actor(4, "staff-4", "own", Set.of(), Set.of("audit.query"));
        var query = new AuditDtos.AuditQuery(null, null, null, null, null, 1, 20);
        var page = new AuditDtos.AuditPage(List.of(), 0, 1, 20);
        when(repository.search(actor, query)).thenReturn(page);

        assertEquals(page, service.search(actor, query));
        assertThrows(BusinessException.class, () -> service.search(actor,
            new AuditDtos.AuditQuery("staff-5", null, null, null, null, 1, 20)));
        verify(repository).search(actor, query);
    }
}
