using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Siyue.Server.Migrations
{
    /// <inheritdoc />
    public partial class TeacherWorkspaceAndFilePrinting : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<bool>(
                name: "Archived",
                table: "Staff",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<bool>(
                name: "Suppressed",
                table: "Printers",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AlterColumn<Guid>(
                name: "StudentId",
                table: "Documents",
                type: "uuid",
                nullable: true,
                oldClrType: typeof(Guid),
                oldType: "uuid");

            migrationBuilder.AlterColumn<Guid>(
                name: "CourseId",
                table: "Documents",
                type: "uuid",
                nullable: true,
                oldClrType: typeof(Guid),
                oldType: "uuid");

            migrationBuilder.AlterColumn<Guid>(
                name: "ClassId",
                table: "Documents",
                type: "uuid",
                nullable: true,
                oldClrType: typeof(Guid),
                oldType: "uuid");

            migrationBuilder.AddColumn<bool>(
                name: "Archived",
                table: "Documents",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<string>(
                name: "Kind",
                table: "Documents",
                type: "text",
                nullable: false,
                defaultValue: "booklet");

            migrationBuilder.AddColumn<Guid>(
                name: "LectureId",
                table: "Documents",
                type: "uuid",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "MaterialKind",
                table: "Documents",
                type: "text",
                nullable: false,
                defaultValue: "");

            migrationBuilder.AddColumn<string>(
                name: "SourceFileName",
                table: "Documents",
                type: "text",
                nullable: false,
                defaultValue: "");

            migrationBuilder.AddColumn<string>(
                name: "SourceKey",
                table: "Documents",
                type: "text",
                nullable: false,
                defaultValue: "");

            migrationBuilder.AddColumn<bool>(
                name: "Archived",
                table: "Courses",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<bool>(
                name: "Archived",
                table: "Classes",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<bool>(
                name: "Archived",
                table: "Campuses",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.CreateTable(
                name: "WrongSettings",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    StudentId = table.Column<Guid>(type: "uuid", nullable: false),
                    LectureId = table.Column<Guid>(type: "uuid", nullable: false),
                    HandoutId = table.Column<Guid>(type: "uuid", nullable: false),
                    RecordedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    RecordedAt = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_WrongSettings", x => x.Id);
                    table.ForeignKey(
                        name: "FK_WrongSettings_Lectures_LectureId",
                        column: x => x.LectureId,
                        principalTable: "Lectures",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_WrongSettings_Students_StudentId",
                        column: x => x.StudentId,
                        principalTable: "Students",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateIndex(
                name: "IX_WrongSettings_LectureId",
                table: "WrongSettings",
                column: "LectureId");

            migrationBuilder.CreateIndex(
                name: "IX_WrongSettings_StudentId_LectureId",
                table: "WrongSettings",
                columns: new[] { "StudentId", "LectureId" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "WrongSettings");

            migrationBuilder.DropColumn(
                name: "Archived",
                table: "Staff");

            migrationBuilder.DropColumn(
                name: "Suppressed",
                table: "Printers");

            migrationBuilder.DropColumn(
                name: "Archived",
                table: "Documents");

            migrationBuilder.DropColumn(
                name: "Kind",
                table: "Documents");

            migrationBuilder.DropColumn(
                name: "LectureId",
                table: "Documents");

            migrationBuilder.DropColumn(
                name: "MaterialKind",
                table: "Documents");

            migrationBuilder.DropColumn(
                name: "SourceFileName",
                table: "Documents");

            migrationBuilder.DropColumn(
                name: "SourceKey",
                table: "Documents");

            migrationBuilder.DropColumn(
                name: "Archived",
                table: "Courses");

            migrationBuilder.DropColumn(
                name: "Archived",
                table: "Classes");

            migrationBuilder.DropColumn(
                name: "Archived",
                table: "Campuses");

            migrationBuilder.AlterColumn<Guid>(
                name: "StudentId",
                table: "Documents",
                type: "uuid",
                nullable: false,
                defaultValue: new Guid("00000000-0000-0000-0000-000000000000"),
                oldClrType: typeof(Guid),
                oldType: "uuid",
                oldNullable: true);

            migrationBuilder.AlterColumn<Guid>(
                name: "CourseId",
                table: "Documents",
                type: "uuid",
                nullable: false,
                defaultValue: new Guid("00000000-0000-0000-0000-000000000000"),
                oldClrType: typeof(Guid),
                oldType: "uuid",
                oldNullable: true);

            migrationBuilder.AlterColumn<Guid>(
                name: "ClassId",
                table: "Documents",
                type: "uuid",
                nullable: false,
                defaultValue: new Guid("00000000-0000-0000-0000-000000000000"),
                oldClrType: typeof(Guid),
                oldType: "uuid",
                oldNullable: true);
        }
    }
}
