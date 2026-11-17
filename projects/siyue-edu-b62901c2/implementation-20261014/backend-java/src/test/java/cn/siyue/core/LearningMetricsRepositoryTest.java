package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LearningMetricsRepositoryTest {
    private final RecordingJdbc jdbc = new RecordingJdbc();
    private final LearningMetricsRepository repository = new LearningMetricsRepository(jdbc);

    @Test void eachScopeUsesBoundParametersAndNetConsumptionLedger() {
        for (String scope : List.of("all", "campus", "assigned", "own")) {
            Actor actor = new Actor(17, "learning-metrics", scope,
                scope.equals("campus") ? Set.of(4L) : Set.of(), Set.of("report.read"));

            LearningMetricsDtos.Summary summary = repository.summary(actor);

            assertEquals(scope.equals("all") ? "全部授权数据" : scope.equals("campus") ? "授权校区"
                : scope.equals("assigned") ? "本人授课范围" : "本人负责范围", summary.scope());
            assertEquals(0, summary.students());
            assertEquals(5,jdbc.queries.size());
            for (int index = 0; index < jdbc.queries.size(); index++) {
                Query query=jdbc.queries.get(index);
                assertEquals(query.sql().chars().filter(character -> character == '?').count(),
                    query.args().length, scope + " SQL placeholder binding " + index);
            }
            String ledger = jdbc.queries.get(4).sql();
            assertTrue(ledger.contains("GROUP BY x.attendance_id HAVING SUM(x.units)>0"),
                "The report must count only consumption with a positive net signed ledger balance");
            assertTrue(ledger.contains("x.deleted=0"), "Deleted ledger rows must not contribute");
            jdbc.queries.clear();
        }
    }

    private record Query(String sql,Object[] args) { }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final List<Query> queries=new ArrayList<>();
        @Override public <T> T queryForObject(String sql,Class<T> requiredType,Object... args) {
            queries.add(new Query(sql,args.clone()));
            return requiredType.cast(0L);
        }
    }
}
