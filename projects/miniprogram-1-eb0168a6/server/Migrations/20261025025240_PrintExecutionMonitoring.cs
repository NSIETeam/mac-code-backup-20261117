using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Siyue.Server.Migrations
{
    /// <inheritdoc />
    public partial class PrintExecutionMonitoring : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<long>(
                name: "ExecutionAt",
                table: "PrintJobs",
                type: "bigint",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "ExecutionJson",
                table: "PrintJobs",
                type: "text",
                nullable: true);

            migrationBuilder.AddColumn<long>(
                name: "DeviceCheckedAt",
                table: "Printers",
                type: "bigint",
                nullable: true);

            migrationBuilder.AddColumn<bool>(
                name: "DeviceReachable",
                table: "Printers",
                type: "boolean",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "MonitorError",
                table: "Devices",
                type: "text",
                nullable: true);

            migrationBuilder.AddColumn<long>(
                name: "MonitorLastSeen",
                table: "Devices",
                type: "bigint",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "MonitorVersion",
                table: "Devices",
                type: "text",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "ExecutionAt",
                table: "PrintJobs");

            migrationBuilder.DropColumn(
                name: "ExecutionJson",
                table: "PrintJobs");

            migrationBuilder.DropColumn(
                name: "DeviceCheckedAt",
                table: "Printers");

            migrationBuilder.DropColumn(
                name: "DeviceReachable",
                table: "Printers");

            migrationBuilder.DropColumn(
                name: "MonitorError",
                table: "Devices");

            migrationBuilder.DropColumn(
                name: "MonitorLastSeen",
                table: "Devices");

            migrationBuilder.DropColumn(
                name: "MonitorVersion",
                table: "Devices");
        }
    }
}
