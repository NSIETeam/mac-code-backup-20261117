import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads aggregate versioned student-name index coverage without decrypting any business record. */
public final class HttpLoadNameIndexCounts {
    private HttpLoadNameIndexCounts() { }

    /** Queries only a dedicated synthetic loopback workload schema.
     * @param args owner-only runtime JSON and workload manifest paths
     * @throws Exception when files, schema/campus boundaries, key configuration or SQL queries fail
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected private runtime and workload manifest");
        ObjectMapper mapper = new ObjectMapper();
        var runtime = mapper.readTree(readPrivate(args[0]));
        var manifest = mapper.readTree(readPrivate(args[1]));
        String url = runtime.path("SIYUE_MYSQL_URL").asText();
        String campus = manifest.path("campusId").asText();
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/siyue_http_load_[a-f0-9]{10}_test(?:\\?.*)?")
                || !campus.startsWith("http-load-") || !manifest.path("syntheticOnly").asBoolean()
                || !url.equals(manifest.path("jdbcUrl").asText())) {
            throw new IllegalArgumentException("Dedicated synthetic index coverage target required");
        }
        var keyring = mapper.readTree(runtime.path("SIYUE_PII_KEYS_JSON").asText());
        if (!keyring.isObject() || keyring.isEmpty()) throw new IllegalArgumentException("Expected versioned keyring");
        List<String> keys = new ArrayList<>();
        keyring.fieldNames().forEachRemaining(keys::add);
        try (Connection connection = DriverManager.getConnection(url, runtime.path("SIYUE_MYSQL_USER").asText(),
                runtime.path("SIYUE_MYSQL_PASSWORD").asText())) {
            String students = " FROM core_student s JOIN core_campus c ON c.id=s.campus_id"
                + " WHERE s.deleted=0 AND c.deleted=0 AND c.public_id=?";
            long total = scalar(connection, "SELECT COUNT(s.id)" + students, List.of(campus));
            String current = "SELECT ni.id FROM core_student_name_index ni WHERE ni.student_id=s.id"
                + " AND ni.deleted=0 AND ni.source_version=s.version";
            long fallback = scalar(connection, "SELECT COUNT(s.id)" + students + " AND NOT EXISTS ("
                + current + ")", List.of(campus));
            Map<String, Long> missingByKey = new LinkedHashMap<>();
            for (String key : keys) {
                missingByKey.put(key, scalar(connection, "SELECT COUNT(s.id)" + students
                    + " AND NOT EXISTS (" + current + " AND ni.key_id=?)", List.of(campus, key)));
            }
            long outdated = scalar(connection, "SELECT COUNT(ni.id) FROM core_student_name_index ni"
                + " JOIN core_student s ON s.id=ni.student_id JOIN core_campus c ON c.id=s.campus_id"
                + " WHERE s.deleted=0 AND c.deleted=0 AND c.public_id=? AND ni.deleted=0"
                + " AND ni.source_version<>s.version", List.of(campus));
            System.out.println(mapper.writeValueAsString(Map.of("students", total, "fallbackStudents", fallback,
                "outdatedActiveIndexRows", outdated, "missingCurrentByKey", missingByKey,
                "allActiveKeysCovered", missingByKey.values().stream().allMatch(value -> value == 0))));
        }
    }

    private static String readPrivate(String name) throws Exception {
        Path path = Path.of(name);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Private regular file required");
        }
        var permissions = Files.getPosixFilePermissions(path);
        for (var permission : permissions) {
            if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")) {
                throw new IllegalArgumentException("Owner-only private file required");
            }
        }
        return Files.readString(path);
    }

    private static long scalar(Connection connection, String sql, List<?> values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.size(); index++) statement.setObject(index + 1, values.get(index));
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Aggregate query returned no row");
                return rows.getLong(1);
            }
        }
    }
}
