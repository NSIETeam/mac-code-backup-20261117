package cn.siyue.core;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PublicCrmFormController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties={"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class PublicCrmFormControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private PublicCrmFormService service;
    @MockitoBean private PublicStoreVisitor visitor;

    @Test void publishedFormReadAndSubmissionDoNotRequireEmployeeJwt()throws Exception{
        var view=new CrmDtos.PublicFormView("FORM_1","课程咨询",null,"lead",true,1,null,
            List.of(new CrmDtos.FormFieldInput("name","学生姓名","name",null,true,List.of())),"random-submit-key-12345",java.util.Map.of(),null);
        when(service.publicForm("FORM_1",null,null)).thenReturn(view);
        mvc.perform(get("/api/v2/public/forms/FORM_1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("课程咨询"))
            .andExpect(jsonPath("$.data.collectLead").value(true))
            .andExpect(jsonPath("$.data.submissionKey").value("random-submit-key-12345"));
        var receipt=new CrmDtos.PublicFormReceipt("random-submit-key-12345",true,"pending");
        when(service.submitFromVisitor(eq("FORM_1"),eq("random-submit-key-12345"),any(),any())).thenReturn(receipt);
        mvc.perform(post("/api/v2/public/forms/FORM_1/submissions")
            .header("Origin","https://crm.example.com").header("Idempotency-Key","random-submit-key-12345")
            .contentType("application/json").content("""
                {"submissionId":"random-submit-key-12345","answersById":{"name":"张同学"}}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.received").value(true))
            .andExpect(jsonPath("$.data.status").value("pending"));
    }
    @Test void promotionAliasesAreCanonicalAndAmbiguousOrIncompleteLinksAreRejected()throws Exception {
        mvc.perform(get("/api/v2/public/forms/FORM_1").param("campaign","ACT_1").param("promotion","LINK_1"))
            .andExpect(status().isOk());
        verify(service).publicForm("FORM_1","ACT_1","LINK_1");
        mvc.perform(get("/api/v2/public/forms/FORM_1").param("activityId","ACT_1").param("campaign","ACT_2"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/public/forms/FORM_1").param("promotionLinkId","LINK_1"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/public/forms/FORM_1").param("activityId","ACT_1","ACT_1"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v2/public/forms/FORM_1/submissions")
            .header("Origin","https://crm.example.com").header("Idempotency-Key","promotion-submit-12345")
            .contentType("application/json").content("""
                {"submissionId":"promotion-submit-12345","answersById":{},"activityId":"ACT_1","promotionLinkId":"LINK_1"}
                """)) .andExpect(status().isOk());
        verify(service).submitFromVisitor(eq("FORM_1"),eq("promotion-submit-12345"),any(),
            argThat(input->"ACT_1".equals(input.activityId())&&"LINK_1".equals(input.promotionLinkId())));
    }

    @Test void addingOptionalPromotionDoesNotChangeExistingJavaRetryCanonicalBytes()throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var original=new CrmDtos.PublicFormSubmissionInput("retry-canonical-12345",Map.of("name","synthetic"),null);
        org.junit.jupiter.api.Assertions.assertEquals("{\"submissionId\":\"retry-canonical-12345\",\"answersById\":{\"name\":\"synthetic\"},\"activityId\":null,\"trialBooking\":null}",mapper.writeValueAsString(original));
        var promoted=new CrmDtos.PublicFormSubmissionInput(original.submissionId(),original.answersById(),"ACT_1",null,"LINK_1");
        org.junit.jupiter.api.Assertions.assertTrue(mapper.writeValueAsString(promoted).contains("\"promotionLinkId\":\"LINK_1\""));
    }

}
