using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Siyue.Server.Migrations
{
    /// <inheritdoc />
    public partial class GuangzhouPrintingAdapter : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "GuangzhouDevices",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    QueueId = table.Column<Guid>(type: "uuid", nullable: false),
                    CampusNumber = table.Column<long>(type: "bigint", nullable: false),
                    BindingHash = table.Column<string>(type: "text", nullable: false),
                    ExpiresAt = table.Column<long>(type: "bigint", nullable: false),
                    Enrolled = table.Column<bool>(type: "boolean", nullable: false),
                    Availability = table.Column<string>(type: "text", nullable: false),
                    Revision = table.Column<int>(type: "integer", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_GuangzhouDevices", x => x.Id);
                    table.ForeignKey(
                        name: "FK_GuangzhouDevices_Devices_Id",
                        column: x => x.Id,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "GuangzhouJobs",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    State = table.Column<string>(type: "text", nullable: false),
                    PdfKey = table.Column<string>(type: "text", nullable: false),
                    Hash = table.Column<string>(type: "text", nullable: false),
                    Bytes = table.Column<long>(type: "bigint", nullable: false),
                    Pages = table.Column<int>(type: "integer", nullable: false),
                    ExecutionKey = table.Column<Guid>(type: "uuid", nullable: true),
                    EvidenceJson = table.Column<string>(type: "text", nullable: true),
                    ResolutionJson = table.Column<string>(type: "text", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_GuangzhouJobs", x => x.Id);
                    table.ForeignKey(
                        name: "FK_GuangzhouJobs_PrintJobs_Id",
                        column: x => x.Id,
                        principalTable: "PrintJobs",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateIndex(
                name: "IX_GuangzhouDevices_BindingHash",
                table: "GuangzhouDevices",
                column: "BindingHash",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_GuangzhouDevices_QueueId",
                table: "GuangzhouDevices",
                column: "QueueId",
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "GuangzhouDevices");

            migrationBuilder.DropTable(
                name: "GuangzhouJobs");
        }
    }
}
