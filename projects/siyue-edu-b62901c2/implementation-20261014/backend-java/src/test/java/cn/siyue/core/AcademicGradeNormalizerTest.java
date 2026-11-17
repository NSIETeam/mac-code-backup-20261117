package cn.siyue.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcademicGradeNormalizerTest {
    @Test void canonicalizesLegacyAliasesWithoutChangingUnknownLabels() {
        assertEquals("小学一年级",AcademicGradeNormalizer.normalize("1年级"));
        assertEquals("初中一年级",AcademicGradeNormalizer.normalize("七年级"));
        assertEquals("初中二年级",AcademicGradeNormalizer.normalize("初２"));
        assertEquals("高中三年级",AcademicGradeNormalizer.normalize("高三"));
        assertEquals("1",AcademicGradeNormalizer.normalize("1"));
        assertEquals("历史标签",AcademicGradeNormalizer.normalize(" 历史标签 "));
        assertEquals("高中四年级",AcademicGradeNormalizer.normalize("高中四年级"));
    }
}
