package cn.siyue.core;

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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StudentTransferController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties={"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class StudentTransferControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private StudentTransferService service;
    private static final String URL="/api/v2/academic/enrollments/batch-transfer";
    @Test void validatesAuthenticationAndNestedExpectedVersion() throws Exception {
        mvc.perform(post(URL).contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(URL).with(jwt().jwt(token -> token.issuer("https://issuer.example"))).header("Idempotency-Key","transfer-http-123456")
            .contentType("application/json").content("{\"items\":[{\"enrollmentId\":\"enrollment\",\"expectedVersion\":0}],\"targetClassId\":\"target\",\"reason\":\"核对\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/academic/students/student/transfer-options?size=101").with(jwt().jwt(token -> token.issuer("https://issuer.example"))))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void resolvesActorAndReturnsPermanentPlacementReceipt() throws Exception {
        Actor actor=new Actor(7,"teacher","assigned",Set.of(),Set.of("enrollment.manage"));
        when(access.resolve("https://issuer.example","subject")).thenReturn(actor);
        var input=new StudentTransferDtos.Input(List.of(new StudentTransferDtos.Item("enrollment",1)),"target","核对");
        var receipt=new StudentTransferDtos.Receipt(List.of(new StudentTransferDtos.Result("enrollment","student","source","target",2,"transfer")),1);
        when(service.transfer(actor,"transfer-http-123456",input)).thenReturn(receipt);
        mvc.perform(post(URL).with(jwt().jwt(t->t.issuer("https://issuer.example").subject("subject")))
            .header("Idempotency-Key","transfer-http-123456").contentType("application/json")
            .content("{\"items\":[{\"enrollmentId\":\"enrollment\",\"expectedVersion\":1}],\"targetClassId\":\"target\",\"reason\":\"核对\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].version").value(2))
            .andExpect(jsonPath("$.data.items[0].fromClassId").value("source"));
        verify(service).transfer(actor,"transfer-http-123456",input);
    }
}
