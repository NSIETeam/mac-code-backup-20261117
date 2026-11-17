package cn.siyue.core;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacySavedViewQueryTest {
    @Test void importedViewRequiresExactResourceAndSupportedLegacySemantics() {
        Actor all = new Actor(11, "staff", "all", Set.of(), Set.of("crm.lead.read", "crm.lead.assign"));
        LegacySavedViewQuery query = new LegacySavedViewQuery("leads", "team", "all", "王小明",
            "updatedAt:desc", "createdAt");
        assertDoesNotThrow(() -> query.authorize(all, "leads"));
        assertThrows(BusinessException.class, () -> query.authorize(all, "customers"));
        assertThrows(BusinessException.class, () -> new LegacySavedViewQuery("leads", "team", "today",
            "王小明", "updatedAt:desc", "createdAt").authorize(all, "leads"));
        assertThrows(BusinessException.class, () -> new LegacySavedViewQuery("leads", "team", "all",
            "王小明", "createdAt:asc", "createdAt").authorize(all, "leads"));
    }

    @Test void teamViewRequiresExplicitTeamAuthorityWithinDeclaredScope() {
        Actor own = new Actor(12, "staff", "own", Set.of(), Set.of("crm.lead.read", "crm.lead.assign"));
        Actor allWithoutTeamGrant = new Actor(13, "staff", "all", Set.of(), Set.of("crm.lead.read"));
        LegacySavedViewQuery team = new LegacySavedViewQuery("customers", "team", "all", "customer",
            "updatedAt:desc", "createdAt");
        assertThrows(BusinessException.class, () -> team.authorize(own, "customers"));
        assertThrows(BusinessException.class, () -> team.authorize(allWithoutTeamGrant, "customers"));
    }

    @Test void savedSearchPatternNormalizesAndEscapesLikeMetacharacters() {
        LegacySavedViewQuery query = new LegacySavedViewQuery("customers", "mine", "all", "  Ａ!%_  ",
            "updatedAt:desc", "createdAt");
        assertEquals("%a!!!%!_%", query.searchPattern());
        assertTrue(query.present());
        assertFalse(new LegacySavedViewQuery(null, null, null, null, null, null).present());
        assertFalse(new LegacySavedViewQuery("", "", "", "", "", "").present());
    }

    @Test void searchNgramsRetainSubstringAndUnicodeCompatibilityWithoutPlaintextCandidates() {
        PiiCipher cipher = new PiiCipher(Map.of("old", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), "old");
        Map<String, String> encoded = SavedViewSearchNgrams.encode(cipher, "王小明 课程咨询");
        assertTrue(encoded.containsKey("old"));
        assertFalse(encoded.get("old").contains("小明"));
        assertTrue(SavedViewSearchNgrams.contains("客户Ａ档案", "A"));
        assertTrue(SavedViewSearchNgrams.contains("王小明 课程咨询", "小明 课"));
    }
}
