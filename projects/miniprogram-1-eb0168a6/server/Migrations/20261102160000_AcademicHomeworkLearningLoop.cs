using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Siyue.Server.Migrations
{
    /// <inheritdoc />
    public partial class AcademicHomeworkLearningLoop : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<long>(
                name: "AttemptedAt",
                table: "AcademicGradingJobs",
                type: "bigint",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "Error",
                table: "AcademicGradingJobs",
                type: "text",
                nullable: false,
                defaultValue: "");

            migrationBuilder.CreateTable(
                name: "AcademicHomeworkPolicies",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ReferenceAnswer = table.Column<string>(type: "text", nullable: false),
                    Subject = table.Column<string>(type: "text", nullable: false),
                    Stage = table.Column<string>(type: "text", nullable: false),
                    Enabled = table.Column<bool>(type: "boolean", nullable: false),
                    Revision = table.Column<int>(type: "integer", nullable: false),
                    UpdatedBy = table.Column<Guid>(type: "uuid", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicHomeworkPolicies", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AcademicHomeworkPolicies_AcademicCollections_Id",
                        column: x => x.Id,
                        principalTable: "AcademicCollections",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "AcademicHomeworkTrainings",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    PolicyRevision = table.Column<int>(type: "integer", nullable: false),
                    JobId = table.Column<Guid>(type: "uuid", nullable: false),
                    StudentId = table.Column<Guid>(type: "uuid", nullable: false),
                    JobRevision = table.Column<int>(type: "integer", nullable: false),
                    SourceHash = table.Column<string>(type: "text", nullable: false),
                    BodyJson = table.Column<string>(type: "text", nullable: false),
                    State = table.Column<string>(type: "text", nullable: false),
                    Revision = table.Column<int>(type: "integer", nullable: false),
                    CreatedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    ReviewedBy = table.Column<Guid>(type: "uuid", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicHomeworkTrainings", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AcademicHomeworkTrainings_AcademicGradingJobs_JobId",
                        column: x => x.JobId,
                        principalTable: "AcademicGradingJobs",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateIndex(
                name: "IX_AcademicHomeworkTrainings_JobId_SourceHash",
                table: "AcademicHomeworkTrainings",
                columns: new[] { "JobId", "SourceHash" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "AcademicHomeworkPolicies");

            migrationBuilder.DropTable(
                name: "AcademicHomeworkTrainings");

            migrationBuilder.DropColumn(
                name: "AttemptedAt",
                table: "AcademicGradingJobs");

            migrationBuilder.DropColumn(
                name: "Error",
                table: "AcademicGradingJobs");
        }
    }
}
