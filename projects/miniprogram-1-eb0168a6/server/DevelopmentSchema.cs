using Microsoft.EntityFrameworkCore.Storage;
using Microsoft.EntityFrameworkCore;
namespace Siyue;
public static class DevelopmentSchema
{
    // Older local previews used EnsureCreated. Upgrade only those SQLite databases,
    // preserving all lecture IDs, source files, students and wrong-question links.
    public static async Task Upgrade(Data db)
    {
        if (!db.Database.IsSqlite()) return;
        await db.Database.OpenConnectionAsync(); var connection = db.Database.GetDbConnection();
        using var query = connection.CreateCommand(); query.CommandText = "PRAGMA table_info('Lectures')";
        var oldLecture = false; await using (var rows = await query.ExecuteReaderAsync()) while (await rows.ReadAsync()) if (rows.GetString(1) == "ClassId") oldLecture = rows.GetInt32(3) == 1;
        query.CommandText = "PRAGMA table_info('LessonPlans')"; var planColumns = new HashSet<string>();
        await using (var rows = await query.ExecuteReaderAsync()) while (await rows.ReadAsync()) planColumns.Add(rows.GetString(1));
        await db.Database.ExecuteSqlRawAsync("PRAGMA foreign_keys=OFF");
        try {
            await using var tx = await db.Database.BeginTransactionAsync();
            await db.Database.ExecuteSqlRawAsync("""
                CREATE TABLE IF NOT EXISTS "AutomationCredentials" ("Id" TEXT NOT NULL PRIMARY KEY,"StaffId" TEXT NOT NULL REFERENCES "Staff"("Id") ON DELETE RESTRICT,"Name" TEXT NOT NULL,"TokenHash" TEXT NOT NULL,"ScopesJson" TEXT NOT NULL,"CreatedAt" INTEGER NOT NULL,"ExpiresAt" INTEGER NOT NULL,"RevokedAt" INTEGER NULL);
                CREATE UNIQUE INDEX IF NOT EXISTS "IX_AutomationCredentials_TokenHash" ON "AutomationCredentials"("TokenHash");
                CREATE INDEX IF NOT EXISTS "IX_AutomationCredentials_StaffId" ON "AutomationCredentials"("StaffId");
                CREATE TABLE IF NOT EXISTS "PrintRequests" ("Id" TEXT NOT NULL PRIMARY KEY,"StaffId" TEXT NOT NULL,"RequestKey" TEXT NOT NULL,"RequestHash" TEXT NOT NULL);
                CREATE UNIQUE INDEX IF NOT EXISTS "IX_PrintRequests_StaffId_RequestKey" ON "PrintRequests"("StaffId","RequestKey");
                CREATE TABLE IF NOT EXISTS "ClassSessions" ("Id" TEXT NOT NULL PRIMARY KEY,"ClassId" TEXT NOT NULL REFERENCES "Classes"("Id") ON DELETE RESTRICT,"CourseId" TEXT NOT NULL REFERENCES "Courses"("Id") ON DELETE RESTRICT,"HandoutId" TEXT NOT NULL REFERENCES "Handouts"("Id") ON DELETE RESTRICT,"StaffId" TEXT NOT NULL,"LessonDate" TEXT NOT NULL,"CreatedAt" INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS "ClassSessionMarks" ("Id" TEXT NOT NULL PRIMARY KEY,"ClassSessionId" TEXT NOT NULL REFERENCES "ClassSessions"("Id") ON DELETE RESTRICT,"StudentId" TEXT NOT NULL REFERENCES "Students"("Id") ON DELETE RESTRICT,"WrongIdsJson" TEXT NOT NULL,"NotesJson" TEXT NOT NULL,"Reviewed" INTEGER NOT NULL,"Revision" INTEGER NOT NULL,"RecordedBy" TEXT NOT NULL);
                CREATE UNIQUE INDEX IF NOT EXISTS "IX_ClassSessionMarks_ClassSessionId_StudentId" ON "ClassSessionMarks"("ClassSessionId","StudentId");
                CREATE TABLE IF NOT EXISTS "PasswordAccounts" ("Id" TEXT NOT NULL PRIMARY KEY, "Account" TEXT NOT NULL, "StaffId" TEXT NOT NULL REFERENCES "Staff" ("Id") ON DELETE RESTRICT, "PasswordHash" TEXT NOT NULL);
                CREATE UNIQUE INDEX IF NOT EXISTS "IX_PasswordAccounts_Account" ON "PasswordAccounts" ("Account");
                CREATE UNIQUE INDEX IF NOT EXISTS "IX_PasswordAccounts_StaffId" ON "PasswordAccounts" ("StaffId");
                """);
            if (oldLecture) {
                await db.Database.ExecuteSqlRawAsync("""
                    CREATE TABLE "Lectures_shared" ("Id" TEXT NOT NULL CONSTRAINT "PK_Lectures" PRIMARY KEY, "ClassId" TEXT NULL, "CourseId" TEXT NOT NULL, "Title" TEXT NOT NULL, "Sequence" INTEGER NOT NULL,
                        CONSTRAINT "FK_Lectures_Classes_ClassId" FOREIGN KEY ("ClassId") REFERENCES "Classes" ("Id") ON DELETE RESTRICT,
                        CONSTRAINT "FK_Lectures_Courses_CourseId" FOREIGN KEY ("CourseId") REFERENCES "Courses" ("Id") ON DELETE RESTRICT);
                    INSERT INTO "Lectures_shared" ("Id", "ClassId", "CourseId", "Title", "Sequence") SELECT "Id", "ClassId", "CourseId", "Title", "Sequence" FROM "Lectures";
                    DROP TABLE "Lectures";
                    ALTER TABLE "Lectures_shared" RENAME TO "Lectures";
                    CREATE INDEX "IX_Lectures_ClassId" ON "Lectures" ("ClassId");
                    CREATE INDEX "IX_Lectures_CourseId" ON "Lectures" ("CourseId");
                    """);
            }
            await db.Database.ExecuteSqlRawAsync("""
                CREATE TABLE IF NOT EXISTS "LessonPlans" ("Id" TEXT NOT NULL PRIMARY KEY, "StaffId" TEXT NOT NULL REFERENCES "Staff" ("Id") ON DELETE RESTRICT, "ClassId" TEXT NOT NULL REFERENCES "Classes" ("Id") ON DELETE RESTRICT, "CourseId" TEXT NOT NULL REFERENCES "Courses" ("Id") ON DELETE RESTRICT, "Title" TEXT NOT NULL, "Content" TEXT NOT NULL, "WordKey" TEXT NOT NULL DEFAULT '', "WordFileName" TEXT NOT NULL DEFAULT '', "UpdatedAt" INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS "IX_LessonPlans_StaffId" ON "LessonPlans" ("StaffId");
                CREATE INDEX IF NOT EXISTS "IX_LessonPlans_ClassId" ON "LessonPlans" ("ClassId");
                CREATE INDEX IF NOT EXISTS "IX_LessonPlans_CourseId" ON "LessonPlans" ("CourseId");
                CREATE TABLE IF NOT EXISTS "Pairings" ("Id" TEXT NOT NULL PRIMARY KEY, "Name" TEXT NOT NULL, "CodeHash" TEXT NOT NULL, "PollHash" TEXT NOT NULL, "DeviceTokenHash" TEXT NOT NULL, "DeviceId" TEXT NULL, "ExpiresAt" INTEGER NOT NULL);
                CREATE UNIQUE INDEX IF NOT EXISTS "IX_Pairings_CodeHash" ON "Pairings" ("CodeHash");
                CREATE UNIQUE INDEX IF NOT EXISTS "IX_Pairings_PollHash" ON "Pairings" ("PollHash");
                """);
            if (planColumns.Count > 0 && !planColumns.Contains("WordKey")) await db.Database.ExecuteSqlRawAsync("ALTER TABLE LessonPlans ADD COLUMN WordKey TEXT NOT NULL DEFAULT ''");
            if (planColumns.Count > 0 && !planColumns.Contains("WordFileName")) await db.Database.ExecuteSqlRawAsync("ALTER TABLE LessonPlans ADD COLUMN WordFileName TEXT NOT NULL DEFAULT ''");
            foreach (var (table, column, type) in new (string, string, string)[] {
                ("Classes", "OwnerStaffId", "TEXT NULL"),
                ("Staff", "CanSelectAnyCampus", "INTEGER NOT NULL DEFAULT 0"),
                ("PasswordAccounts", "MustChangePassword", "INTEGER NOT NULL DEFAULT 0"),
                ("Staff", "Archived", "INTEGER NOT NULL DEFAULT 0"), ("Campuses", "Archived", "INTEGER NOT NULL DEFAULT 0"), ("Courses", "Archived", "INTEGER NOT NULL DEFAULT 0"), ("Classes", "Archived", "INTEGER NOT NULL DEFAULT 0"),
                ("Documents", "Layout", "TEXT NOT NULL DEFAULT 'analysis'"), ("Documents", "ClassSessionId", "TEXT NULL"),
                ("Documents", "Archived", "INTEGER NOT NULL DEFAULT 0"), ("Documents", "Kind", "TEXT NOT NULL DEFAULT 'booklet'"), ("Documents", "SourceKey", "TEXT NOT NULL DEFAULT ''"), ("Documents", "SourceFileName", "TEXT NOT NULL DEFAULT ''"), ("Documents", "LectureId", "TEXT NULL"), ("Documents", "MaterialKind", "TEXT NOT NULL DEFAULT ''"), ("Printers", "Suppressed", "INTEGER NOT NULL DEFAULT 0"),
                ("Devices", "MonitorVersion", "TEXT NULL"), ("Devices", "MonitorLastSeen", "INTEGER NULL"), ("Devices", "MonitorError", "TEXT NULL"),
                ("PrintJobs", "ExecutionJson", "TEXT NULL"), ("PrintJobs", "ExecutionAt", "INTEGER NULL"),
                ("Printers", "DeviceReachable", "INTEGER NULL"), ("Printers", "DeviceCheckedAt", "INTEGER NULL"),
                ("Pairings", "RequestedProfileJson", "TEXT NULL"), ("Pairings", "Automatic", "INTEGER NOT NULL DEFAULT 0"), ("Pairings", "LastSeen", "INTEGER NULL"),
                ("Devices", "AgentVersion", "TEXT NOT NULL DEFAULT ''"), ("Devices", "MachineName", "TEXT NOT NULL DEFAULT ''"), ("Devices", "ProbeError", "TEXT NULL"),
                ("Printers", "Status", "TEXT NOT NULL DEFAULT 'unknown'"), ("Printers", "StatusDetail", "TEXT NOT NULL DEFAULT ''"), ("Printers", "RawStatus", "INTEGER NOT NULL DEFAULT 0"),
                ("Printers", "JobCount", "INTEGER NOT NULL DEFAULT 0"), ("Printers", "Driver", "TEXT NOT NULL DEFAULT ''"), ("Printers", "Port", "TEXT NOT NULL DEFAULT ''"), ("Printers", "StatusAt", "INTEGER NULL") }) {
                using var columnsQuery = connection.CreateCommand(); columnsQuery.Transaction = (System.Data.Common.DbTransaction)tx.GetDbTransaction(); columnsQuery.CommandText = $"PRAGMA table_info('{table}')";
                var exists = false; await using (var rows = await columnsQuery.ExecuteReaderAsync()) while (await rows.ReadAsync()) if (rows.GetString(1) == column) exists = true;
                // Identifiers and types come only from the literal allow-list above.
#pragma warning disable EF1002
                if (!exists) await db.Database.ExecuteSqlRawAsync($"ALTER TABLE \"{table}\" ADD COLUMN \"{column}\" {type}");

#pragma warning restore EF1002
            }
            await db.Database.ExecuteSqlRawAsync("""
                CREATE TABLE IF NOT EXISTS "DeviceOperations" ("Id" TEXT NOT NULL PRIMARY KEY,"DeviceId" TEXT NOT NULL,"PrinterId" TEXT NULL,"StaffId" TEXT NOT NULL,"Action" TEXT NOT NULL,"JobId" INTEGER NULL,"State" TEXT NOT NULL,"RequestKey" TEXT NOT NULL,"ClaimTokenHash" TEXT NULL,"ResultProtected" TEXT NULL,"CreatedAt" INTEGER NOT NULL,"ExpiresAt" INTEGER NOT NULL,"CompletedAt" INTEGER NULL);
                CREATE UNIQUE INDEX IF NOT EXISTS "IX_DeviceOperations_DeviceId_RequestKey" ON "DeviceOperations" ("DeviceId","RequestKey");
                """);
            using (var schema = connection.CreateCommand()) {
                schema.Transaction = (System.Data.Common.DbTransaction)tx.GetDbTransaction(); schema.CommandText = "SELECT sql FROM sqlite_master WHERE type='table' AND name='Documents'";
                var create = (string?)await schema.ExecuteScalarAsync();
                if (create != null && create.Contains("\"StudentId\" TEXT NOT NULL")) {
                    var replacement = create.Replace("\"Documents\"", "\"Documents_nullable\"");
                    foreach (var column in new[] { "StudentId", "ClassId", "CourseId" }) replacement = replacement.Replace("\"" + column + "\" TEXT NOT NULL", "\"" + column + "\" TEXT NULL");
#pragma warning disable EF1002
                    await db.Database.ExecuteSqlRawAsync(replacement);
#pragma warning restore EF1002
                    await db.Database.ExecuteSqlRawAsync("INSERT INTO Documents_nullable SELECT * FROM Documents; DROP TABLE Documents; ALTER TABLE Documents_nullable RENAME TO Documents;");
                }
            }
            await db.Database.ExecuteSqlRawAsync("""
                CREATE TABLE IF NOT EXISTS "MobileUploads" ("Id" TEXT NOT NULL PRIMARY KEY,"StaffId" TEXT NOT NULL,"SessionHash" TEXT NOT NULL,"TokenHash" TEXT NOT NULL,"Mode" TEXT NOT NULL,"ClassId" TEXT NULL,"CourseId" TEXT NULL,"LectureId" TEXT NULL,"MaterialKind" TEXT NOT NULL,"State" TEXT NOT NULL,"ResultUrl" TEXT NOT NULL,"ExpiresAt" INTEGER NOT NULL);
                CREATE UNIQUE INDEX IF NOT EXISTS "IX_MobileUploads_TokenHash" ON "MobileUploads" ("TokenHash");
                CREATE TABLE IF NOT EXISTS "Attendances" ("Id" TEXT NOT NULL PRIMARY KEY,"StudentId" TEXT NOT NULL REFERENCES "Students" ("Id") ON DELETE RESTRICT,"LectureId" TEXT NOT NULL REFERENCES "Lectures" ("Id") ON DELETE RESTRICT,"Absent" INTEGER NOT NULL,"RecordedBy" TEXT NOT NULL,"UpdatedAt" INTEGER NOT NULL);
                CREATE UNIQUE INDEX IF NOT EXISTS "IX_Attendances_StudentId_LectureId" ON "Attendances" ("StudentId","LectureId");
                CREATE INDEX IF NOT EXISTS "IX_Attendances_LectureId" ON "Attendances" ("LectureId");
                CREATE TABLE IF NOT EXISTS "WrongSettings" ("Id" TEXT NOT NULL PRIMARY KEY,"StudentId" TEXT NOT NULL REFERENCES "Students" ("Id") ON DELETE RESTRICT,"LectureId" TEXT NOT NULL REFERENCES "Lectures" ("Id") ON DELETE RESTRICT,"HandoutId" TEXT NOT NULL,"RecordedBy" TEXT NOT NULL,"RecordedAt" INTEGER NOT NULL,"MarksJson" TEXT NOT NULL DEFAULT '{{}}');
                CREATE UNIQUE INDEX IF NOT EXISTS "IX_WrongSettings_StudentId_LectureId" ON "WrongSettings" ("StudentId","LectureId");
                CREATE INDEX IF NOT EXISTS "IX_WrongSettings_LectureId" ON "WrongSettings" ("LectureId");
                """);
            using (var marksColumns = connection.CreateCommand()) {
                marksColumns.Transaction = (System.Data.Common.DbTransaction)tx.GetDbTransaction(); marksColumns.CommandText = "PRAGMA table_info('WrongSettings')";
                var hasMarks = false; await using (var columns = await marksColumns.ExecuteReaderAsync()) while (await columns.ReadAsync()) if (columns.GetString(1) == "MarksJson") hasMarks = true;
                if (!hasMarks) await db.Database.ExecuteSqlRawAsync("ALTER TABLE WrongSettings ADD COLUMN MarksJson TEXT NOT NULL DEFAULT '{{}}'");
            }
            await db.Database.ExecuteSqlRawAsync("""
                CREATE TABLE IF NOT EXISTS "LearningModelConfigurations" ("Id" TEXT NOT NULL PRIMARY KEY,"ProtectedJson" TEXT NOT NULL,"Revision" INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS "BookImports" ("Id" TEXT NOT NULL PRIMARY KEY, "StaffId" TEXT NOT NULL,"ClassId" TEXT NOT NULL REFERENCES "Classes" ("Id") ON DELETE RESTRICT,"CourseId" TEXT NOT NULL REFERENCES "Courses" ("Id") ON DELETE RESTRICT,"FileName" TEXT NOT NULL,"OriginalKey" TEXT NOT NULL,"NormalizedKey" TEXT NOT NULL,"State" TEXT NOT NULL,"Error" TEXT NULL,"Revision" INTEGER NOT NULL,"SectionsJson" TEXT NOT NULL,"BlockCount" INTEGER NOT NULL,"CreatedAt" INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS "IX_BookImports_ClassId_CourseId" ON "BookImports" ("ClassId","CourseId");
                CREATE INDEX IF NOT EXISTS "IX_BookImports_CourseId" ON "BookImports" ("CourseId");
                CREATE TABLE IF NOT EXISTS "LearningEvents" ("Id" TEXT NOT NULL PRIMARY KEY,"StudentId" TEXT NOT NULL REFERENCES "Students" ("Id") ON DELETE RESTRICT,"QuestionId" TEXT NOT NULL REFERENCES "Questions" ("Id") ON DELETE RESTRICT,"ClassId" TEXT NOT NULL,"CourseId" TEXT NOT NULL,"RecordedBy" TEXT NOT NULL,"State" TEXT NOT NULL,"Kind" TEXT NOT NULL,"RequestKey" TEXT NOT NULL,"RecordedAt" INTEGER NOT NULL);
                CREATE UNIQUE INDEX IF NOT EXISTS "IX_LearningEvents_StudentId_QuestionId_RequestKey" ON "LearningEvents" ("StudentId","QuestionId","RequestKey");
                CREATE INDEX IF NOT EXISTS "IX_LearningEvents_QuestionId" ON "LearningEvents" ("QuestionId");
                CREATE TABLE IF NOT EXISTS "LearningArtifacts" ("Id" TEXT NOT NULL PRIMARY KEY,"StudentId" TEXT NOT NULL REFERENCES "Students" ("Id") ON DELETE RESTRICT,"ClassId" TEXT NOT NULL,"CourseId" TEXT NOT NULL,"StaffId" TEXT NOT NULL,"Kind" TEXT NOT NULL,"State" TEXT NOT NULL,"RequestKey" TEXT NOT NULL,"PayloadJson" TEXT NOT NULL,"Error" TEXT NULL,"Revision" INTEGER NOT NULL,"DocumentId" TEXT NULL,"CreatedAt" INTEGER NOT NULL);
                CREATE UNIQUE INDEX IF NOT EXISTS "IX_LearningArtifacts_StudentId_RequestKey" ON "LearningArtifacts" ("StudentId","RequestKey");
                """);
            foreach (var table in new[] { "Handouts", "BookImports" }) {
                using var columns = connection.CreateCommand(); columns.Transaction = tx.GetDbTransaction();
                columns.CommandText = $"PRAGMA table_info('{table}')";
                var exists = false;
                await using (var rows = await columns.ExecuteReaderAsync()) while (await rows.ReadAsync()) if (rows.GetString(1) == "RecognitionMode") exists = true;
#pragma warning disable EF1002
                if (!exists) await db.Database.ExecuteSqlRawAsync($"ALTER TABLE \"{table}\" ADD COLUMN \"RecognitionMode\" TEXT NOT NULL DEFAULT 'legacy'");
#pragma warning restore EF1002
            }
            await tx.CommitAsync();
        } finally { await db.Database.ExecuteSqlRawAsync("PRAGMA foreign_keys=ON"); await db.Database.CloseConnectionAsync(); }
    }
}
