using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Siyue.Server.Migrations
{
    /// <inheritdoc />
    public partial class AcademicHomeworkCollection : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "AcademicCollections",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    BatchId = table.Column<Guid>(type: "uuid", nullable: false),
                    ClassId = table.Column<Guid>(type: "uuid", nullable: false),
                    CreatedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    Title = table.Column<string>(type: "text", nullable: false),
                    Instructions = table.Column<string>(type: "text", nullable: false),
                    DueOn = table.Column<string>(type: "text", nullable: false),
                    State = table.Column<string>(type: "text", nullable: false),
                    Revision = table.Column<int>(type: "integer", nullable: false),
                    RosterJson = table.Column<string>(type: "text", nullable: false),
                    ApprovedBy = table.Column<Guid>(type: "uuid", nullable: true),
                    ApprovedAt = table.Column<long>(type: "bigint", nullable: true),
                    CreatedAt = table.Column<long>(type: "bigint", nullable: false),
                    RequestKey = table.Column<string>(type: "text", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicCollections", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AcademicCollections_Classes_ClassId",
                        column: x => x.ClassId,
                        principalTable: "Classes",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "AcademicSubmissions",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    CollectionId = table.Column<Guid>(type: "uuid", nullable: false),
                    Name = table.Column<string>(type: "text", nullable: false),
                    StudentId = table.Column<Guid>(type: "uuid", nullable: true),
                    Revision = table.Column<int>(type: "integer", nullable: false),
                    RequestKey = table.Column<string>(type: "text", nullable: false),
                    ContentHash = table.Column<string>(type: "text", nullable: false),
                    SubmittedAt = table.Column<long>(type: "bigint", nullable: false),
                    CheckedBy = table.Column<Guid>(type: "uuid", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicSubmissions", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AcademicSubmissions_AcademicCollections_CollectionId",
                        column: x => x.CollectionId,
                        principalTable: "AcademicCollections",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "AcademicSubmissionPhotos",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    SubmissionId = table.Column<Guid>(type: "uuid", nullable: false),
                    Position = table.Column<int>(type: "integer", nullable: false),
                    ContentType = table.Column<string>(type: "text", nullable: false),
                    Bytes = table.Column<byte[]>(type: "bytea", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicSubmissionPhotos", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AcademicSubmissionPhotos_AcademicSubmissions_SubmissionId",
                        column: x => x.SubmissionId,
                        principalTable: "AcademicSubmissions",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_AcademicCollections_ClassId",
                table: "AcademicCollections",
                column: "ClassId");

            migrationBuilder.CreateIndex(
                name: "IX_AcademicCollections_CreatedBy_RequestKey_ClassId",
                table: "AcademicCollections",
                columns: new[] { "CreatedBy", "RequestKey", "ClassId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AcademicSubmissionPhotos_SubmissionId",
                table: "AcademicSubmissionPhotos",
                column: "SubmissionId");

            migrationBuilder.CreateIndex(
                name: "IX_AcademicSubmissions_CollectionId_StudentId",
                table: "AcademicSubmissions",
                columns: new[] { "CollectionId", "StudentId" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "AcademicSubmissionPhotos");

            migrationBuilder.DropTable(
                name: "AcademicSubmissions");

            migrationBuilder.DropTable(
                name: "AcademicCollections");
        }
    }
}
