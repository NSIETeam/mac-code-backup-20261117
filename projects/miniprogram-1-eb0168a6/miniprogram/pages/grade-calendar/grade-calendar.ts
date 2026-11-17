import { api, confirm, Named, run, token } from '../../services/api'

interface Step { offset: number; action: string }
interface Template { id: string; title: string; steps: Step[] }
interface PreviewStep extends Step { date: string }
interface CalendarEvent { id: string; date: string; action: string; done: boolean }
interface Owner { grade: string; staffId: string; name: string }
interface Catalog { grades: string[]; templates: Template[]; owners: Owner[]; canManage: boolean }
interface Staff extends Named { enabled: boolean; role: string }

const today = () => new Date(Date.now() + 8 * 3600000).toISOString().slice(0, 10)
const root = '/grade-calendar'
const dayMs = 86400000

function calendarDate(value: string): number {
  if (!/^20\d{2}-\d{2}-\d{2}$/.test(value)) return NaN
  const time = Date.parse(value + 'T00:00:00Z')
  return Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === value ? time : NaN
}

Page({
  data: {
    catalog: null as Catalog | null, gradeIndex: -1, templateIndex: -1, staffIndex: -1,
    events: [] as CalendarEvent[], preview: [] as PreviewStep[], previewError: '', staff: [] as Staff[], owners: [] as Owner[],
    month: today().slice(0, 7), anchor: today(), busy: false, loading: false,
    manage: false, title: '', steps: '', templateAnchor: today()
  },
  identity: '', epoch: 0,
  onLoad() {
    this.identity = token()
    if (!this.identity) { wx.reLaunch({ url: '/pages/login/login' }); return }
    return run(async () => {
      await this.loadCatalog()
      await this.loadEvents()
    })
  },
  onUnload() { this.identity = ''; this.epoch++ },
  onShow() {
    if (this.identity && this.identity !== token()) {
      this.setData({ catalog: null, events: [], owners: [], staff: [] })
      wx.reLaunch({ url: '/pages/login/login' })
    }
  },
  grade() { return this.data.catalog?.grades[this.data.gradeIndex] || '' },
  gradePath() { return root + '/' + encodeURIComponent(this.grade()) },
  async loadCatalog() {
    const grade = this.grade()
    const templateId = this.data.catalog?.templates[this.data.templateIndex]?.id
    const catalog = await api<Catalog>(root)
    if (this.identity !== token()) return
    const gradeIndex = catalog.grades.includes(grade) ? catalog.grades.indexOf(grade) : catalog.grades.length ? 0 : -1
    this.setData({ catalog, gradeIndex, templateIndex: catalog.templates.findIndex(template => template.id === templateId), owners: catalog.owners.filter(owner => owner.grade === catalog.grades[gradeIndex]) })
    this.updatePreview()
  },
  async loadEvents() {
    const epoch = ++this.epoch
    if (!this.grade()) return
    this.setData({ events: [], loading: true })
    try {
      const events = await api<CalendarEvent[]>(this.gradePath() + '/events?month=' + this.data.month)
      if (this.identity === token() && this.epoch === epoch) this.setData({ events })
    } finally { if (epoch === this.epoch) this.setData({ loading: false }) }
  },
  selectGrade(e: WechatMiniprogram.PickerChange) {
    const gradeIndex = Number(e.detail.value)
    const catalog = this.data.catalog
    if (this.data.busy || !catalog?.grades[gradeIndex]) return
    this.setData({ gradeIndex, owners: catalog.owners.filter(owner => owner.grade === catalog.grades[gradeIndex]) })
    return run(() => this.loadEvents())
  },
  selectMonth(e: WechatMiniprogram.PickerChange) {
    if (this.data.busy) return
    this.setData({ month: String(e.detail.value) })
    return run(() => this.loadEvents())
  },
  anchor(e: WechatMiniprogram.PickerChange) {
    this.setData({ anchor: String(e.detail.value) })
    this.updatePreview()
  },
  templateAnchor(e: WechatMiniprogram.PickerChange) { this.setData({ templateAnchor: String(e.detail.value) }) },
  template(e: WechatMiniprogram.PickerChange) {
    this.setData({ templateIndex: Number(e.detail.value) })
    this.updatePreview()
  },
  updatePreview() {
    const template = this.data.catalog?.templates[this.data.templateIndex]
    if (!template) { this.setData({ preview: [], previewError: '' }); return }
    const anchor = calendarDate(this.data.anchor)
    const preview = Number.isFinite(anchor) ? template.steps.map(step => ({
      ...step, date: new Date(anchor + step.offset * dayMs).toISOString().slice(0, 10)
    })).sort((a, b) => a.date.localeCompare(b.date)) : []
    if (!preview.length || preview.some(step => !Number.isFinite(calendarDate(step.date)))) {
      this.setData({ preview: [], previewError: '生成日期超出可用范围，请调整基准日期。' })
      return
    }
    this.setData({ preview, previewError: '' })
  },
  owner(e: WechatMiniprogram.PickerChange) { this.setData({ staffIndex: Number(e.detail.value) }) },
  title(e: WechatMiniprogram.Input) { this.setData({ title: e.detail.value }) },
  steps(e: WechatMiniprogram.Input) { this.setData({ steps: e.detail.value }) },
  apply() {
    const template = this.data.catalog?.templates[this.data.templateIndex]
    const firstDate = this.data.preview[0]?.date
    if (!template || !this.grade() || !firstDate) return
    return this.mutate(async () => {
      const result = await api<{ added: number }>(this.gradePath() + '/events', 'POST', { templateId: template.id, anchor: this.data.anchor })
      if (this.identity !== token()) return
      this.setData({ month: firstDate.slice(0, 7) })
      wx.showToast({ title: result.added ? '已生成 ' + result.added + ' 项' : '这个日期已套用该模板', icon: 'none' })
      await this.loadEvents()
    })
  },
  changeDate(e: WechatMiniprogram.PickerChange) {
    return this.updateEvent(String(e.currentTarget.dataset.id), { date: String(e.detail.value) })
  },
  toggleDone(e: WechatMiniprogram.TouchEvent) {
    const event = this.data.events.find(row => row.id === e.currentTarget.dataset.id)
    if (event) return this.updateEvent(event.id, { done: !event.done })
    return undefined
  },
  updateEvent(id: string, patch: { date?: string; done?: boolean }) {
    return this.mutate(async () => {
      await api(this.gradePath() + '/events/' + encodeURIComponent(id), 'PUT', patch)
      await this.loadEvents()
    })
  },
  toggleManage() {
    if (!this.data.catalog?.canManage || this.data.busy) return
    if (this.data.manage) { this.setData({ manage: false }); return }
    return this.mutate(async () => {
      const catalog = await api<{ staff: Staff[] }>('/admin/catalog')
      if (this.identity === token()) this.setData({ manage: true, staff: catalog.staff.filter(staff => staff.enabled && staff.role !== 'admin') })
    })
  },
  createTemplate() {
    const title = this.data.title.trim()
    const lines = this.data.steps.split('\n').map(line => line.trim()).filter(Boolean)
    if (!title || !lines.length || lines.length > 50) {
      wx.showToast({ title: '请填写模板名和 1–50 个节点', icon: 'none' })
      return
    }
    const steps: Step[] = []
    const anchor = calendarDate(this.data.templateAnchor)
    for (const [index, line] of lines.entries()) {
      const match = /^(\S+?)\s*[|｜\t]\s*(.+)$/.exec(line)
      const value = match?.[1] || ''
      const action = match?.[2].trim() || ''
      const offset = /^[+-]?\d+$/.test(value) ? Number(value) : (calendarDate(value) - anchor) / dayMs
      if (!Number.isInteger(offset) || offset < -180 || offset > 365 || !action || action.length > 240) {
        wx.showToast({ title: '请核对第 ' + (index + 1) + ' 个节点的日期或事项', icon: 'none' })
        return
      }
      steps.push({ offset, action })
    }
    return this.mutate(async () => {
      await api(root + '/templates', 'POST', { title, steps })
      if (this.identity !== token()) return
      this.setData({ title: '', steps: '' })
      await this.loadCatalog()
    })
  },
  grant() {
    const staff = this.data.staff[this.data.staffIndex]
    if (staff) return this.changeOwner(staff.id, 'PUT')
    return undefined
  },
  revoke(e: WechatMiniprogram.TouchEvent) { return this.changeOwner(String(e.currentTarget.dataset.id), 'DELETE') },
  changeOwner(id: string, method: 'PUT' | 'DELETE') {
    if (!this.grade()) return
    return this.mutate(async () => {
      if (method === 'DELETE' && !await confirm('撤销该员工的本年级日历权限？')) return
      if (this.identity !== token()) return
      await api(this.gradePath() + '/owners/' + encodeURIComponent(id), method)
      await this.loadCatalog()
    })
  },
  mutate(action: () => Promise<void>) {
    if (this.data.busy) return
    this.setData({ busy: true })
    return run(async () => {
      try { await action() }
      finally { this.setData({ busy: false }) }
    })
  }
})
