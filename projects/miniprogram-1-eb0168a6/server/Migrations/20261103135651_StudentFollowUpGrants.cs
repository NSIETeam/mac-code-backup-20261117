using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Siyue.Server.Migrations
{
    /// <inheritdoc />
    public partial class StudentFollowUpGrants : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "StudentFollowUpGrants",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ClassId = table.Column<Guid>(type: "uuid", nullable: false),
                    StaffId = table.Column<Guid>(type: "uuid", nullable: false),
                    Department = table.Column<string>(type: "text", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_StudentFollowUpGrants", x => x.Id);
                });

            migrationBuilder.CreateIndex(
                name: "IX_StudentFollowUpGrants_ClassId_StaffId",
                table: "StudentFollowUpGrants",
                columns: new[] { "ClassId", "StaffId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_StudentFollowUpGrants_StaffId",
                table: "StudentFollowUpGrants",
                column: "StaffId");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "StudentFollowUpGrants");
        }
    }
}
