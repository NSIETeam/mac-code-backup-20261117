using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Siyue.Server.Migrations
{
    /// <inheritdoc />
    public partial class AcademicParentProgress : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<long>(
                name: "FirstSubmittedAt",
                table: "AcademicSubmissions",
                type: "bigint",
                nullable: true);

            migrationBuilder.AddColumn<Guid>(
                name: "ParentBindingId",
                table: "AcademicSubmissions",
                type: "uuid",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "ParentKey",
                table: "AcademicSubmissions",
                type: "text",
                nullable: true);

            migrationBuilder.CreateTable(
                name: "AcademicGradingJobs",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    SubmissionId = table.Column<Guid>(type: "uuid", nullable: false),
                    SourceHash = table.Column<string>(type: "text", nullable: false),
                    ReferenceAnswer = table.Column<string>(type: "text", nullable: false),
                    State = table.Column<string>(type: "text", nullable: false),
                    ResultJson = table.Column<string>(type: "text", nullable: false),
                    Revision = table.Column<int>(type: "integer", nullable: false),
                    CreatedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    ReviewedBy = table.Column<Guid>(type: "uuid", nullable: true),
                    CreatedAt = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicGradingJobs", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AcademicGradingJobs_AcademicSubmissions_SubmissionId",
                        column: x => x.SubmissionId,
                        principalTable: "AcademicSubmissions",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "AcademicParentBindings",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ParentKey = table.Column<string>(type: "text", nullable: false),
                    ClassId = table.Column<Guid>(type: "uuid", nullable: false),
                    StudentId = table.Column<Guid>(type: "uuid", nullable: true),
                    ChildName = table.Column<string>(type: "text", nullable: false),
                    Relationship = table.Column<string>(type: "text", nullable: false),
                    CheckCode = table.Column<string>(type: "text", nullable: false),
                    State = table.Column<string>(type: "text", nullable: false),
                    Revision = table.Column<int>(type: "integer", nullable: false),
                    VerifiedBy = table.Column<Guid>(type: "uuid", nullable: true),
                    CreatedAt = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicParentBindings", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AcademicParentBindings_Classes_ClassId",
                        column: x => x.ClassId,
                        principalTable: "Classes",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "AcademicParentSessions",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ParentKey = table.Column<string>(type: "text", nullable: false),
                    TokenHash = table.Column<string>(type: "text", nullable: false),
                    ExpiresAt = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicParentSessions", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "AcademicStageProgress",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    StudentId = table.Column<Guid>(type: "uuid", nullable: false),
                    ClassId = table.Column<Guid>(type: "uuid", nullable: false),
                    CreatedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    Title = table.Column<string>(type: "text", nullable: false),
                    From = table.Column<string>(type: "text", nullable: false),
                    To = table.Column<string>(type: "text", nullable: false),
                    Problem = table.Column<string>(type: "text", nullable: false),
                    Evidence = table.Column<string>(type: "text", nullable: false),
                    Adjustment = table.Column<string>(type: "text", nullable: false),
                    Result = table.Column<string>(type: "text", nullable: false),
                    ActionState = table.Column<string>(type: "text", nullable: false),
                    NextReviewOn = table.Column<string>(type: "text", nullable: false),
                    State = table.Column<string>(type: "text", nullable: false),
                    Revision = table.Column<int>(type: "integer", nullable: false),
                    ApprovedBy = table.Column<Guid>(type: "uuid", nullable: true),
                    PublishedAt = table.Column<long>(type: "bigint", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicStageProgress", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AcademicStageProgress_Students_StudentId",
                        column: x => x.StudentId,
                        principalTable: "Students",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateIndex(
                name: "IX_AcademicGradingJobs_SubmissionId_SourceHash",
                table: "AcademicGradingJobs",
                columns: new[] { "SubmissionId", "SourceHash" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AcademicParentBindings_ClassId",
                table: "AcademicParentBindings",
                column: "ClassId");

            migrationBuilder.CreateIndex(
                name: "IX_AcademicParentBindings_ParentKey_ClassId_ChildName",
                table: "AcademicParentBindings",
                columns: new[] { "ParentKey", "ClassId", "ChildName" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AcademicParentSessions_TokenHash",
                table: "AcademicParentSessions",
                column: "TokenHash",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AcademicStageProgress_StudentId_From_To",
                table: "AcademicStageProgress",
                columns: new[] { "StudentId", "From", "To" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "AcademicGradingJobs");

            migrationBuilder.DropTable(
                name: "AcademicParentBindings");

            migrationBuilder.DropTable(
                name: "AcademicParentSessions");

            migrationBuilder.DropTable(
                name: "AcademicStageProgress");

            migrationBuilder.DropColumn(
                name: "FirstSubmittedAt",
                table: "AcademicSubmissions");

            migrationBuilder.DropColumn(
                name: "ParentBindingId",
                table: "AcademicSubmissions");

            migrationBuilder.DropColumn(
                name: "ParentKey",
                table: "AcademicSubmissions");
        }
    }
}
