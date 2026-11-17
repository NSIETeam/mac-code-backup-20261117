import { Workspace } from './api'
export interface QuickAction { id: string; title: string; detail: string; keywords: string; url?: string; intent?: 'handouts' | 'upload' | 'questions' | 'generate' | 'plan' | 'roster' | 'book' | 'history'; admin?: boolean }
export interface SearchResult { id: string; title: string; detail: string; keywords: string; url?: string }
const actions: QuickAction[] = [
 {id:'material-library',title:'讲义库',detail:'按原文件夹查看、下载共享教材与配套资料',keywords:'教材 讲义 原稿 源格式 解析 笔记 下载',url:'/pages/material-library/material-library'},
 {id:'question-bank',title:'同类题推荐',detail:'输入知识点或题目类型，返回同类题',keywords:'题库 推荐 变式 练习 知识点',url:'/pages/question-bank/question-bank'},
 {id:'accounts',title:'账号管理',detail:'创建教师和管理员（含店长）账号',keywords:'账号 创建 老师 店长 管理员',url:'/pages/admin/admin?kind=staff',admin:true},
 {id:'devices',title:'校区打印设备',detail:'管理校区及接入打印助手',keywords:'广州 打印机 绑定 接入 校区',url:'/pages/admin/admin?kind=devices',admin:true},
 {id:'integrations',title:'外部接入',detail:'模型与 PDF 解析辅助配置',keywords:'模型 接口 密钥 Kimi Moonshot',url:'/pages/model-settings/model-settings',admin:true},
 {id:'manual',title:'操作手册',detail:'电脑端与小程序端图文说明',keywords:'帮助 使用 说明 下载 pdf',url:'/pages/manual/manual'},
 {id:'sessions',title:'课堂标记',detail:'选择课次与学生，点题标错',keywords:'班级 学生 讲次 错题',url:'/pages/home/home'},
 {id:'new-session',title:'新建课次',detail:'选择班级、讲次和日期',keywords:'开课 上课',url:'/pages/session-setup/session-setup'},
 {id:'exports',title:'打印预览',detail:'查看已生成的错题集',keywords:'文档 下载 pdf 打印',url:'/pages/export/export'},
 {id:'jobs',title:'打印记录',detail:'查看任务进度与补打',keywords:'排队 任务 失败 补打',url:'/pages/jobs/jobs'}
]
export function availableActions(role: string): QuickAction[] { return actions.filter(action => !action.admin || role === 'admin') }
export function matchingResults<T extends SearchResult>(items: T[], query: string): T[] {
  const tokens = query.toLocaleLowerCase().trim().split(/\s+/).filter(Boolean)
  return items.filter(item => tokens.every(token => (item.title + ' ' + item.detail + ' ' + item.keywords).toLocaleLowerCase().includes(token)))
}
export function classroomResults(workspace: Workspace, action: QuickAction): SearchResult[] {
  const results: SearchResult[] = []
  for (const cl of workspace.classes) for (const course of workspace.courses) {
    if (workspace.user.role !== 'admin' && !workspace.assignments.some(a => a.staffId === workspace.user.id && a.classId === cl.id && a.courseId === course.id)) continue
    const campus = workspace.campuses.find(c => c.id === cl.campusId)?.name || ''
    const query = 'classId=' + encodeURIComponent(cl.id) + '&courseId=' + encodeURIComponent(course.id)
    const page = action.intent === 'book' ? '/pages/book-import/book-import?' : action.intent === 'history' ? '/pages/history/history?' : action.intent === 'roster' ? '/pages/roster/roster?' : action.intent === 'questions' ? '/pages/questions/questions?' : '/pages/classroom/classroom?'
    const extra = action.intent === 'generate' ? '&action=roster' : action.intent === 'plan' ? '&action=new-plan' : action.intent === 'upload' ? '&action=materials' : ''
    results.push({ id: cl.id + ':' + course.id, title: cl.name, detail: course.name + (campus ? ' · ' + campus : ''), keywords: campus + ' ' + course.name, url: page + query + extra })
  }
  return results
}
