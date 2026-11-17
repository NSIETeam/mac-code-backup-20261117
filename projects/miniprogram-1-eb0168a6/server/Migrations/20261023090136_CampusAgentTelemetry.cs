using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Siyue.Server.Migrations
{
    /// <inheritdoc />
    public partial class CampusAgentTelemetry : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "Driver",
                table: "Printers",
                type: "text",
                nullable: false,
                defaultValue: "");

            migrationBuilder.AddColumn<int>(
                name: "JobCount",
                table: "Printers",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<string>(
                name: "Port",
                table: "Printers",
                type: "text",
                nullable: false,
                defaultValue: "");

            migrationBuilder.AddColumn<long>(
                name: "RawStatus",
                table: "Printers",
                type: "bigint",
                nullable: false,
                defaultValue: 0L);

            migrationBuilder.AddColumn<string>(
                name: "Status",
                table: "Printers",
                type: "text",
                nullable: false,
                defaultValue: "");

            migrationBuilder.AddColumn<long>(
                name: "StatusAt",
                table: "Printers",
                type: "bigint",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "StatusDetail",
                table: "Printers",
                type: "text",
                nullable: false,
                defaultValue: "");

            migrationBuilder.AddColumn<bool>(
                name: "Automatic",
                table: "Pairings",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<long>(
                name: "LastSeen",
                table: "Pairings",
                type: "bigint",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "AgentVersion",
                table: "Devices",
                type: "text",
                nullable: false,
                defaultValue: "");

            migrationBuilder.AddColumn<string>(
                name: "MachineName",
                table: "Devices",
                type: "text",
                nullable: false,
                defaultValue: "");

            migrationBuilder.AddColumn<string>(
                name: "ProbeError",
                table: "Devices",
                type: "text",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "Driver",
                table: "Printers");

            migrationBuilder.DropColumn(
                name: "JobCount",
                table: "Printers");

            migrationBuilder.DropColumn(
                name: "Port",
                table: "Printers");

            migrationBuilder.DropColumn(
                name: "RawStatus",
                table: "Printers");

            migrationBuilder.DropColumn(
                name: "Status",
                table: "Printers");

            migrationBuilder.DropColumn(
                name: "StatusAt",
                table: "Printers");

            migrationBuilder.DropColumn(
                name: "StatusDetail",
                table: "Printers");

            migrationBuilder.DropColumn(
                name: "Automatic",
                table: "Pairings");

            migrationBuilder.DropColumn(
                name: "LastSeen",
                table: "Pairings");

            migrationBuilder.DropColumn(
                name: "AgentVersion",
                table: "Devices");

            migrationBuilder.DropColumn(
                name: "MachineName",
                table: "Devices");

            migrationBuilder.DropColumn(
                name: "ProbeError",
                table: "Devices");
        }
    }
}
