package cn.siyue.core;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Java/MySQL core entry point. All persistence timestamps use UTC. */
@SpringBootApplication
public class SiyueApplication {
    /** Starts the service with external configuration; startup errors fail closed. */
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        if (args.length > 0 && "legacy-migration-candidate".equals(args[0])) {
            LegacyMigrationCandidateCli.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(SiyueApplication.class, args);
    }
}
