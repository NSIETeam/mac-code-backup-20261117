package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpportunityConfigRepositoryTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final OpportunityConfigRepository repository=spy(new OpportunityConfigRepository(jdbc,mock(PiiCipher.class)));
    @Test void projectRequiresItsConfiguredDepartmentAndCannotUseAnotherCampus() {
        doReturn(ref(new Seed(1,"department",null,"required",true,2L))).when(repository).find("department");
        doReturn(ref(new Seed(2,"project",99L,null,true,2L))).when(repository).find("project");
        assertThrows(BusinessException.class,()->repository.identity(new Identity("department","project"),2L,false));
        assertThrows(BusinessException.class,()->repository.identity(new Identity("department",null),2L,false));
        assertThrows(BusinessException.class,()->repository.identity(new Identity("department","project"),3L,false));
        doReturn(ref(new Seed(2,"project",1L,null,true,2L))).when(repository).find("project");
        assertEquals("project",repository.identity(new Identity("department","project"),2L,false).view().projectId());
    }
    @Test void notUsedIsConfiguredMeaningAndNotAnUnknownProjectWildcard() {
        doReturn(ref(new Seed(1,"department",null,"not_used",true,null))).when(repository).find("department");
        doReturn(ref(new Seed(2,"project",1L,null,true,null))).when(repository).find("project");
        assertNull(repository.identity(new Identity("department",null),null,false).project());
        assertThrows(BusinessException.class,()->repository.identity(new Identity("department","project"),null,false));
        assertThrows(BusinessException.class,()->repository.identity(null,null,false));
    }
    @Test void inactiveProfileValueCanBeRetainedButCannotBeNewlySelected() {
        doReturn(ref(new Seed(3,"grade",null,null,false,2L))).when(repository).find("grade");
        Profile desired=new Profile("grade",null,null,null,null,null);
        assertThrows(BusinessException.class,()->repository.profile(desired,2L,null));
        ProfileView old=new ProfileView("grade",null,null,null,null,null,"old name",null,null,null,null,null);
        assertEquals("grade",repository.profile(desired,2L,old).gradeId());
    }
    @Test void fiscalYearAndQuarterRemainSeparateAndParentMustMatch() {
        doReturn(ref(new Seed(4,"fiscal_year",null,null,true,null))).when(repository).find("year");
        doReturn(ref(new Seed(5,"quarter",4L,null,true,null))).when(repository).find("quarter");
        var good=new Profile(null,null,null,null,"year","quarter");
        assertEquals("fiscal_year",repository.profile(good,null,null).fiscalYearId());
        assertThrows(BusinessException.class,()->repository.profile(new Profile(null,null,null,null,null,"quarter"),null,null));
    }
    @Test void ownScopeCannotAcquireConfigurationAdministrationByPermissionAlone() {
        Actor actor=new Actor(1,"P1","own",Set.of(),Set.of("crm.opportunity.config.manage"));
        assertThrows(BusinessException.class,()->repository.list(actor,new OptionQuery(null,"all",null,1,20)));
        assertThrows(BusinessException.class,()->repository.campus(actor,null)); verifyNoInteractions(jdbc);
    }
    @Test void configuredJudgmentCannotHaveWonEffect() {
        Actor actor=new Actor(1,"P1","all",Set.of(),Set.of("crm.opportunity.config.manage"));
        when(jdbc.queryForObject(anyString(),eq(Long.class))).thenReturn(1L);
        assertThrows(BusinessException.class,()->repository.create(actor,new OptionCreate("judgment","WIN","人工成交",null,null,null,"won")));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    private record Seed(long id,String kind,Long parent,String mode,boolean active,Long campus) { }
    private OpportunityConfigRepository.Ref ref(Seed seed) {
        long id=seed.id(); String kind=seed.kind(),mode=seed.mode(); Long parent=seed.parent(),campus=seed.campus(); boolean active=seed.active();
        return new OpportunityConfigRepository.Ref(id,campus,parent,new Option(kind,kind,"code","label",parent==null?null:"parent",
            campus==null?null:"CAMP",mode,null,active,1));
    }
}
