package cn.siyue.core;

import cn.siyue.core.CommerceDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductPublicationServiceTest {
    final AccessRepository access=mock(AccessRepository.class);
    final AcademicCatalogRepository catalog=mock(AcademicCatalogRepository.class);
    final CommerceRepository commerce=mock(CommerceRepository.class);
    final MutationRepository mutations=mock(MutationRepository.class);
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final ProductPublicationService service=new ProductPublicationService(access,catalog,commerce,mutations,mapper);
    final Actor actor=new Actor(1,"publisher","all",Set.of(),Set.of("product.publish"));
    final PublicationInput input=new PublicationInput(List.of("CLASS"),2L);
    final AcademicCatalogRepository.CourseStatusRow course=new AcademicCatalogRepository.CourseStatusRow(1,"COURSE",null,true,2);
    final Product result=new Product("PRODUCT","COURSE","课程",List.of("CLASS"),true,1);
    final String key="product-publish-0001";
    void setup() {
        when(access.lockActive(1)).thenReturn(actor);
        when(catalog.lockCourseStatus(actor,"COURSE")).thenReturn(course);
        when(mutations.hash(anyString())).thenReturn("hash");
    }
    @Test void exactLegacyGrantPublishesAndAuditsWithoutManageGrant() throws Exception {
        setup();when(commerce.publishCourse(actor,course,input,true)).thenReturn(result);
        assertEquals(result,service.publish(actor,"COURSE",key,input,true));
        verify(mutations).complete(eq(actor),any());
    }
    @Test void revokedGrantFailsBeforeScopeOrMutation() {
        when(access.lockActive(1)).thenReturn(new Actor(1,"publisher","all",Set.of(),Set.of("commerce.read")));
        assertThrows(BusinessException.class,()->service.publish(actor,"COURSE",key,input,true));
        verifyNoInteractions(catalog,commerce,mutations);
    }
    @Test void staleCourseVersionCannotWrite() {
        setup();assertThrows(BusinessException.class,()->service.publish(actor,"COURSE",key,new PublicationInput(null,1L),true));
        verifyNoInteractions(commerce);verify(mutations,never()).complete(any(),any());
    }
    @Test void identicalRetryRechecksProductScope() throws Exception {
        setup();when(mutations.replay(actor,key,"product.publish","hash")).thenReturn(mapper.writeValueAsString(result));
        assertEquals(result,service.publish(actor,"COURSE",key,input,true));
        verify(commerce).authorize(actor,"PRODUCT","product");
        verify(commerce,never()).publishCourse(any(),any(),any(),anyBoolean());
    }
    @Test void scopeRevokedRetryDenied() throws Exception {
        setup();when(mutations.replay(actor,key,"product.publish","hash")).thenReturn(mapper.writeValueAsString(result));
        doThrow(new BusinessException(ErrorCode.NOT_FOUND,"范围撤销")).when(commerce).authorize(actor,"PRODUCT","product");
        assertThrows(BusinessException.class,()->service.publish(actor,"COURSE",key,input,true));
        verify(mutations,never()).complete(any(),any());
    }
    @Test void duplicateClassesRejectedWithoutWrites() {
        assertThrows(BusinessException.class,()->service.publish(actor,"COURSE",key,new PublicationInput(List.of("CLASS","CLASS"),2L),true));
        verifyNoInteractions(access,catalog,commerce,mutations);
    }
    @Test void unpublishUsesSeparateIdempotencyAction() throws Exception {
        setup();when(commerce.publishCourse(actor,course,input,false)).thenReturn(result);
        service.publish(actor,"COURSE",key,input,false);
        verify(mutations).replay(actor,key,"product.unpublish","hash");
    }
}
