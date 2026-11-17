package cn.siyue.core;

import cn.siyue.core.CrmTaskReadDtos.Assignee;
import cn.siyue.core.CrmTaskReadDtos.AssigneePage;
import cn.siyue.core.CrmTaskReadDtos.AssigneeQuery;
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

@WebMvcTest(CrmTaskReadController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class CrmTaskReadControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private CrmTaskReadService service;
    private final Actor actor = new Actor(8, "EMP_8", "own", Set.of(), Set.of("crm.task.write", "crm.task.read"));

    @Test void bindsBusinessDirectoryQueryAndDoesNotExposeAccountMetadata() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor);
        var query = new AssigneeQuery("CAMP_1", "service", "EMP", 2, 10);
        when(service.assignees(actor, query)).thenReturn(new AssigneePage(
            List.of(new Assignee("EMP_8", "EMP_8", 3)), 11, 2, 10, "CAMP_1", "service",
            Instant.EPOCH, "current_actor_scope"));
        mvc.perform(get("/api/v2/crm/tasks/assignees?campusId=CAMP_1&taskType=service&query=EMP&page=2&size=10")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].principalId").value("EMP_8"))
            .andExpect(jsonPath("$.data.items[0].pendingCount").value(3))
            .andExpect(jsonPath("$.data.items[0].permissionCodes").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].oidcSubject").doesNotExist())
            .andExpect(jsonPath("$.data.workloadScope").value("current_actor_scope"));
        verify(service).assignees(actor, query);
    }
    @Test void missingQueryUsesBoundedDefaultsAndMalformedPageIsRejected() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor);
        mvc.perform(get("/api/v2/crm/tasks/assignees")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject"))))
            .andExpect(status().isOk());
        verify(service).assignees(actor, new AssigneeQuery(null, "marketing", null, 1, 20));
        mvc.perform(get("/api/v2/crm/tasks/assignees?page=bad")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject"))))
            .andExpect(status().isBadRequest());
    }
    @Test void taskContextAndMatchingOpportunitiesUseSpecificAuthorizedRoutes() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor);
        when(service.context(actor, "TASK_1")).thenReturn(new CrmTaskReadDtos.Context(null,
            new CrmTaskReadDtos.Customer("CUS_1", "合成家庭"), new CrmTaskReadDtos.Student("STU_1", "合成甲", "三年级"),
            List.of(new CrmDtos.CustomerContact("CONTACT_1", "guardian", "phone", "138****0000", true)),
            null, null, "masked_guardian_contacts"));
        mvc.perform(get("/api/v2/crm/tasks/TASK_1/context")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.student.id").value("STU_1"))
            .andExpect(jsonPath("$.data.contacts[0].valueMasked").value("138****0000"))
            .andExpect(jsonPath("$.data.students").doesNotExist())
            .andExpect(jsonPath("$.data.orders").doesNotExist());
        mvc.perform(get("/api/v2/crm/tasks/TASK_1/opportunities?page=2&size=10")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject"))))
            .andExpect(status().isOk());
        verify(service).context(actor, "TASK_1"); verify(service).opportunities(actor, "TASK_1", 2, 10);
    }
    @Test void unauthenticatedDirectoryCannotReadStaffData() throws Exception {
        mvc.perform(get("/api/v2/crm/tasks/assignees")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
