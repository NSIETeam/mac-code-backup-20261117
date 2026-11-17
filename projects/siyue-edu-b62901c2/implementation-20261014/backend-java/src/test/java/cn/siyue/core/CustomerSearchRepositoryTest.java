package cn.siyue.core;

import cn.siyue.core.CustomerSearchDtos.Query;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerSearchRepositoryTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PiiCipher cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final CustomerSearchRepository repository=new CustomerSearchRepository(jdbc,cipher);
    private Actor actor(String... grants) { return new Actor(7,"staff-7","own",Set.of(),Set.of(grants)); }
    private Query query(String type,String value) { return new Query(type,value,1,20); }
    @Test void sourceCodesUseOwnResourceScopeAndExactBoundValue() {
        var opportunity=repository.filter(actor("crm.opportunity.read"),query("opportunity_id","OPP_%'"));
        assertTrue(opportunity.sql().contains("o.customer_id=c.id")); assertTrue(opportunity.sql().contains("o.owner_id=?"));
        assertEquals(List.of("OPP_%'",7L),opportunity.args());
        var lead=repository.filter(actor("crm.lead.read"),query("lead_id","LEAD_Exact"));
        assertTrue(lead.sql().contains("l.owner_id = ?")); assertEquals(List.of("LEAD_Exact",7L),lead.args());
        assertFalse(opportunity.sql().contains("OPP_%'"));
    }
    @Test void prospectsRequireIntakeAndEffectiveFamilyAssociation() {
        var intake=repository.filter(actor("crm.opportunity.intake"),query("student_id","STU_01"));
        assertTrue(intake.sql().contains("link.customer_id=c.id")); assertTrue(intake.sql().contains("link.deleted=0 AND s.deleted=0"));
        assertFalse(intake.sql().contains("core_effective_enrollment"));
        var academic=repository.filter(actor("student.read"),query("student_id","STU_01"));
        assertTrue(academic.sql().contains("core_effective_enrollment")); assertTrue(academic.sql().contains("e.status = 'active'"));
    }
    @Test void incompleteAuthorizedChildNameIndexesFailExplicitly() {
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(true);
        var error=assertThrows(BusinessException.class,()->repository.filter(actor("crm.opportunity.intake"),query("name","合成孩子")));
        assertEquals(ErrorCode.SEARCH_INDEX_NOT_READY,error.code());
        verify(jdbc).queryForObject(contains("c.deleted=0"),eq(Boolean.class),any(Object[].class));
    }
    @Test void readyNameSearchUsesCustomerAndStudentVersionedIndexesWithoutDecryptingRows() {
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(false);
        var result=repository.filter(actor("crm.opportunity.intake"),query("name"," 合成   孩子 "));
        assertTrue(result.sql().contains("core_customer_name_index")); assertTrue(result.sql().contains("core_student_name_index"));
        assertTrue(result.sql().contains("ni.source_version=s.version")); assertFalse(result.sql().contains("name_cipher"));
        assertEquals(4,result.args().size());
    }
    @Test void customerOnlyNameSearchDoesNotInspectUnownedStudentCoverage() {
        var result=repository.filter(actor(),query("name","家庭名称"));
        assertTrue(result.sql().contains("core_customer_name_index")); assertFalse(result.sql().contains("core_student"));
        verifyNoInteractions(jdbc);
    }
    @Test void existingCustomerNameNormalizationOrderRemainsCompatible() {
        String value="家庭　　名字";
        var result=repository.filter(actor(),query("name",value));
        assertArrayEquals(cipher.blindIndexes("customer_name",value.trim().replaceAll("\\s+"," ")).get("test"),(byte[])result.args().get(1));
        verifyNoInteractions(jdbc);
    }
    @Test void nameNormalizationAndRotationProduceAllCurrentKeyPredicates() {
        String key=Base64.getEncoder().encodeToString(new byte[32]);
        var rotated=new PiiCipher(Map.of("old",key,"new",key),"new");
        var names=new StudentNameIndexRepository(jdbc,rotated); var missing=names.missing("s");
        assertEquals(2,missing.args().size()); assertTrue(missing.sql().contains("ni.source_version=s.version"));
        assertEquals("a b",StudentNameIndexRepository.normalize(" Ａ　　B "));
        names.replace("STU_1","合成孩子");
        verify(jdbc,times(2)).update(contains("ON DUPLICATE KEY UPDATE"),anyString(),any(byte[].class),eq("STU_1"));
    }
}
