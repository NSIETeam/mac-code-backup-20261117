using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Siyue.Server.Migrations
{
    /// <inheritdoc />
    public partial class AcademicBetaOperations : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "AcademicAttendances",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ClassSessionId = table.Column<Guid>(type: "uuid", nullable: false),
                    StudentId = table.Column<Guid>(type: "uuid", nullable: false),
                    Status = table.Column<string>(type: "text", nullable: false),
                    Note = table.Column<string>(type: "text", nullable: false),
                    MakeupStatus = table.Column<string>(type: "text", nullable: false),
                    MakeupOn = table.Column<string>(type: "text", nullable: true),
                    Revision = table.Column<int>(type: "integer", nullable: false),
                    RecordedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    UpdatedAt = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicAttendances", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AcademicAttendances_ClassSessions_ClassSessionId",
                        column: x => x.ClassSessionId,
                        principalTable: "ClassSessions",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_AcademicAttendances_Students_StudentId",
                        column: x => x.StudentId,
                        principalTable: "Students",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "AcademicBetaEvents",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    EntityId = table.Column<Guid>(type: "uuid", nullable: false),
                    ClassSessionId = table.Column<Guid>(type: "uuid", nullable: false),
                    StaffId = table.Column<Guid>(type: "uuid", nullable: false),
                    Action = table.Column<string>(type: "text", nullable: false),
                    SnapshotJson = table.Column<string>(type: "text", nullable: false),
                    CreatedAt = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicBetaEvents", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "AcademicHomeworkRecords",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ClassSessionId = table.Column<Guid>(type: "uuid", nullable: false),
                    StudentId = table.Column<Guid>(type: "uuid", nullable: false),
                    HomeworkRevision = table.Column<int>(type: "integer", nullable: false),
                    Status = table.Column<string>(type: "text", nullable: false),
                    Note = table.Column<string>(type: "text", nullable: false),
                    Revision = table.Column<int>(type: "integer", nullable: false),
                    RecordedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    UpdatedAt = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicHomeworkRecords", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AcademicHomeworkRecords_ClassSessions_ClassSessionId",
                        column: x => x.ClassSessionId,
                        principalTable: "ClassSessions",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_AcademicHomeworkRecords_Students_StudentId",
                        column: x => x.StudentId,
                        principalTable: "Students",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "AcademicHomeworks",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ClassSessionId = table.Column<Guid>(type: "uuid", nullable: false),
                    Title = table.Column<string>(type: "text", nullable: false),
                    Requirements = table.Column<string>(type: "text", nullable: false),
                    DueOn = table.Column<string>(type: "text", nullable: true),
                    Revision = table.Column<int>(type: "integer", nullable: false),
                    RecordedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    UpdatedAt = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicHomeworks", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AcademicHomeworks_ClassSessions_ClassSessionId",
                        column: x => x.ClassSessionId,
                        principalTable: "ClassSessions",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "AcademicMessages",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ClassSessionId = table.Column<Guid>(type: "uuid", nullable: false),
                    StudentId = table.Column<Guid>(type: "uuid", nullable: true),
                    CreatedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    RequestKey = table.Column<string>(type: "text", nullable: false),
                    Kind = table.Column<string>(type: "text", nullable: false),
                    Title = table.Column<string>(type: "text", nullable: false),
                    SourceJson = table.Column<string>(type: "text", nullable: false),
                    SourceHash = table.Column<string>(type: "text", nullable: false),
                    Body = table.Column<string>(type: "text", nullable: false),
                    RecipientLabel = table.Column<string>(type: "text", nullable: false),
                    Channel = table.Column<string>(type: "text", nullable: false),
                    State = table.Column<string>(type: "text", nullable: false),
                    ReviewNote = table.Column<string>(type: "text", nullable: false),
                    Revision = table.Column<int>(type: "integer", nullable: false),
                    ApprovedBy = table.Column<Guid>(type: "uuid", nullable: true),
                    ApprovedAt = table.Column<long>(type: "bigint", nullable: true),
                    CreatedAt = table.Column<long>(type: "bigint", nullable: false),
                    UpdatedAt = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicMessages", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "AcademicOperationEvents",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    EntityId = table.Column<Guid>(type: "uuid", nullable: false),
                    ClassSessionId = table.Column<Guid>(type: "uuid", nullable: true),
                    ActorId = table.Column<Guid>(type: "uuid", nullable: false),
                    Action = table.Column<string>(type: "text", nullable: false),
                    SnapshotJson = table.Column<string>(type: "text", nullable: false),
                    CreatedAt = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicOperationEvents", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "AcademicPractices",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ClassSessionId = table.Column<Guid>(type: "uuid", nullable: false),
                    StudentId = table.Column<Guid>(type: "uuid", nullable: false),
                    QuestionId = table.Column<Guid>(type: "uuid", nullable: false),
                    Corrected = table.Column<bool>(type: "boolean", nullable: false),
                    RetestAt = table.Column<long>(type: "bigint", nullable: true),
                    RetestState = table.Column<string>(type: "text", nullable: false),
                    Note = table.Column<string>(type: "text", nullable: false),
                    Revision = table.Column<int>(type: "integer", nullable: false),
                    RecordedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    UpdatedAt = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicPractices", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "AcademicPreparations",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ClassSessionId = table.Column<Guid>(type: "uuid", nullable: false),
                    MaterialsReady = table.Column<bool>(type: "boolean", nullable: false),
                    RosterChecked = table.Column<bool>(type: "boolean", nullable: false),
                    HomeworkPlanned = table.Column<bool>(type: "boolean", nullable: false),
                    Note = table.Column<string>(type: "text", nullable: false),
                    Revision = table.Column<int>(type: "integer", nullable: false),
                    RecordedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    UpdatedAt = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicPreparations", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AcademicPreparations_ClassSessions_ClassSessionId",
                        column: x => x.ClassSessionId,
                        principalTable: "ClassSessions",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "AcademicTasks",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ClassSessionId = table.Column<Guid>(type: "uuid", nullable: false),
                    StudentId = table.Column<Guid>(type: "uuid", nullable: true),
                    Title = table.Column<string>(type: "text", nullable: false),
                    Category = table.Column<string>(type: "text", nullable: false),
                    DueOn = table.Column<string>(type: "text", nullable: true),
                    Status = table.Column<string>(type: "text", nullable: false),
                    Note = table.Column<string>(type: "text", nullable: false),
                    RequestKey = table.Column<string>(type: "text", nullable: false),
                    RequestHash = table.Column<string>(type: "text", nullable: false),
                    CreatedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    RecordedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    Revision = table.Column<int>(type: "integer", nullable: false),
                    CreatedAt = table.Column<long>(type: "bigint", nullable: false),
                    UpdatedAt = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicTasks", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AcademicTasks_ClassSessions_ClassSessionId",
                        column: x => x.ClassSessionId,
                        principalTable: "ClassSessions",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_AcademicTasks_Students_StudentId",
                        column: x => x.StudentId,
                        principalTable: "Students",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "AcademicMessageCommunications",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    MessageId = table.Column<Guid>(type: "uuid", nullable: false),
                    MessageRevision = table.Column<int>(type: "integer", nullable: false),
                    ConfirmedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    RequestKey = table.Column<string>(type: "text", nullable: false),
                    RecipientLabel = table.Column<string>(type: "text", nullable: false),
                    Channel = table.Column<string>(type: "text", nullable: false),
                    Body = table.Column<string>(type: "text", nullable: false),
                    SourceHash = table.Column<string>(type: "text", nullable: false),
                    ConfirmedAt = table.Column<long>(type: "bigint", nullable: false),
                    Status = table.Column<string>(type: "text", nullable: false),
                    ResponseNote = table.Column<string>(type: "text", nullable: false),
                    FollowUpOn = table.Column<string>(type: "text", nullable: true),
                    Revision = table.Column<int>(type: "integer", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicMessageCommunications", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AcademicMessageCommunications_AcademicMessages_MessageId",
                        column: x => x.MessageId,
                        principalTable: "AcademicMessages",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateIndex(
                name: "IX_AcademicAttendances_ClassSessionId_StudentId",
                table: "AcademicAttendances",
                columns: new[] { "ClassSessionId", "StudentId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AcademicAttendances_StudentId",
                table: "AcademicAttendances",
                column: "StudentId");

            migrationBuilder.CreateIndex(
                name: "IX_AcademicBetaEvents_EntityId_CreatedAt",
                table: "AcademicBetaEvents",
                columns: new[] { "EntityId", "CreatedAt" });

            migrationBuilder.CreateIndex(
                name: "IX_AcademicHomeworkRecords_ClassSessionId_StudentId",
                table: "AcademicHomeworkRecords",
                columns: new[] { "ClassSessionId", "StudentId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AcademicHomeworkRecords_StudentId",
                table: "AcademicHomeworkRecords",
                column: "StudentId");

            migrationBuilder.CreateIndex(
                name: "IX_AcademicHomeworks_ClassSessionId",
                table: "AcademicHomeworks",
                column: "ClassSessionId",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AcademicMessageCommunications_ConfirmedBy_RequestKey",
                table: "AcademicMessageCommunications",
                columns: new[] { "ConfirmedBy", "RequestKey" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AcademicMessageCommunications_MessageId_MessageRevision",
                table: "AcademicMessageCommunications",
                columns: new[] { "MessageId", "MessageRevision" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AcademicMessages_CreatedBy_RequestKey",
                table: "AcademicMessages",
                columns: new[] { "CreatedBy", "RequestKey" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AcademicOperationEvents_ClassSessionId_CreatedAt",
                table: "AcademicOperationEvents",
                columns: new[] { "ClassSessionId", "CreatedAt" });

            migrationBuilder.CreateIndex(
                name: "IX_AcademicPractices_ClassSessionId_StudentId_QuestionId",
                table: "AcademicPractices",
                columns: new[] { "ClassSessionId", "StudentId", "QuestionId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AcademicPreparations_ClassSessionId",
                table: "AcademicPreparations",
                column: "ClassSessionId",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AcademicTasks_ClassSessionId",
                table: "AcademicTasks",
                column: "ClassSessionId");

            migrationBuilder.CreateIndex(
                name: "IX_AcademicTasks_CreatedBy_RequestKey",
                table: "AcademicTasks",
                columns: new[] { "CreatedBy", "RequestKey" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AcademicTasks_StudentId",
                table: "AcademicTasks",
                column: "StudentId");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "AcademicAttendances");

            migrationBuilder.DropTable(
                name: "AcademicBetaEvents");

            migrationBuilder.DropTable(
                name: "AcademicHomeworkRecords");

            migrationBuilder.DropTable(
                name: "AcademicHomeworks");

            migrationBuilder.DropTable(
                name: "AcademicMessageCommunications");

            migrationBuilder.DropTable(
                name: "AcademicOperationEvents");

            migrationBuilder.DropTable(
                name: "AcademicPractices");

            migrationBuilder.DropTable(
                name: "AcademicPreparations");

            migrationBuilder.DropTable(
                name: "AcademicTasks");

            migrationBuilder.DropTable(
                name: "AcademicMessages");
        }
    }
}
