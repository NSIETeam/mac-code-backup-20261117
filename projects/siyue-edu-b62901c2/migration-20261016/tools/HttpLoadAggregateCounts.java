import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** Reads only aggregate student, idempotency, and audit counts for HTTP acceptance. */
public final class HttpLoadAggregateCounts {
    private HttpLoadAggregateCounts() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected private runtime and load manifest");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode runtime = mapper.readTree(Files.readString(Path.of(args[0])));
        JsonNode manifest = mapper.readTree(Files.readString(Path.of(args[1])));
        String url = runtime.path("SIYUE_MYSQL_URL").asText();
        String user = runtime.path("SIYUE_MYSQL_USER").asText();
        String password = runtime.path("SIYUE_MYSQL_PASSWORD").asText();
        String campus = manifest.path("campusId").asText();
        List<String> actors = new ArrayList<>();
        manifest.path("actors").forEach(actor -> actors.add(actor.path("principalId").asText()));
        if (actors.size() < 2 || actors.size() > 8 || actors.stream().anyMatch(id -> !id.startsWith("http-load-"))) {
            throw new IllegalArgumentException("Synthetic actor set is invalid");
        }
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            long campusId = scalar(connection,
                "SELECT id FROM core_campus WHERE public_id = ? AND deleted = 0", List.of(campus));
            long students = scalar(connection,
                "SELECT COUNT(id) FROM core_student WHERE campus_id = ? AND deleted = 0", List.of(campusId));
            String actorMarks = String.join(",", java.util.Collections.nCopies(actors.size(), "?"));
            List<Object> actorParams = new ArrayList<>(actors);
            actorParams.add("academic.student.create");
            long idempotency = scalar(connection,
                "SELECT COUNT(i.id) FROM core_idempotency i JOIN core_principal p ON p.id = i.actor_id "
                    + "WHERE p.public_id IN (" + actorMarks + ") AND i.action = ? AND i.deleted = 0", actorParams);
            long audit = scalar(connection,
                "SELECT COUNT(a.id) FROM core_audit a JOIN core_student s "
                    + "ON s.public_id = a.resource_public_id JOIN core_campus c ON c.id = s.campus_id "
                    + "WHERE c.public_id = ? AND s.deleted = 0 AND a.action = 'academic.student.create' "
                    + "AND a.deleted = 0", List.of(campus));
            System.out.println(mapper.writeValueAsString(new Counts(students, idempotency, audit)));
        }
    }

    private static long scalar(Connection connection, String sql, List<?> values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.size(); index++) statement.setObject(index + 1, values.get(index));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Aggregate count query returned no row");
                return result.getLong(1);
            }
        }
    }

    private record Counts(long students, long idempotency, long audit) { }
}
