using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;
namespace Siyue.Server.Migrations;
[DbContext(typeof(Data))]
[Migration("20261104070000_ClassCreationOwner")]
public class ClassCreationOwner : Migration {
 protected override void Up(MigrationBuilder m) {
  m.AddColumn<Guid>(name:"OwnerStaffId",table:"Classes",type:"uuid",nullable:true);
  m.CreateIndex(name:"IX_Classes_OwnerStaffId",table:"Classes",column:"OwnerStaffId");
  m.AddForeignKey(name:"FK_Classes_Staff_OwnerStaffId",table:"Classes",column:"OwnerStaffId",principalTable:"Staff",principalColumn:"Id",onDelete:ReferentialAction.Restrict);
 }
 protected override void Down(MigrationBuilder m) {
  m.DropForeignKey(name:"FK_Classes_Staff_OwnerStaffId",table:"Classes");
  m.DropIndex(name:"IX_Classes_OwnerStaffId",table:"Classes");
  m.DropColumn(name:"OwnerStaffId",table:"Classes");
 }
}
