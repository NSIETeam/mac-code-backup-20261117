package cn.siyue.core;

import cn.siyue.core.CampusConfigurationDtos.Campus;
import cn.siyue.core.CampusConfigurationDtos.CampusFields;
import cn.siyue.core.CampusConfigurationDtos.Room;
import cn.siyue.core.CampusConfigurationDtos.RoomInput;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Applies the existing campus configuration rules without accessing business records. */
final class CampusConfigurationValidator {
    private static final List<String> SUBJECTS = List.of("语文", "数学", "英语", "物理", "化学", "生物",
        "历史", "地理", "道德与法治", "政治", "科学");
    private static final String NUMBERS = "一二三四五六七八九";
    private static final Pattern GRADE = Pattern.compile("^(小学|小|初中|初|高中|高)?([一二三四五六七八九1-9])(?:年级|年)?$");
    private CampusConfigurationValidator() { }

    static Campus merge(Campus old, CampusFields patch, List<String> dictionarySubjects) {
        if (patch == null) { throw invalid("校区资料不能为空"); }
        Campus base = old == null ? new Campus("CAMPUS_" + UUID.randomUUID(), null, null, null,
            true, 0, List.of(), List.of(), List.of()) : old;
        String name = text(patch.name() == null ? base.name() : patch.name(), 100, true);
        Set<String> allowed = new HashSet<>(SUBJECTS);
        allowed.addAll(dictionarySubjects); allowed.addAll(base.subjects());
        List<String> subjects = patch.subjects() == null ? base.subjects() : subjects(patch.subjects(), allowed);
        List<String> grades = patch.grades() == null ? base.grades() : grades(patch.grades());
        List<Room> rooms = patch.rooms() == null ? base.rooms() : rooms(patch.rooms(), base.rooms());
        return new Campus(base.id(), name, optional(patch.address(), base.address(), 500),
            optional(patch.phone(), base.phone(), 50), patch.active() == null ? base.active() : patch.active(),
            base.version() + 1, rooms, subjects, grades);
    }

    private static String optional(String value, String previous, int max) {
        return value == null ? previous : text(value, max, false);
    }
    private static String text(String value, int max, boolean required) {
        if (value == null || value.trim().length() > max || required && value.isBlank()) {
            throw invalid("校区文字字段为空或过长");
        }
        return value.trim();
    }
    private static List<String> subjects(List<String> input, Set<String> allowed) {
        if (input.size() > 100) { throw invalid("校区学科最多100项"); }
        List<String> result = input.stream().map(value -> text(value, 100, true)).toList();
        if (new HashSet<>(result).size() != result.size() || !allowed.containsAll(result)) {
            throw invalid("校区学科重复或不在教学字典中");
        }
        return result;
    }
    private static List<String> grades(List<String> input) {
        if (input.size() > 12) { throw invalid("覆盖年级最多12项"); }
        List<String> result = input.stream().map(CampusConfigurationValidator::grade).toList();
        if (new HashSet<>(result).size() != result.size()) { throw invalid("覆盖年级重复"); }
        return result;
    }
    static String grade(String input) {
        String value = Normalizer.normalize(text(input, 100, true), Normalizer.Form.NFKC).replaceAll("\\s+", "");
        var match = GRADE.matcher(value);
        if (!match.matches()) { throw invalid("覆盖年级无效"); }
        int number = Character.isDigit(match.group(2).charAt(0)) ? Integer.parseInt(match.group(2))
            : NUMBERS.indexOf(match.group(2)) + 1;
        String stage = match.group(1);
        if (stage == null) {
            if (!value.endsWith("年") && !value.endsWith("年级")) { throw invalid("覆盖年级需指定学段或年级"); }
            stage = number > 6 ? "初中" : "小学";
            if (number > 6) { number -= 6; }
        }
        return canonicalGrade(stage, number);
    }
    private static String canonicalGrade(String stage, int number) {
        String normalized = stage.startsWith("小") ? "小学" : stage.startsWith("初") ? "初中" : "高中";
        int max = normalized.equals("小学") ? 6 : 3;
        if (number < 1 || number > max) { throw invalid("覆盖年级超出小学一年级至高中三年级"); }
        return normalized + NUMBERS.charAt(number - 1) + "年级";
    }
    private static List<Room> rooms(List<RoomInput> input, List<Room> previous) {
        if (input.size() > 200) { throw invalid("教室最多200间"); }
        List<Room> result = new ArrayList<>();
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (RoomInput item : input) {
            Room room = room(item, previous);
            if (!ids.add(room.id()) || !room.name().isBlank() && !names.add(room.name())) {
                throw invalid("教室编号或名称重复");
            }
            result.add(room);
        }
        return autoNames(result, names);
    }
    private static Room room(RoomInput item, List<Room> previous) {
        if (item == null || item.capacity() == null || item.capacity() < 1 || item.capacity() > 10000) {
            throw invalid("教室容量须为1至10000");
        }
        String name = item.name() == null ? "" : text(item.name(), 100, false);
        Room old = previous.stream().filter(row -> item.id() != null ? row.id().equals(item.id())
            : row.name().equals(name)).findFirst().orElse(null);
        if (item.id() != null && old == null) { throw invalid("不能伪造教室编号"); }
        int virtual = virtualSeats(item, old);
        return new Room(old == null ? "ROOM_" + UUID.randomUUID() : old.id(),
            item.name() == null && old != null ? old.name() : name, item.capacity(), virtual);
    }
    private static int virtualSeats(RoomInput item, Room old) {
        int value = item.virtualSeats() == null ? old == null ? 0 : old.virtualSeats() : item.virtualSeats();
        if (value < 0 || value > 10000) { throw invalid("虚拟座位须为0至10000"); }
        return value;
    }
    private static List<Room> autoNames(List<Room> rooms, Set<String> names) {
        List<Room> result = new ArrayList<>();
        for (Room room : rooms) {
            String name = room.name(); int number = 1;
            if (name.isBlank()) {
                do { name = "教室" + number++; } while (names.contains(name));
                names.add(name);
            }
            result.add(new Room(room.id(), name, room.capacity(), room.virtualSeats()));
        }
        return List.copyOf(result);
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_INPUT, message); }
}
