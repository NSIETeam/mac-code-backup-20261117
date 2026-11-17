using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Siyue.Server.Migrations
{
    /// <inheritdoc />
    public partial class StudentFollowUps : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "StudentFollowUps",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ClassId = table.Column<Guid>(type: "uuid", nullable: false),
                    StudentId = table.Column<Guid>(type: "uuid", nullable: false),
                    StaffId = table.Column<Guid>(type: "uuid", nullable: false),
                    StaffName = table.Column<string>(type: "text", nullable: false),
                    Note = table.Column<string>(type: "text", nullable: false),
                    RequestKey = table.Column<string>(type: "text", nullable: false),
                    CreatedAt = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_StudentFollowUps", x => x.Id);
                });

            migrationBuilder.CreateIndex(
                name: "IX_StudentFollowUps_ClassId_StudentId_CreatedAt",
                table: "StudentFollowUps",
                columns: new[] { "ClassId", "StudentId", "CreatedAt" });

            migrationBuilder.CreateIndex(
                name: "IX_StudentFollowUps_ClassId_StudentId_StaffId_RequestKey",
                table: "StudentFollowUps",
                columns: new[] { "ClassId", "StudentId", "StaffId", "RequestKey" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "StudentFollowUps");
        }
    }
}
