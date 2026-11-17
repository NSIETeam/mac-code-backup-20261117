package cn.siyue.core;

import cn.siyue.core.CustomerSearchDtos.Query;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerSearchServiceTest {
    private final CrmRepository repository=mock(CrmRepository.class);
    private final CrmService service=new CrmService(mock(AccessRepository.class),repository,mock(MutationRepository.class),new ObjectMapper());
    private Actor actor(String... extra) {
        var grants=new java.util.HashSet<>(List.of(extra)); grants.add("crm.customer.read");
        return new Actor(1,"staff-1","own",Set.of(),Set.copyOf(grants));
    }
    @Test void defaultAndExplicitAutoKeepTheOriginalRepositoryContract() {
        var user=actor(); var expected=new CrmDtos.CustomerPage(List.of(),0,1,50);
        when(repository.customers(user,(String)null,1,50)).thenReturn(expected);
        assertEquals(expected,service.customers(user,new Query(null,null,null,null)));
        assertEquals(expected,service.customers(user,new Query("auto",null,1,50)));
        verify(repository,times(2)).customers(user,(String)null,1,50); verifyNoMoreInteractions(repository);
    }
    @Test void typedSearchPreservesIdentifierSpellingAndBoundedPagination() {
        var user=actor("crm.opportunity.intake"); var input=new Query("student_number","00a-Ａ",2,20);
        var expected=new CrmDtos.CustomerPage(List.of(),0,2,20); when(repository.customers(user,input)).thenReturn(expected);
        assertEquals(expected,service.customers(user,input)); verify(repository).customers(user,input);
    }
    @Test void typedEmptyUnknownAndInvalidBoundsNeverQuery() {
        var user=actor();
        for(var input:List.of(new Query("name"," ",1,20),new Query("all","x",1,20),new Query("name","x",0,20),
                new Query("name","x",1,101),new Query("name","x".repeat(255),1,20))) {
            assertEquals(ErrorCode.INVALID_INPUT,assertThrows(BusinessException.class,()->service.customers(user,input)).code());
        }
        verifyNoInteractions(repository);
    }
    @Test void resourcePrivilegesAreCheckedBeforeAnyValueLookup() {
        var user=actor();
        for(String type:List.of("student_id","student_number","opportunity_id","lead_id"))
            assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,()->service.customers(user,new Query(type,"private-id",1,20))).code());
        verifyNoInteractions(repository);
    }
    @Test void intakeCanSearchProspectsWhileAcademicOnlyRequiresStudentRead() {
        var input=new Query("student_id","STU_prospect",1,20);
        service.customers(actor("crm.opportunity.intake"),input);
        service.customers(actor("student.read"),input);
        verify(repository).customers(actor("crm.opportunity.intake"),input);
        verify(repository).customers(actor("student.read"),input);
    }
    @Test void customerReadIsRequiredEvenForIntake() {
        var user=new Actor(2,"no-reader","all",Set.of(),Set.of("crm.opportunity.intake"));
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,()->service.customers(user,new Query("name","姓名",1,20))).code());
        verifyNoInteractions(repository);
    }
}
