using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Siyue.Server.Migrations
{
    /// <inheritdoc />
    public partial class AcademicOnlineTesting : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "AnswersJson",
                table: "AcademicSubmissions",
                type: "text",
                nullable: false,
                defaultValue: "[]");

            migrationBuilder.AddColumn<string>(
                name: "AnswersRequestKey",
                table: "AcademicSubmissions",
                type: "text",
                nullable: false,
                defaultValue: "");

            migrationBuilder.CreateTable(
                name: "AcademicOnlineTests",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    QuestionsJson = table.Column<string>(type: "text", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AcademicOnlineTests", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AcademicOnlineTests_AcademicCollections_Id",
                        column: x => x.Id,
                        principalTable: "AcademicCollections",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "AcademicOnlineTests");

            migrationBuilder.DropColumn(
                name: "AnswersJson",
                table: "AcademicSubmissions");

            migrationBuilder.DropColumn(
                name: "AnswersRequestKey",
                table: "AcademicSubmissions");
        }
    }
}
