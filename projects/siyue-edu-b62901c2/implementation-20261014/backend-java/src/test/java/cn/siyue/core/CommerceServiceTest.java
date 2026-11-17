package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceServiceTest {
    final AccessRepository access=mock(AccessRepository.class);
    final CommerceRepository repository=mock(CommerceRepository.class);
    final MutationRepository mutations=mock(MutationRepository.class);
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final CommerceService service=new CommerceService(access,repository,mutations,mapper,
        Validation.buildDefaultValidatorFactory().getValidator());
    final Actor actor=new Actor(1,"admin","all",Set.of(),Set.of("commerce.read","commerce.manage"));

    @Test void promotionTypeIsValidatedBeforePaginationQuery() {
        assertThrows(BusinessException.class,()->service.promotions(actor,1,50,"bogus"));
        verifyNoInteractions(repository);
        service.promotions(actor,2,20,"joint");
        verify(repository).promotions(actor,2,20,"joint");
        service.promotions(actor,1,50);
        verify(repository).promotions(actor,1,50,null);
    }
    @Test void readPermissionAndPageBoundsAreRequired() {
        var forbidden=new Actor(2,"staff","all",Set.of(),Set.of());
        assertThrows(BusinessException.class,()->service.products(forbidden,1,50));
        assertThrows(BusinessException.class,()->service.products(actor,0,50));
        assertThrows(BusinessException.class,()->service.courseClasses(actor,"COURSE",1,101));
        verifyNoInteractions(repository);
    }
    @Test void exactStoreReadGrantListsPublishedProductsOnly() {
        Actor storefrontReader = new Actor(3, "store-reader", "campus", Set.of(7L), Set.of("store.read"));
        var page = new CommerceDtos.ProductPage(List.of(), 0, 1, 25);
        when(repository.products(storefrontReader, 1, 25, true)).thenReturn(page);

        assertEquals(page, service.products(storefrontReader, 1, 25));
        verify(repository).products(storefrontReader, 1, 25, true);
        assertThrows(BusinessException.class, () -> service.promotions(storefrontReader, 1, 25));
        assertThrows(BusinessException.class, () -> service.courseClasses(storefrontReader, "COURSE", 1, 25));
    }
    @Test void exactProductPublishGrantReadsScopedDraftProductsAndCourseClassesOnly() {
        Actor publisher = new Actor(4, "publisher", "campus", Set.of(8L), Set.of("product.publish"));
        var products = new CommerceDtos.ProductPage(List.of(), 0, 1, 20);
        var classes = new CommerceDtos.ClassPage(List.of(), 0, 1, 20);
        when(repository.products(publisher, 1, 20, false)).thenReturn(products);
        when(repository.courseClasses(publisher, "COURSE", 1, 20)).thenReturn(classes);

        assertEquals(products, service.products(publisher, 1, 20));
        assertEquals(classes, service.courseClasses(publisher, "COURSE", 1, 20));
        verify(repository).products(publisher, 1, 20, false);
        assertThrows(BusinessException.class, () -> service.promotions(publisher, 1, 20));
        when(access.lockActive(publisher.id())).thenReturn(publisher);
        var input = new CommerceDtos.ProductInput("COURSE", "Name", List.of("CLASS"), false, null);
        assertThrows(BusinessException.class, () -> service.saveProduct(publisher, null, "product-write-key-0001", input));
        verify(repository, never()).saveProduct(any(), any(), any());
    }
    @Test void createReplayRechecksCurrentProductScope() throws Exception {
        var input=new CommerceDtos.ProductInput("COURSE","商品",List.of("CLASS"),true,null);
        var receipt=new CommerceDtos.Product("PRODUCT","COURSE","商品",List.of("CLASS"),true,1);
        when(access.lockActive(1)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("hash");
        when(mutations.replay(actor,"commerce-retry-0001","commerce.product.create","hash"))
            .thenReturn(mapper.writeValueAsString(receipt));
        doThrow(new BusinessException(ErrorCode.NOT_FOUND,"范围已撤销"))
            .when(repository).authorize(actor,"PRODUCT","product");
        assertThrows(BusinessException.class,()->service.saveProduct(actor,null,"commerce-retry-0001",input));
        verify(repository,never()).saveProduct(any(),any(),any());
        verify(mutations,never()).complete(any(),any());
    }
    @Test void duplicateClassSelectionIsRejectedBeforeMutation() {
        var input=new CommerceDtos.ProductInput("COURSE","商品",List.of("CLASS","CLASS"),true,null);
        assertThrows(BusinessException.class,()->service.saveProduct(actor,null,"commerce-retry-0002",input));
        verifyNoInteractions(access,repository,mutations);
    }
}
