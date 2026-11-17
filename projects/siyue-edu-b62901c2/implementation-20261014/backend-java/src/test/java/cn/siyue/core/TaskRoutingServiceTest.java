package cn.siyue.core;

import static cn.siyue.core.TaskRoutingDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class TaskRoutingServiceTest {
    private final AccessRepository access=mock(AccessRepository.class);
    private final TaskRoutingReadRepository reads=mock(TaskRoutingReadRepository.class);
    private final TaskRoutingPlanRepository plans=mock(TaskRoutingPlanRepository.class);
    private final TaskRoutingMutationRepository writes=mock(TaskRoutingMutationRepository.class);
    private final MutationRepository ledger=mock(MutationRepository.class);
    private final TaskRoutingService service=new TaskRoutingService(access,reads,plans,writes,ledger);
    private final Actor actor=new Actor(1,"manager","own",Set.of(),Set.of("crm.task.read","crm.task.write","crm.task.assign","crm.task.manage"));
    private final String key="routing-original-key-123";
    private CreatePreview createPreview(){return new CreatePreview("create","campus","supervisor","manager",Instant.EPOCH,Instant.parse("2099-01-01T00:00:00Z"),1,1,0,Map.of(),List.of(),List.of());}
    private ChangeInput change(){return new ChangeInput(1,List.of(new Selection("task",1)),"even",List.of("worker"),null,"none",null,null,null,null);}
    private ChangePreview preview(){return new ChangePreview("allocation","batch",1,1,1,0,List.of(),List.of(),Map.of("worker",1L),0,"manager","manager",Instant.EPOCH,Instant.parse("2099-01-01T00:00:00Z"));}
    private Receipt receipt(){return new Receipt(true,"batch","ROUTE_EVT_1","left_scope",null,List.of());}
    private void active(){when(access.lockActive(1)).thenReturn(actor);when(ledger.hash(anyString())).thenReturn("hash");}
    @Test void createRechecksCurrentPermissionBeforeReplay(){active();when(access.lockActive(1)).thenReturn(new Actor(1,"manager","own",Set.of(),Set.of("crm.task.write")));assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,()->service.commitCreate(actor,key,new CreateCommit(null,createPreview()))).code());verifyNoInteractions(plans,writes,ledger);}
    @Test void createReplayNeverRebuildsExpiredOrAlreadyReservedTargets(){active();var r=receipt();when(ledger.replay(actor,key,"crm.task.routing.create","hash")).thenReturn(TaskRoutingSupport.json(r));when(writes.replay(actor,r)).thenReturn(r);assertEquals(r,service.commitCreate(actor,key,new CreateCommit(null,createPreview())));verifyNoInteractions(plans);verify(ledger,never()).complete(any(),any());}
    @Test void changeReplayBeforeVersionAndScopePlanPreservesHandoffReceipt(){active();var r=receipt();when(ledger.replay(actor,key,"crm.task.routing.allocation","hash")).thenReturn(TaskRoutingSupport.json(r));when(writes.replay(actor,r)).thenReturn(r);assertEquals(r,service.commitChange(actor,"batch","allocation",key,new ChangeCommit(change(),preview())));verifyNoInteractions(plans);}
    @Test void revokedManagementCannotReplay(){active();when(access.lockActive(1)).thenReturn(new Actor(1,"manager","own",Set.of(),Set.of("crm.task.read","crm.task.write","crm.task.assign")));assertThrows(BusinessException.class,()->service.commitChange(actor,"batch","allocation",key,new ChangeCommit(change(),preview())));verifyNoInteractions(plans,writes,ledger);}
    @Test void stalePreviewNeverWrites(){active();when(plans.change(actor,"batch","allocation",change(),true)).thenReturn(new TaskRoutingPlanRepository.ChangePlan(preview(),null));var wrong=new ChangePreview("allocation","batch",1,2,2,0,List.of(),List.of(),Map.of(),0,"manager","manager",Instant.EPOCH,Instant.EPOCH);assertEquals(ErrorCode.PREVIEW_STALE,assertThrows(BusinessException.class,()->service.commitChange(actor,"batch","allocation",key,new ChangeCommit(change(),wrong))).code());verifyNoInteractions(writes);}
    @Test void matchingChangeCommitsAndJournalsExactlyOnce(){active();var p=new TaskRoutingPlanRepository.ChangePlan(preview(),null);when(plans.change(actor,"batch","allocation",change(),true)).thenReturn(p);when(writes.change(actor,p,null)).thenReturn(receipt());assertEquals(receipt(),service.commitChange(actor,"batch","allocation",key,new ChangeCommit(change(),preview())));verify(writes).change(actor,p,null);verify(ledger).complete(eq(actor),argThat(w->w.action().equals("crm.task.routing.allocation")&&w.resourceId().equals("batch")));}
    @Test void blockedPlanDoesNotPartiallyCommit(){active();var p=new ChangePreview("allocation","batch",1,1,0,1,List.of(),List.of(new Blocked("task","source_owner_unavailable")),Map.of(),1,"manager","manager",Instant.EPOCH,Instant.EPOCH);when(plans.change(actor,"batch","allocation",change(),true)).thenReturn(new TaskRoutingPlanRepository.ChangePlan(p,null));assertThrows(BusinessException.class,()->service.commitChange(actor,"batch","allocation",key,new ChangeCommit(change(),p)));verifyNoInteractions(writes);}
    @Test void creationWithZeroReservedRequiresNewSelection(){active();var p=createPreview();when(plans.create(actor,null)).thenReturn(new TaskRoutingPlanRepository.CreatePlan(p,List.of(),1));assertThrows(BusinessException.class,()->service.commitCreate(actor,key,new CreateCommit(null,p)));verifyNoInteractions(writes);}
    @Test void globalQueriesArePassedToScopedServerRepository(){var q=new Query("scope",null,null,null,null,null,null,null,null,null,"worker","pending",2,10);service.tasks(actor,q);service.employees(actor,q);verify(reads).tasks(actor,q);verify(reads).employees(actor,q);}
    @Test void unknownOperationIsNotUsedAsJournalAction(){active();assertThrows(BusinessException.class,()->service.commitChange(actor,"batch","delete",key,new ChangeCommit(change(),preview())));verifyNoInteractions(plans,writes,ledger);}
    @Test void newExecutionScopeKeepsLegacyScopeButRestrictsNewTasksToOwner(){var clause=TaskRoutingScope.execution(actor,new ScopeSql.Clause("t.campus_id=?",List.of(3)));assertTrue(clause.sql().contains("delivery_mode='legacy'"));assertTrue(clause.sql().contains("delivery_mode<>'legacy'"));assertEquals(List.of(3,1L),clause.args());}
    @Test void ownManagerScopeIsActualDelegation(){var clause=TaskRoutingScope.management(actor);assertEquals("b.supervisor_id=?",clause.sql());assertEquals(List.of(1L),clause.args());}
    @Test void departmentAndCustomerScopeCannotBeBorrowedForManagement(){assertThrows(BusinessException.class,()->TaskRoutingScope.management(new Actor(1,"worker","all",Set.of(),Set.of("crm.task.read"))));}
    @Test void nullRecipientIsValidationFailureNotNpe(){var repo=new TaskRoutingAllocation(mock(org.springframework.jdbc.core.JdbcTemplate.class));var input=new EmployeePlan("even",Arrays.asList("worker",null),null);assertEquals(ErrorCode.INVALID_INPUT,assertThrows(BusinessException.class,()->repo.validate(actor,1,input,2)).code());}
}
