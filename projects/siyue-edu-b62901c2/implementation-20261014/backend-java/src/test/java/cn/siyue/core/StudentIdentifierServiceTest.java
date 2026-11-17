package cn.siyue.core;

import cn.siyue.core.StudentIdentifierDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudentIdentifierServiceTest {
    final AccessRepository access=mock(AccessRepository.class);
    final StudentIdentifierRepository repository=mock(StudentIdentifierRepository.class);
    final MutationRepository mutations=mock(MutationRepository.class);
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final StudentIdentifierService service=new StudentIdentifierService(access,repository,mutations,mapper);
    final Actor writer=new Actor(1,"EMP_1","own",Set.of(),Set.of("crm.customer.read","crm.customer.identity.write","crm.opportunity.intake"));
    final Target target=new Target("CUS_1","STU_1");
    final Link link=new Link(7,"STU_1",3);
    final String key="student_identifier_key_1";
    Identifier row(boolean active){return new Identifier("SID_1","STU_1","external-school","001AbC",active,active?1:2,Instant.EPOCH,active?null:Instant.EPOCH.plusSeconds(10));}
    void authorized(){when(access.lockActive(writer.id())).thenReturn(writer);when(repository.authorize(writer,target,true)).thenReturn(link);when(mutations.hash(anyString())).thenReturn("hash");}
    @Test void prospectIntakeCanListBoundedNumbersWithoutAcademicRead(){
        when(repository.authorize(writer,target,false)).thenReturn(link);
        var expected=new Page(List.of(row(true)),1,1,20,3);when(repository.list(link,new Query("active",1,20))).thenReturn(expected);
        assertSame(expected,service.list(writer,target,null));verifyNoInteractions(access,mutations);
    }
    @Test void customerReadAloneDoesNotExposeNumbersAndAcademicFallbackUsesRepositoryScope(){
        Actor none=new Actor(2,"EMP_2","all",Set.of(),Set.of("crm.customer.read"));
        assertThrows(BusinessException.class,()->service.list(none,target,null));verifyNoInteractions(repository);
        Actor academic=new Actor(3,"EMP_3","assigned",Set.of(),Set.of("crm.customer.read","student.read"));
        when(repository.authorize(academic,target,false)).thenReturn(link);service.list(academic,target,new Query("all",2,10));
        verify(repository).authorize(academic,target,false);verify(repository).list(link,new Query("all",2,10));
    }
    @Test void newFilingRechecksCurrentWriteAndLinkedHouseholdBeforeHashAndInsert()throws Exception{
        authorized();Create input=new Create("external-school","001AbC",3);when(repository.create(writer,link,input)).thenReturn(row(true));
        assertEquals(row(true),service.create(writer,target,key,input));var order=inOrder(access,repository,mutations);
        order.verify(access).lockActive(1);order.verify(repository).authorize(writer,target,true);order.verify(mutations).hash(anyString());
        order.verify(mutations).replay(writer,key,"crm.customer.student.identifier.create","hash");order.verify(repository).create(writer,link,input);
        order.verify(mutations).complete(eq(writer),argThat(w->w.resourceId().equals("SID_1")&&w.action().equals("crm.customer.student.identifier.create")));
    }
    @Test void originalSuccessfulCreateReplaysBeforeCurrentStudentVersionCheck()throws Exception{
        authorized();when(repository.authorize(writer,target,true)).thenReturn(new Link(7,"STU_1",99));
        when(mutations.replay(writer,key,"crm.customer.student.identifier.create","hash")).thenReturn(mapper.writeValueAsString(row(true)));
        assertEquals(row(true),service.create(writer,target,key,new Create("external-school","001AbC",3)));
        verify(repository,never()).create(any(),any(),any());verify(mutations,never()).complete(any(),any());
    }
    @Test void revokedGrantOrRelationBlocksSavedReceiptBeforeLedger()throws Exception{
        authorized();when(access.lockActive(1)).thenReturn(new Actor(1,"EMP_1","own",Set.of(),Set.of("crm.customer.read")));
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,()->service.create(writer,target,key,new Create("source","001",3))).code());verifyNoInteractions(repository,mutations);
        authorized();when(repository.authorize(writer,target,true)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND,"missing"));
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(BusinessException.class,()->service.create(writer,target,key,new Create("source","001",3))).code());verifyNoInteractions(mutations);
    }
    @Test void deactivationReplayReauthorizesStudentAndMappingButDoesNotRepeatWrite()throws Exception{
        authorized();when(repository.get(link,"SID_1")).thenReturn(row(false));when(mutations.replay(writer,key,"crm.customer.student.identifier.deactivate","hash")).thenReturn(mapper.writeValueAsString(row(false)));
        assertEquals(row(false),service.deactivate(writer,target,"SID_1",key,new Deactivate(1,"原备案错误，停止使用")));
        verify(repository).get(link,"SID_1");verify(repository,never()).deactivate(any(),any(),any(),any());verify(mutations,never()).complete(any(),any());
    }
    @Test void indexNotReadyLeavesNoNewReceiptAndDoesNotTurnIntoSuccessfulEmptyResult(){
        authorized();when(repository.create(eq(writer),eq(link),any())).thenThrow(new BusinessException(ErrorCode.SEARCH_INDEX_NOT_READY,"maintain"));
        assertEquals(ErrorCode.SEARCH_INDEX_NOT_READY,assertThrows(BusinessException.class,()->service.create(writer,target,key,new Create("source","001",3))).code());verify(mutations,never()).complete(any(),any());
    }
    @Test void invalidOriginalValuesAndMissingExplicitGrantFailBeforeAccountLock(){
        for(String value:List.of("", " 001", "001 ","A\nB","x".repeat(121)))assertThrows(BusinessException.class,()->service.create(writer,target,key,new Create("source",value,3)));
        Actor oldWriter=new Actor(2,"EMP_2","all",Set.of(),Set.of("crm.customer.read","crm.customer.write"));
        assertThrows(BusinessException.class,()->service.create(oldWriter,target,key,new Create("source","001",3)));
        assertThrows(BusinessException.class,()->service.deactivate(writer,target,"SID_1",key,new Deactivate(1,"")));
        verifyNoInteractions(access,repository,mutations);
    }
}
