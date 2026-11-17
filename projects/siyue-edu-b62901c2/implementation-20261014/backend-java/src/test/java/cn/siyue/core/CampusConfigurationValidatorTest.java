package cn.siyue.core;

import cn.siyue.core.CampusConfigurationDtos.Campus;
import cn.siyue.core.CampusConfigurationDtos.CampusFields;
import cn.siyue.core.CampusConfigurationDtos.Room;
import cn.siyue.core.CampusConfigurationDtos.RoomInput;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CampusConfigurationValidatorTest {
    @Test void roomsKeepStableIdsAndFillBlankNamesWithoutCollision() {
        Campus old = new Campus("C1", "合成校区", "旧地址", "", true, 3,
            List.of(new Room("R1", "教室1", 20, 2)), List.of("数学"), List.of("小学一年级"));
        CampusFields patch = new CampusFields(null, null, null,
            List.of(new RoomInput("R1", null, 25, null), new RoomInput(null, "", 10, null)),
            null, null, null);
        Campus next = CampusConfigurationValidator.merge(old, patch, List.of());
        assertEquals(4, next.version()); assertEquals("旧地址", next.address());
        assertEquals(new Room("R1", "教室1", 25, 2), next.rooms().get(0));
        assertEquals("教室2", next.rooms().get(1).name());
        assertNotEquals("R1", next.rooms().get(1).id());
    }
    @Test void rejectsForgedRoomIdsAndUnknownSubjects() {
        assertThrows(BusinessException.class, () -> CampusConfigurationValidator.merge(null,
            fields(List.of(new RoomInput("forged", "教室1", 10, 0)), null, null), List.of()));
        assertThrows(BusinessException.class, () -> CampusConfigurationValidator.merge(null,
            fields(null, List.of("未配置学科"), null), List.of()));
        assertDoesNotThrow(() -> CampusConfigurationValidator.merge(null,
            fields(null, List.of("拓展"), null), List.of("拓展")));
    }
    @Test void gradeAliasesNormalizeAndDuplicateGradesAreRejected() {
        assertEquals("初中一年级", CampusConfigurationValidator.grade("七年级"));
        assertEquals("高中三年级", CampusConfigurationValidator.grade("高３"));
        assertThrows(BusinessException.class, () -> CampusConfigurationValidator.grade("高中四年级"));
        assertThrows(BusinessException.class, () -> CampusConfigurationValidator.merge(null,
            fields(null, null, List.of("小一", "小学一年级")), List.of()));
    }
    @Test void blankNamesInvalidCapacitiesAndDuplicateRoomsAreRejected() {
        assertThrows(BusinessException.class, () -> CampusConfigurationValidator.merge(null,
            new CampusFields(" ", null, null, null, null, null, null), List.of()));
        assertThrows(BusinessException.class, () -> CampusConfigurationValidator.merge(null,
            fields(List.of(new RoomInput(null, "教室1", 0, 0)), null, null), List.of()));
        assertThrows(BusinessException.class, () -> CampusConfigurationValidator.merge(null,
            fields(List.of(new RoomInput(null, "A", 10, 0), new RoomInput(null, "A", 10, 0)), null, null), List.of()));
    }
    private CampusFields fields(List<RoomInput> rooms, List<String> subjects, List<String> grades) {
        return new CampusFields("合成校区", null, null, rooms, null, subjects, grades);
    }
}
