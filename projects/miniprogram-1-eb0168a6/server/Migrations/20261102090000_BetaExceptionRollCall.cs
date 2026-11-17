using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Siyue.Server.Migrations
{
    public partial class BetaExceptionRollCall : Migration
    {
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<Guid>(name: "TransferClassId", table: "AcademicAttendances", type: "uuid", nullable: true);
            migrationBuilder.AddColumn<string>(name: "TransferOn", table: "AcademicAttendances", type: "text", nullable: true);
            migrationBuilder.CreateIndex(name: "IX_AcademicAttendances_TransferClassId_TransferOn", table: "AcademicAttendances", columns: new[] { "TransferClassId", "TransferOn" });
        }

        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(name: "IX_AcademicAttendances_TransferClassId_TransferOn", table: "AcademicAttendances");
            migrationBuilder.DropColumn(name: "TransferClassId", table: "AcademicAttendances");
            migrationBuilder.DropColumn(name: "TransferOn", table: "AcademicAttendances");
        }
    }
}
