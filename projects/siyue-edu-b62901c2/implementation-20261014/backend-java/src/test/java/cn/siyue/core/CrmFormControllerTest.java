package cn.siyue.core;

import java.util.List;
import java.util.Map;
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

@WebMvcTest(CrmFormController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties={"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class CrmFormControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private CrmFormService service;
    @MockitoBean private PublicCrmFormService publicForms;
    @MockitoBean private CrmFormSubmissionReviewService submissionReviews;
    private Actor actor(){return new Actor(7,"staff-7","campus",Set.of(2L),Set.of("crm.form.write","crm.activity.write"));}

    @Test void createsDraftWithValidatedFieldDefinition()throws Exception{
        when(access.resolve("https://issuer.example","subject")).thenReturn(actor());
        var form=new CrmDtos.MarketingForm("FORM_1","campus-2","staff-7","满意度",null,"survey",false,"draft",0,
            List.of(new CrmDtos.FormFieldInput("rating","评价","single",null,true,List.of("好","一般"))),Map.of(),1);
        var input=new CrmDtos.MarketingFormInput("campus-2","满意度",null,"survey",false,
            List.of(new CrmDtos.FormFieldInput("rating","评价","single",null,true,List.of("好","一般"))),Map.of());
        when(service.create(actor(),"form-create-key-12345",input)).thenReturn(form);
        mvc.perform(post("/api/v2/crm/forms").with(jwt().jwt(token->token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com").header("Idempotency-Key","form-create-key-12345")
            .contentType("application/json").content("""
                {"campusId":"campus-2","title":"满意度","formKind":"survey","collectLead":false,
                 "fields":[{"id":"rating","title":"评价","type":"single","required":true,"options":["好","一般"]}],
                 "leadFieldMapping":{}}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.formKind").value("survey"))
            .andExpect(jsonPath("$.data.status").value("draft"));
    }

    @Test void rejectsUnknownFieldKindAtHttpBoundary()throws Exception{
        mvc.perform(post("/api/v2/crm/forms").with(jwt().jwt(token -> token.issuer("https://issuer.example"))).header("Origin","https://crm.example.com")
            .header("Idempotency-Key","form-create-key-12345").contentType("application/json")
            .content("""
                {"campusId":"campus-2","title":"表单","formKind":"lead","collectLead":false,
                 "fields":[{"id":"x","title":"未知","type":"custom"}]}
                """))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void stopRequiresValidVersionAndPassesAuthenticatedIdentity()throws Exception{
        when(access.resolve("https://issuer.example","subject")).thenReturn(actor());
        mvc.perform(post("/api/v2/crm/forms/FORM_1/unpublish")
            .with(jwt().jwt(token->token.issuer("https://issuer.example").subject("subject")))
            .header("Origin","https://crm.example.com").header("Idempotency-Key","form-stop-key-12345")
            .contentType("application/json").content("{\"expectedVersion\":2}"))
            .andExpect(status().isOk());
        verify(service).unpublish(actor(),"FORM_1","form-stop-key-12345",new CrmDtos.MarketingFormVersion(2));
        mvc.perform(post("/api/v2/crm/forms/FORM_1/unpublish").with(jwt().jwt(token -> token.issuer("https://issuer.example")))
            .header("Origin","https://crm.example.com").header("Idempotency-Key","form-stop-key-12345")
            .contentType("application/json").content("{\"expectedVersion\":0}"))
            .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(service);
    }
}
