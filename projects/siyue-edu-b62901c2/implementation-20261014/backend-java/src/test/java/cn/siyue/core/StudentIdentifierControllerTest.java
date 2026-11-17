package cn.siyue.core;

import cn.siyue.core.StudentIdentifierDtos.*;
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

@WebMvcTest(StudentIdentifierController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties={"siyue.web-origin=https://crm.example.com","spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example","spring.security.oauth2.resourceserver.jwt.audiences=siyue-core","spring.jackson.deserialization.fail-on-unknown-properties=true"})
class StudentIdentifierControllerTest {
    @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean AccessRepository access;@MockitoBean StudentIdentifierService service;
    final Actor actor=new Actor(1,"EMP_1","own",Set.of(),Set.of("crm.customer.read","crm.customer.identity.write"));
    final Target target=new Target("CUS_1","STU_1");final String base="/api/v2/crm/customers/CUS_1/students/STU_1/identifiers",key="student_identifier_key_1";
    org.springframework.test.web.servlet.request.RequestPostProcessor token(){when(access.resolve("https://issuer.example","subject")).thenReturn(actor);return jwt().jwt(j->j.issuer("https://issuer.example").subject("subject"));}
    @Test void listBindsTargetAndPaginationWithExactNumberSpelling()throws Exception{
        var row=new Identifier("SID_1","STU_1","School-A","001AbC",true,1,Instant.EPOCH,null);when(service.list(actor,target,new Query("all",2,10))).thenReturn(new Page(List.of(row),11,2,10,3));
        mvc.perform(get(base+"?status=all&page=2&size=10").with(token())).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].value").value("001AbC")).andExpect(jsonPath("$.data.items[0].namespace").value("School-A")).andExpect(jsonPath("$.data.studentVersion").value(3));verify(service).list(actor,target,new Query("all",2,10));
    }
    @Test void createAndDeactivateCarryExactPathVersionAndOriginalKey()throws Exception{
        mvc.perform(post(base).with(token()).header("Idempotency-Key",key).contentType("application/json").content("{\"namespace\":\"School-A\",\"value\":\"001AbC\",\"expectedStudentVersion\":3}")).andExpect(status().isOk());verify(service).create(actor,target,key,new Create("School-A","001AbC",3));
        mvc.perform(put(base+"/SID_1/deactivate").with(token()).header("Idempotency-Key",key).contentType("application/json").content("{\"expectedVersion\":1,\"reason\":\"备案纠错\"}")).andExpect(status().isOk());verify(service).deactivate(actor,target,"SID_1",key,new Deactivate(1,"备案纠错"));
    }
    @Test void unknownFieldsZeroVersionAndUnboundedPagingFailBeforeService()throws Exception{
        mvc.perform(post(base).with(token()).header("Idempotency-Key",key).contentType("application/json").content("{\"namespace\":\"A\",\"value\":\"001\",\"expectedStudentVersion\":0}")).andExpect(status().isBadRequest());
        mvc.perform(post(base).with(token()).header("Idempotency-Key",key).contentType("application/json").content("{\"namespace\":\"A\",\"value\":\"001\",\"expectedStudentVersion\":1,\"studentId\":\"STU_OTHER\"}")).andExpect(status().isBadRequest());
        mvc.perform(get(base+"?size=101").with(token())).andExpect(status().isBadRequest());mvc.perform(get(base+"?status=bad").with(token())).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void anonymousAndCrossOriginWritesNeverReachService()throws Exception{
        mvc.perform(get(base)).andExpect(status().isUnauthorized());mvc.perform(post(base).with(token()).header("Origin","https://other.example").header("Idempotency-Key",key).contentType("application/json").content("{\"namespace\":\"A\",\"value\":\"001\",\"expectedStudentVersion\":1}")).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void missingKeyAndImmutableFieldRewriteAreRejected()throws Exception{
        mvc.perform(post(base).with(token()).contentType("application/json").content("{\"namespace\":\"A\",\"value\":\"001\",\"expectedStudentVersion\":1}")).andExpect(status().isBadRequest());
        mvc.perform(put(base+"/SID_1/deactivate").with(token()).header("Idempotency-Key",key).contentType("application/json").content("{\"expectedVersion\":1,\"reason\":\"纠错\",\"value\":\"002\"}")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
}
