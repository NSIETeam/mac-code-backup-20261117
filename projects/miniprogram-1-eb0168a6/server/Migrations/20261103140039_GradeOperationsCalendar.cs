using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Siyue.Server.Migrations
{
    /// <inheritdoc />
    public partial class GradeOperationsCalendar : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "GradeCalendarEvents",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    Grade = table.Column<string>(type: "text", nullable: false),
                    TemplateId = table.Column<Guid>(type: "uuid", nullable: false),
                    Anchor = table.Column<string>(type: "text", nullable: false),
                    StepIndex = table.Column<int>(type: "integer", nullable: false),
                    Date = table.Column<string>(type: "text", nullable: false),
                    Action = table.Column<string>(type: "text", nullable: false),
                    Done = table.Column<bool>(type: "boolean", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_GradeCalendarEvents", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "GradeCalendarOwners",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    Grade = table.Column<string>(type: "text", nullable: false),
                    StaffId = table.Column<Guid>(type: "uuid", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_GradeCalendarOwners", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "GradeCalendarTemplates",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    Title = table.Column<string>(type: "text", nullable: false),
                    StepsJson = table.Column<string>(type: "text", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_GradeCalendarTemplates", x => x.Id);
                });

            migrationBuilder.CreateIndex(
                name: "IX_GradeCalendarEvents_Grade_Date",
                table: "GradeCalendarEvents",
                columns: new[] { "Grade", "Date" });

            migrationBuilder.CreateIndex(
                name: "IX_GradeCalendarEvents_Grade_TemplateId_Anchor_StepIndex",
                table: "GradeCalendarEvents",
                columns: new[] { "Grade", "TemplateId", "Anchor", "StepIndex" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_GradeCalendarOwners_Grade_StaffId",
                table: "GradeCalendarOwners",
                columns: new[] { "Grade", "StaffId" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "GradeCalendarEvents");

            migrationBuilder.DropTable(
                name: "GradeCalendarOwners");

            migrationBuilder.DropTable(
                name: "GradeCalendarTemplates");
        }
    }
}
