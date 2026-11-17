package cn.siyue.core;

import cn.siyue.core.CampusConfigurationDtos.Campus;
import cn.siyue.core.CampusConfigurationDtos.CampusFields;
import cn.siyue.core.CampusConfigurationDtos.CampusPage;
import cn.siyue.core.CampusConfigurationDtos.Room;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores normalized campus configuration under current account scope and campus resource locks. */
@Repository
public class CampusConfigurationRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    public CampusConfigurationRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc; this.cipher = cipher;
    }
    private record Row(long id, String publicId, String name, String address, String phone, boolean active, long version) { }
    private record Occupancy(String room, int capacity, String subject, String grade) { }

    public CampusPage list(Actor actor, int page, int size) {
        ScopeSql.Clause scope = scope(actor);
        Long total = jdbc.queryForObject("SELECT COUNT(id) FROM core_campus WHERE deleted=0 AND (" + scope.sql() + ")",
            Long.class, scope.args().toArray());
        List<Object> args = new ArrayList<>(scope.args()); args.add(size); args.add((page - 1) * size);
        List<String> ids = jdbc.queryForList("SELECT public_id FROM core_campus WHERE deleted=0 AND ("
            + scope.sql() + ") ORDER BY id LIMIT ? OFFSET ?", String.class, args.toArray());
        return new CampusPage(ids.stream().map(id -> get(actor, id, false)).toList(), total == null ? 0 : total, page, size);
    }
    public Campus get(Actor actor, String publicId, boolean lock) { return campus(row(actor, publicId, lock)); }
    public void lockDirectory() {
        jdbc.queryForObject("SELECT id FROM core_campus_configuration_lock WHERE lock_name='campus-directory' FOR UPDATE", Long.class);
    }
    public Campus create(CampusFields input) {
        Campus next = CampusConfigurationValidator.merge(null, input, dictionarySubjects());
        uniqueName(next);
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher,address_cipher,phone_cipher,active,version,configuration_ready) VALUES(?,?,?,?,?,?,1)",
            next.id(), cipher.encrypt(next.name()), encrypt(next.address()), encrypt(next.phone()), next.active(), next.version());
        long id = jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id=?", Long.class, next.id());
        replaceCollections(id, next);
        return next;
    }
    public Campus update(Actor actor, String publicId, CampusConfigurationDtos.UpdateInput input) {
        Row row = row(actor, publicId, true);
        if (row.version() != input.expectedVersion()) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "校区已更新，请刷新后重试"); }
        Campus next = CampusConfigurationValidator.merge(campus(row), input.patch(), dictionarySubjects());
        uniqueName(next);
        validateOccupancy(row.id(), next, input.patch().rooms() != null);
        int changed = jdbc.update("UPDATE core_campus SET name_cipher=?,address_cipher=?,phone_cipher=?,active=?,"
            + "version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND version=? AND deleted=0",
            cipher.encrypt(next.name()), encrypt(next.address()), encrypt(next.phone()), next.active(), row.id(), row.version());
        if (changed != 1) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "校区已更新，请刷新后重试"); }
        replaceCollections(row.id(), next);
        return next;
    }
    private Row row(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = scope(actor);
        List<Object> args = new ArrayList<>(); args.add(publicId); args.addAll(scope.args());
        List<Row> rows = jdbc.query("SELECT id,public_id,name_cipher,address_cipher,phone_cipher,active,version "
            + "FROM core_campus WHERE public_id=? AND deleted=0 AND (" + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> new Row(rs.getLong("id"), rs.getString("public_id"), cipher.decrypt(rs.getString("name_cipher")),
                decrypt(rs.getString("address_cipher")), decrypt(rs.getString("phone_cipher")),
                rs.getBoolean("active"), rs.getLong("version")), args.toArray());
        if (rows.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "校区不存在或不在当前授权范围"); }
        return rows.get(0);
    }
    private Campus campus(Row row) {
        List<Room> rooms = jdbc.query("SELECT public_id,name,capacity,virtual_seats FROM core_campus_room "
            + "WHERE campus_id=? AND deleted=0 ORDER BY sequence_no,id", (rs, n) -> new Room(rs.getString("public_id"),
                rs.getString("name"), rs.getInt("capacity"), rs.getInt("virtual_seats")), row.id());
        return new Campus(row.publicId(), row.name(), row.address(), row.phone(), row.active(), row.version(),
            rooms, options(row.id(), "subject"), options(row.id(), "grade"));
    }
    private List<String> options(long campus, String kind) {
        return jdbc.queryForList("SELECT value FROM core_campus_academic_option WHERE campus_id=? AND kind=? "
            + "AND deleted=0 ORDER BY sequence_no,id", String.class, campus, kind);
    }
    private List<String> dictionarySubjects() {
        return jdbc.queryForList("SELECT value FROM core_academic_dictionary_value WHERE kind='subjects' AND deleted=0 ORDER BY sequence_no,id", String.class);
    }
    private void uniqueName(Campus campus) {
        List<String> names = jdbc.queryForList("SELECT name_cipher FROM core_campus WHERE public_id<>? AND deleted=0", String.class, campus.id());
        if (names.stream().map(cipher::decrypt).anyMatch(campus.name()::equals)) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "校区名称已存在");
        }
    }
    private void validateOccupancy(long campusId, Campus next, boolean roomsChanged) {
        List<Occupancy> classes = jdbc.query("SELECT c.room,c.capacity,co.subject,co.grade FROM core_class c "
            + "JOIN core_course co ON co.id=c.course_id WHERE c.campus_id=? AND c.deleted=0 AND c.active=1",
            (rs, n) -> new Occupancy(rs.getString("room"), rs.getInt("capacity"), rs.getString("subject"), rs.getString("grade")), campusId);
        if (!classes.isEmpty() && !next.active()) { throw inUse("仍有有效班级，不能停用校区"); }
        for (Occupancy item : classes) { validateClass(next, item, roomsChanged); }
        if (roomsChanged) { validateFutureSessionRooms(campusId, next); }
    }
    private void validateClass(Campus next, Occupancy item, boolean roomsChanged) {
        if (!next.subjects().isEmpty() && item.subject() != null && !next.subjects().contains(item.subject())) {
            throw inUse("存在不属于校区开设学科的有效班级");
        }
        if (!next.grades().isEmpty() && item.grade() != null
                && !next.grades().contains(CampusConfigurationValidator.grade(item.grade()))) {
            throw inUse("存在不属于校区覆盖年级的有效班级");
        }
        if (roomsChanged) { requireRoom(next, item.room(), item.capacity()); }
    }
    private void validateFutureSessionRooms(long campusId, Campus next) {
        List<Occupancy> sessions = jdbc.query("SELECT COALESCE(s.room,c.room) effective_room,c.capacity "
            + "FROM core_session s JOIN core_class c ON c.id=s.class_id "
            + "WHERE COALESCE(s.campus_id,c.campus_id)=? "
            + "AND c.deleted=0 AND c.active=1 AND s.deleted=0 AND DATE(s.starts_at)>=UTC_DATE() "
                + "AND s.status NOT IN ('cancelled','canceled')", (rs, n) -> new Occupancy(rs.getString("effective_room"),
                rs.getInt("capacity"), null, null), campusId);
        for (Occupancy item : sessions) { requireRoom(next, item.room(), item.capacity()); }
    }
    private void requireRoom(Campus campus, String name, int capacity) {
        if (name == null || name.isBlank()) { return; }
        if (campus.rooms().stream().noneMatch(room -> room.name().equals(name) && room.capacity() >= capacity)) {
            throw inUse("教室正在排课或容量不足，不能删除、改名或缩减容量");
        }
    }
    private void replaceCollections(long campus, Campus next) {
        jdbc.update("UPDATE core_campus_room SET deleted=1,updated_at=UTC_TIMESTAMP(3) WHERE campus_id=?", campus);
        int sequence = 0;
        for (Room room : next.rooms()) {
            jdbc.update("INSERT INTO core_campus_room(public_id,campus_id,name,capacity,virtual_seats,sequence_no) VALUES(?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE name=VALUES(name),capacity=VALUES(capacity),virtual_seats=VALUES(virtual_seats),"
                + "sequence_no=VALUES(sequence_no),deleted=0,updated_at=UTC_TIMESTAMP(3)",
                room.id(), campus, room.name(), room.capacity(), room.virtualSeats(), sequence++);
        }
        replaceOptions(campus, "subject", next.subjects()); replaceOptions(campus, "grade", next.grades());
    }
    private void replaceOptions(long campus, String kind, List<String> values) {
        jdbc.update("UPDATE core_campus_academic_option SET deleted=1,updated_at=UTC_TIMESTAMP(3) WHERE campus_id=? AND kind=?", campus, kind);
        for (int i = 0; i < values.size(); i++) {
            jdbc.update("INSERT INTO core_campus_academic_option(campus_id,kind,value,sequence_no) VALUES(?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE sequence_no=VALUES(sequence_no),deleted=0,updated_at=UTC_TIMESTAMP(3)", campus, kind, values.get(i), i);
        }
    }
    private ScopeSql.Clause scope(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("id IN (SELECT campus_id FROM core_principal_campus WHERE principal_id=? AND deleted=0)", List.of(actor.id()));
            default -> new ScopeSql.Clause("1=0", List.of());
        };
    }
    private String encrypt(String value) { return value == null ? null : cipher.encrypt(value); }
    private String decrypt(String value) { return value == null ? null : cipher.decrypt(value); }
    private BusinessException inUse(String message) { return new BusinessException(ErrorCode.INVALID_INPUT, message); }
}
