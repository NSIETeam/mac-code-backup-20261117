package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrmFormServiceTest {
    private final AccessRepository access=mock(AccessRepository.class);
    private final CrmFormRepository forms=mock(CrmFormRepository.class);
    private final MutationRepository mutations=mock(MutationRepository.class);
    private final CrmFormService service=new CrmFormService(access,forms,mutations,new ObjectMapper());
    private final Actor writer=new Actor(7,"staff-7","campus",Set.of(2L),Set.of("crm.form.write"));

    @Test void leadFormRequiresExplicitNameAndGuardianPhoneBindings(){
        var input=new CrmDtos.MarketingFormInput("campus-2","课程咨询",null,"lead",true,
            List.of(new CrmDtos.FormFieldInput("name","学生姓名","name",null,true,List.of()),
                new CrmDtos.FormFieldInput("phone","家长电话","phone",null,true,List.of())),Map.of("studentName","name"));
        assertThrows(BusinessException.class,()->service.create(writer,"form-create-key-12345",input));
        verifyNoInteractions(access,forms,mutations);
    }

    @Test void questionnaireCannotCollectSalesLeads(){
        var input=new CrmDtos.MarketingFormInput("campus-2","满意度问卷",null,"survey",true,
            List.of(new CrmDtos.FormFieldInput("q1","评价","single",null,true,List.of("好","一般"))),Map.of());
        assertThrows(BusinessException.class,()->service.create(writer,"form-create-key-12345",input));
        verifyNoInteractions(access,forms,mutations);
    }

    @Test void leadDraftCreationIsScopedAuditedAndIdempotent()throws Exception{
        when(access.lockActive(7)).thenReturn(writer);when(mutations.hash(anyString())).thenReturn("form-hash");
        var input=new CrmDtos.MarketingFormInput("campus-2","课程咨询",null,"lead",true,
            List.of(new CrmDtos.FormFieldInput("student","学生姓名","name",null,true,List.of()),
                new CrmDtos.FormFieldInput("guardian","监护人电话","phone",null,true,List.of())),
            Map.of("studentName","student","guardianPhone","guardian"));
        var draft=new CrmDtos.MarketingForm("FORM_1","campus-2","staff-7","课程咨询",null,"lead",true,"draft",0,input.fields(),input.leadFieldMapping(),1);
        when(forms.create(writer,input)).thenReturn(draft);
        assertEquals(draft,service.create(writer,"form-create-key-12345",input));
        verify(forms).authorizeCampus(writer,"campus-2");
        verify(mutations).complete(eq(writer),argThat(write->write.action().equals("crm.form.create")&&write.resourceId().equals("FORM_1")));
        when(mutations.replay(writer,"form-create-key-12345","crm.form.create","form-hash"))
            .thenReturn(new ObjectMapper().writeValueAsString(draft));
        assertEquals(draft,service.create(writer,"form-create-key-12345",input));
        verify(forms,times(1)).create(writer,input);
    }
    @Test void stoppingFormRechecksPermissionScopeAndReplaysWithoutAnotherWrite()throws Exception{
        Actor publisher=new Actor(7,"staff-7","campus",Set.of(2L),Set.of("crm.form.publish"));
        when(access.lockActive(7)).thenReturn(publisher);
        when(mutations.hash(anyString())).thenReturn("stop-hash");
        var closed=new CrmDtos.MarketingForm("FORM_1","campus-2","staff-7","问卷",null,
            "survey",false,"closed",1,List.of(),Map.of(),3);
        when(forms.unpublish(publisher,"FORM_1",2)).thenReturn(closed);
        var version=new CrmDtos.MarketingFormVersion(2);
        assertEquals(closed,service.unpublish(publisher,"FORM_1","form-stop-key-12345",version));
        verify(forms).authorizeForm(publisher,"FORM_1");
        verify(mutations).complete(eq(publisher),argThat(write->write.action().equals("crm.form.unpublish")));
        when(mutations.replay(publisher,"form-stop-key-12345","crm.form.unpublish","stop-hash"))
            .thenReturn(new ObjectMapper().writeValueAsString(closed));
        assertEquals(closed,service.unpublish(publisher,"FORM_1","form-stop-key-12345",version));
        verify(forms,times(1)).unpublish(publisher,"FORM_1",2);
    }

    @Test void stopDoesNotUseStalePublisherPermission(){
        Actor publisher=new Actor(7,"staff-7","campus",Set.of(2L),Set.of("crm.form.publish"));
        when(access.lockActive(7)).thenReturn(writer);
        assertThrows(BusinessException.class,()->service.unpublish(publisher,"FORM_1",
            "form-stop-key-12345",new CrmDtos.MarketingFormVersion(2)));
        verifyNoInteractions(forms,mutations);
    }
}
