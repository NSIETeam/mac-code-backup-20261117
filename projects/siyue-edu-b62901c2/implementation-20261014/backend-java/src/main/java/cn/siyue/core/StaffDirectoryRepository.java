package cn.siyue.core;

import cn.siyue.core.StaffDirectoryDtos.DirectoryMember;
import cn.siyue.core.StaffDirectoryDtos.DirectoryPage;
import cn.siyue.core.StaffDirectoryDtos.Scope;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads active principals and their independent, encrypted business profiles. */
@Repository
public class StaffDirectoryRepository {
    private static final String TEACHER = "EXISTS (SELECT pp.id FROM core_principal_position pp "
        + "JOIN core_access_position ap ON ap.id=pp.position_id AND ap.deleted=0 AND ap.active=1 "
        + "WHERE pp.principal_id=p.id AND pp.deleted=0 AND ap.directory_kind='teacher')";
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    /** Wires scoped directory SQL and decryption of the permitted profile projection.
     * @param jdbc transaction-bound database access
     * @param cipher keyring for identity names, employee numbers and business-profile fields
     */
    public StaffDirectoryRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** Reads active directory members, using current active positions to distinguish teachers from staff.
     * @param actor authorized caller; own/assigned scopes see self, campus scope sees self and shared campuses
     * @param category validated all, staff or teacher category
     * @param page validated one-based page
     * @param size validated maximum number of members
     * @return members, matching total and requested page metadata; missing profile values remain absent
     * @throws BusinessException if the caller has an unsupported directory scope
     */
    public DirectoryPage list(Actor actor, String category, int page, int size) {
        QueryParts query = query(actor, category);
        Long total = jdbc.queryForObject("SELECT COUNT(p.id) FROM core_principal p WHERE p.deleted=0 "
            + "AND p.active=1 AND (" + query.sql() + ")", Long.class, query.args().toArray());
        List<PrincipalRow> rows = jdbc.query(select() + " FROM core_principal p "
            + "LEFT JOIN core_staff_profile sp ON sp.principal_id=p.id AND sp.deleted=0 "
            + "LEFT JOIN core_campus c ON c.id=sp.campus_id AND c.deleted=0 "
            + "WHERE p.deleted=0 AND p.active=1 AND (" + query.sql() + ") "
            + "ORDER BY p.public_id LIMIT ? OFFSET ?", (rs, index) -> new PrincipalRow(
                rs.getLong("id"), rs.getString("public_id"), decrypt(rs.getString("directory_name_cipher")),
                decrypt(rs.getString("directory_username_cipher")),
                decrypt(rs.getString("employee_no_cipher")), rs.getString("scope_type"),
                instant(rs.getTimestamp("profile_created_at")), rs.getLong("profile_version"),
                decrypt(rs.getString("phone_cipher")), date(rs.getDate("employment_start")),
                date(rs.getDate("employment_end")), decrypt(rs.getString("title_cipher")),
                decrypt(rs.getString("note_cipher")), rs.getString("campus_public_id"),
                decrypt(rs.getString("campus_name_cipher"))),
            concat(query.args(), List.of(size, (page - 1) * size)).toArray());
        return new DirectoryPage(rows.stream().map(this::member).toList(), total == null ? 0 : total,
            page, size, category);
    }

    private String select() {
        return "SELECT p.id,p.public_id,COALESCE(sp.name_cipher,p.display_name_cipher) AS directory_name_cipher,"
            + "COALESCE(sp.username_cipher,p.username_cipher) AS directory_username_cipher,"
            + "p.employee_no_cipher,p.scope_type,"
            + "sp.created_at AS profile_created_at,COALESCE(sp.version,0) AS profile_version,"
            + "sp.phone_cipher,sp.employment_start,sp.employment_end,sp.title_cipher,sp.note_cipher,"
            + "c.public_id AS campus_public_id,c.name_cipher AS campus_name_cipher";
    }

    private QueryParts query(Actor actor, String category) {
        List<Object> args = new ArrayList<>();
        String sql = visibility(actor, args);
        if (category.equals("teacher")) { sql += " AND " + TEACHER; }
        if (category.equals("staff")) { sql += " AND NOT " + TEACHER; }
        return new QueryParts(sql, List.copyOf(args));
    }

    private String visibility(Actor actor, List<Object> args) {
        return switch (actor.scope()) {
            case "all" -> "1=1";
            case "own", "assigned" -> { args.add(actor.id()); yield "p.id=?"; }
            case "campus" -> {
                args.add(actor.id());
                args.add(actor.id());
                yield "(p.id=? OR EXISTS (SELECT pc.id FROM core_principal_campus pc "
                    + "WHERE pc.principal_id=p.id AND pc.deleted=0 AND pc.campus_id IN "
                    + "(SELECT own_campus.campus_id FROM core_principal_campus own_campus "
                    + "WHERE own_campus.principal_id=? AND own_campus.deleted=0)))";
            }
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "员工目录范围无效");
        };
    }

    private DirectoryMember member(PrincipalRow row) {
        List<PositionRow> positions = jdbc.query("SELECT ap.public_id FROM core_principal_position pp "
            + "JOIN core_access_position ap ON ap.id=pp.position_id AND ap.deleted=0 AND ap.active=1 "
            + "WHERE pp.principal_id=? AND pp.deleted=0 ORDER BY ap.public_id", (rs, index) ->
                new PositionRow(rs.getString("public_id")), row.id());
        List<String> roleIds = positions.stream().map(PositionRow::publicId)
            .filter(id -> id.startsWith("LEGACY_ROLE_")).map(id -> id.substring("LEGACY_ROLE_".length())).toList();
        List<String> campuses = jdbc.queryForList("SELECT c.public_id FROM core_principal_campus pc "
            + "JOIN core_campus c ON c.id=pc.campus_id AND c.deleted=0 "
            + "WHERE pc.principal_id=? AND pc.deleted=0 ORDER BY c.public_id", String.class, row.id());
        return new DirectoryMember(row.publicId(), row.name(), row.username(), row.employeeNo(), roleIds,
            new Scope(row.scope(), campuses), false, row.createdAt(), row.profileVersion(), row.phone(),
            row.employmentStart(), row.employmentEnd(), row.title(), row.note(), row.campusId(), row.campus());
    }

    private String decrypt(String value) { return value == null ? null : cipher.decrypt(value); }
    private static String instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant().toString();
    }
    private static String date(java.sql.Date value) { return value == null ? null : value.toLocalDate().toString(); }
    private static List<Object> concat(List<Object> first, List<Object> second) {
        List<Object> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }
    /** Fixed directory predicate plus its ordered bind arguments; caller values are never interpolated into SQL. */
    private record QueryParts(String sql, List<Object> args) { }
    /** Current active position identifier used to project legacy role labels without consulting historical checkpoints. */
    private record PositionRow(String publicId) { }
    /** Decrypted directory-only identity and optional business-profile fields used to assemble one member response. */
    private record PrincipalRow(long id, String publicId, String name, String username, String employeeNo, String scope,
            String createdAt, long profileVersion, String phone, String employmentStart, String employmentEnd,
            String title, String note, String campusId, String campus) { }
}
