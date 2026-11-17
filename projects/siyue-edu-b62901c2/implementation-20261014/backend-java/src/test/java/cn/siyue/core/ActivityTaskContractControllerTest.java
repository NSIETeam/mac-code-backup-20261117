package cn.siyue.core;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({ActivityAudienceController.class,CrmTaskController.class})
@Import(SecurityConfiguration.class)
@TestPropertySource(properties={"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class ActivityTaskContractControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean AccessRepository access;
    @MockitoBean ActivityAudienceService audience;
    @MockitoBean CrmTaskService tasks;
    private final Actor actor=new Actor(5,"EMP_5","all",Set.of(),Set.of("crm.task.read","crm.task.write"));
    @Test void audienceIsSourcePagedAndCarriesTypedVersionedEvidence() throws Exception {
        when(access.resolve("https://issuer.example","subject")).thenReturn(actor);
        Instant from=Instant.parse("2030-01-01T00:00:00Z");
        var item=new ActivityAudienceDtos.Item("lead:LEAD_1",new CrmDtos.TaskSourceRef("lead","LEAD_1",3L),
            "CUS_1","家庭甲","STU_1","孩子甲","数学","2030春","EMP_5",from,false);
        when(audience.list(actor,"ACT_1",new ActivityAudienceDtos.Query("lead",null,from,null,"without_evidence",2,10)))
            .thenReturn(new ActivityAudienceDtos.Page(List.of(item),11,2,10,"source","ACT_1"));
        mvc.perform(get("/api/v2/crm/activities/ACT_1/audience")
            .param("sourceType","lead").param("fromAt",from.toString()).param("taskState","without_evidence")
            .param("page","2").param("size","10")
            .with(jwt().jwt(token->token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.unit").value("source"))
            .andExpect(jsonPath("$.data.items[0].source.version").value(3))
            .andExpect(jsonPath("$.data.items[0].studentId").value("STU_1"))
            .andExpect(jsonPath("$.data.items[0].phone").doesNotExist());
    }
    @Test void taskAndBatchDrilldownPassTheSameActivityAndBatchFilters() throws Exception {
        when(access.resolve("https://issuer.example","subject")).thenReturn(actor);
        when(tasks.list(actor,new CrmDtos.TaskQuery("pending","marketing","BATCH_1","ACT_1",2,10)))
            .thenReturn(new CrmDtos.TaskPage(List.of(),0,2,10));
        when(tasks.listBatches(actor,new CrmDtos.TaskBatchQuery(null,"marketing","ACT_1",1,20)))
            .thenReturn(new CrmDtos.TaskBatchPage(List.of(),0,1,20));
        mvc.perform(get("/api/v2/crm/tasks?status=pending&taskType=marketing&batchId=BATCH_1&activityId=ACT_1&page=2&size=10")
            .with(jwt().jwt(token->token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com")).andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2));
        mvc.perform(get("/api/v2/crm/tasks/batches?taskType=marketing&activityId=ACT_1&page=1&size=20")
            .with(jwt().jwt(token->token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com")).andExpect(status().isOk()).andExpect(jsonPath("$.data.size").value(20));
        verify(tasks).list(actor,new CrmDtos.TaskQuery("pending","marketing","BATCH_1","ACT_1",2,10));
        verify(tasks).listBatches(actor,new CrmDtos.TaskBatchQuery(null,"marketing","ACT_1",1,20));
    }
    @Test void invalidSourceReferenceFailsValidationBeforeBatchPreview() throws Exception {
        when(access.resolve("https://issuer.example","subject")).thenReturn(actor);
        mvc.perform(post("/api/v2/crm/tasks/batches/preview")
            .with(jwt().jwt(token->token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com").contentType("application/json").content("""
            {"title":"活动任务","taskType":"marketing","activityId":"ACT_1","ownerIds":["EMP_5"],
             "strategy":"even","dueAt":"2030-01-01T00:00:00Z","targets":[{"customerId":"CUS_1",
             "subject":"数学","term":"2030春","sources":[{"type":"fake","id":"L1","version":0}]}]}
            """))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(tasks);
    }
}
