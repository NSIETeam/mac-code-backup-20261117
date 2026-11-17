export interface Execution { jobId: string; phase: string; detail: string; processId?: number; exitCode?: number; windowsJobIds?: number[]; pagesPrinted?: number; updatedAt: string }
export const executionLabels: Record<string,string> = { preparing:'正在准备打印',process_started:'校区电脑已启动打印',queued:'已进入 Windows 队列',printing:'Windows 正在处理',blocked:'打印作业异常',awaiting_result:'等待系统完成回执',system_completed:'Windows 报告完成',failed:'打印进程启动失败',uncertain:'打印结果待核实' }
export function readExecution(json?: string|null): Execution|null { try { const x=JSON.parse(json||'null');return x && typeof x.jobId==='string' && typeof x.phase==='string' && executionLabels[x.phase] ? x : null } catch { return null } }
export function executionActive(x: Execution|null): boolean { return !!x && ['system_completed','failed','uncertain'].indexOf(x.phase)<0 }
export function executionLabel(x: Execution|null, monitorLastSeen?: string, now=Date.now()): string {
 if(!x)return ''
 if(executionActive(x) && (!monitorLastSeen || !Number.isFinite(Date.parse(monitorLastSeen)) || now-Date.parse(monitorLastSeen)>30000))return '监控状态待更新，请勿重复打印'
 return executionLabels[x.phase]
}
