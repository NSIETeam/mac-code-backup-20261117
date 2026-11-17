package cn.siyue.core;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AttendanceCorrectionController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties={"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class AttendanceCorrectionControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private AttendanceCorrectionService service;
    private static final String URL="/api/v2/academic/sessions/session/students/student/attendance/corrections";
    @Test void correctionRequiresAuthenticationAndValidVersionReason() throws Exception {
        mvc.perform(post(URL).contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        for(String body:new String[]{
                "{\"classId\":\"class\",\"expectedVersion\":0,\"status\":\"absent\",\"reason\":\"核对\"}",
                "{\"classId\":\"class\",\"expectedVersion\":1,\"status\":\"absent\",\"reason\":\" \"}",
                "{\"classId\":\"class\",\"expectedVersion\":1,\"status\":\"bad\",\"reason\":\"核对\"}"}) {
            mvc.perform(post(URL).with(jwt().jwt(token -> token.issuer("https://issuer.example"))).header("Idempotency-Key","correction-http-123456")
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void routesMappedActorAndTypedReceipt() throws Exception {
        Actor actor=new Actor(7,"teacher","assigned",Set.of(),Set.of("attendance.manage"));
        when(access.resolve("https://issuer.example","subject")).thenReturn(actor);
        var input=new AttendanceCorrectionDtos.Input("class",1,"absent",null,"核对");
        var target=new AttendanceCorrectionDtos.Target("student","session");
        var result=new AttendanceCorrectionDtos.Receipt("attendance","correction","class","session","student",
            "absent",null,2,"teacher",Instant.parse("2026-10-14T01:00:00Z"),"0.00");
        when(service.correct(actor,target,"correction-http-123456",input)).thenReturn(result);
        mvc.perform(post(URL).with(jwt().jwt(t->t.issuer("https://issuer.example").subject("subject")))
            .header("Idempotency-Key","correction-http-123456").contentType("application/json")
            .content("{\"classId\":\"class\",\"expectedVersion\":1,\"status\":\"absent\",\"reason\":\"核对\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2))
            .andExpect(jsonPath("$.data.consumptionAmount").value("0.00"))
            .andExpect(jsonPath("$.data.id").value("attendance"));
        verify(service).correct(actor,target,"correction-http-123456",input);
    }
}
