package cn.siyue.core;

import static cn.siyue.core.TaskRoutingDtos.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TaskRoutingController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties={"siyue.web-origin=https://crm.example.com","spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example","spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class TaskRoutingControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean AccessRepository access;
    @MockitoBean TaskRoutingService service;
    private Actor actor(){return new Actor(1,"manager","all",Set.of(),Set.of("crm.task.manage","crm.task.read","crm.task.write","crm.task.assign"));}
    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor token(){when(access.resolve("https://issuer.example","subject")).thenReturn(actor());return jwt().jwt(j->j.issuer("https://issuer.example").subject("subject"));}
    @Test void globalTaskAndEmployeeTabsNeedNoBatch()throws Exception{
        when(service.tasks(any(),any())).thenReturn(new Page<>(List.of(),0,2,10));when(service.employees(any(),any())).thenReturn(new Page<>(List.of(),0,2,10));
        for(String tab:List.of("tasks","employees"))mvc.perform(get("/api/v2/crm/task-routing/"+tab+"?view=scope&page=2&size=10&ownerId=worker").with(token())).andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2));
        verify(service).tasks(eq(actor()),argThat(q->q.batchId()==null&&q.ownerId().equals("worker")&&q.view().equals("scope")));
        verify(service).employees(eq(actor()),argThat(q->q.batchId()==null&&q.page()==2));
    }
    @Test void batchTaskQueryCannotOverridePathWithOtherBatch()throws Exception{
        when(service.tasks(any(),any())).thenReturn(new Page<>(List.of(),0,1,50));
        mvc.perform(get("/api/v2/crm/task-routing/batches/B1/tasks?batchId=B2").with(token())).andExpect(status().isOk());
        verify(service).tasks(eq(actor()),argThat(q->q.batchId().equals("B1")&&q.view().equals("scope")));
    }
    @Test void handoffReceiptIsExplicitSuccessfulMinimalAcknowledgment()throws Exception{
        when(service.commitChange(any(),eq("B1"),eq("transfer"),any(),any())).thenReturn(new Receipt(true,"B1","ROUTE_EVT_1","left_scope",null,List.of()));
        mvc.perform(post("/api/v2/crm/task-routing/batches/B1/transfer/commit").with(token()).header("Origin","https://crm.example.com").header("Idempotency-Key","routing-controller-key-1").contentType("application/json").content("{\"input\":{\"expectedManagementVersion\":1,\"supervisorId\":\"M2\",\"reason\":\"交接\"},\"expectedPreview\":{\"action\":\"transfer\",\"batchId\":\"B1\"}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.committed").value(true)).andExpect(jsonPath("$.data.scopeStatus").value("left_scope")).andExpect(jsonPath("$.data.tasks").isEmpty());
    }
    @Test void createCommitRequiresReviewAndIdempotencyKey()throws Exception{
        mvc.perform(post("/api/v2/crm/task-routing/batches/commit").with(token()).header("Origin","https://crm.example.com").contentType("application/json").content("{}")) .andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void supervisorLookupAcceptsBatchForScopedTransfer()throws Exception{
        when(service.supervisors(any(),any())).thenReturn(new Page<>(List.of(),0,1,50));
        mvc.perform(get("/api/v2/crm/task-routing/supervisors?batchId=B1").with(token())).andExpect(status().isOk());verify(service).supervisors(eq(actor()),argThat(q->q.batchId().equals("B1")&&q.campusId()==null));
    }
}
