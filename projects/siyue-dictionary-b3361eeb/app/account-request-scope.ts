export type AccountRequestToken = { generation: number; employeeId: string };

export class AccountRequestScope {
  private generation = 0;
  private employeeId = "";
  private accessKey = "";
  capture(): AccountRequestToken { return { generation: this.generation, employeeId: this.employeeId }; }
  isCurrent(token: AccountRequestToken) { return token.generation === this.generation && token.employeeId === this.employeeId; }
  identify(employeeId: string, accessKey = "") {
    if (employeeId === this.employeeId && accessKey === this.accessKey) return false;
    this.generation += 1; this.employeeId = employeeId; this.accessKey = accessKey; return true;
  }
  invalidate() { this.generation += 1; this.employeeId = ""; this.accessKey = ""; }
}
