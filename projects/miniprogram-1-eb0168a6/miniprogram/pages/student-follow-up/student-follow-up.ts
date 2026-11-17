import { api, confirm, Named, requestKey, run, token, user } from '../../services/api'

interface FollowUp {
  recent: { lessonDate: string; reviewed: boolean; wrongCount: number | null }[]
  notes: { id: string; staffName: string; note: string; createdAt: string; dueOn: string; resolvedAt: string | null }[]
}

interface Grant { id: string; staffId: string; name: string; department: string }
interface EligibleStaff extends Named { enabled: boolean; role: string }

interface FollowUpAlert { id: string; studentId: string; studentName: string; dueOn: string; kind?: string; count?: number }
const chinaToday = () => new Date(Date.now() + 8 * 3600000).toISOString().slice(0, 10)

interface SpeechManager {
  start(options: { lang: string; duration: number }): void
  stop(): void
  onStop: (result: { result?: string }) => void
  onError: () => void
}

Page({
  data: {
    canGrant: false, grantsOpen: false, grantsBusy: false, grants: [] as Grant[],
    eligibleStaff: [] as EligibleStaff[], staffIndex: -1, departmentIndex: -1, departments: ['教务', '教研', '招生'],
    classes: [] as Named[], students: [] as Named[], classIndex: -1, studentIndex: -1,
    summary: null as FollowUp | null, note: '', dueOn: '', alerts: [] as (FollowUpAlert & { status: string })[], loading: false, saving: false,
    voice: '' as '' | 'permission' | 'recording' | 'finishing', voiceHint: '', voiceAvailable: false
  },
  owner: '', epoch: 0, key: '', manager: null as SpeechManager | null,
  async onLoad(query: Record<string, string>) {
    this.owner = token()
    if (!this.owner) { wx.reLaunch({ url: '/pages/login/login' }); return }
    this.setData({ voiceAvailable: !!this.speechManager() })
    return run(async () => {
      const classes = await api<Named[]>('/student-follow-up/classes')
      if (token() !== this.owner) return
      this.setData({ classes, canGrant: user()?.role === 'admin' })
      const index = classes.findIndex(item => item.id === query.classId)
      if (index >= 0) await this.selectClass(index)
    })
  },
  onHide() { this.cancelVoice() },
  onUnload() { this.epoch++; this.owner = ''; this.cancelVoice() },
  onShow() {
    if (this.owner && this.owner !== token()) {
      this.epoch++
      this.setData({ classes: [], students: [], summary: null, note: '', dueOn: '', alerts: [] })
      wx.reLaunch({ url: '/pages/login/login' })
    }
  },
  endpoint() {
    const schoolClass = this.data.classes[this.data.classIndex]
    const student = this.data.students[this.data.studentIndex]
    return schoolClass && student ? '/classes/' + schoolClass.id + '/students/' + student.id + '/follow-ups' : ''
  },
  async allowSwitch() {
    if (this.data.saving || this.data.voice || this.data.grantsBusy) return false
    return (!this.data.note.trim() && !this.data.dueOn) || await confirm('切换后会清空未保存的沟通记录。', '切换学员')
  },
  async changeClass(e: WechatMiniprogram.PickerChange) {
    const index = Number(e.detail.value)
    if (index !== this.data.classIndex && await this.allowSwitch()) return run(() => this.selectClass(index))
  },
  async selectClass(index: number) {
    const schoolClass = this.data.classes[index]
    if (!schoolClass) return
    const epoch = ++this.epoch
    this.setData({ classIndex: index, grantsOpen: false, grants: [], eligibleStaff: [], staffIndex: -1, departmentIndex: -1, studentIndex: -1, students: [], summary: null, note: '', dueOn: '', alerts: [], loading: true })
    try {
      const students = await api<Named[]>('/classes/' + schoolClass.id + '/follow-up-roster')
      if (this.epoch === epoch && this.owner === token()) this.setData({ students })
      await this.loadAlerts(schoolClass.id)
    } finally {
      if (this.epoch === epoch) this.setData({ loading: false })
    }
  },
  async changeStudent(e: WechatMiniprogram.PickerChange) {
    return this.selectStudent(Number(e.detail.value))
  },
  async selectStudent(index: number) {
    if (index === this.data.studentIndex || !this.data.students[index] || !await this.allowSwitch()) return
    const epoch = ++this.epoch
    this.key = ''
    this.setData({ studentIndex: index, summary: null, note: '', dueOn: '', voiceHint: '' })
    return run(() => this.loadSummary(epoch))
  },
  async loadSummary(epoch: number) {
    const path = this.endpoint()
    if (!path) return
    this.setData({ loading: true })
    try {
      const summary = await api<FollowUp>(path)
      if (this.epoch === epoch && this.owner === token()) {
        summary.notes = summary.notes.map(n => ({ ...n, createdAt: new Date(Date.parse(n.createdAt) + 8 * 3600000).toISOString().slice(0, 16).replace('T', ' ') }))
        this.setData({ summary })
      }
    } finally {
      if (this.epoch === epoch) this.setData({ loading: false })
    }
  },
  grantPath() {
    const classId = this.data.classes[this.data.classIndex]?.id
    return classId && this.data.canGrant ? '/admin/classes/' + classId + '/follow-up-grants' : ''
  },
  toggleGrants() {
    const path = this.grantPath()
    if (!path || this.data.grantsBusy) return
    if (this.data.grantsOpen) { this.setData({ grantsOpen: false }); return }
    this.setData({ grantsOpen: true, grantsBusy: true })
    return run(async () => {
      try {
        const [grants, catalog] = await Promise.all([api<Grant[]>(path), api<{ staff: EligibleStaff[] }>('/admin/catalog')])
        if (this.owner !== token()) return
        this.setData({ grants, eligibleStaff: catalog.staff.filter(staff => staff.enabled && staff.role !== 'admin') })
      } finally { this.setData({ grantsBusy: false }) }
    })
  },
  chooseStaff(e: WechatMiniprogram.PickerChange) { this.setData({ staffIndex: Number(e.detail.value) }) },
  chooseDepartment(e: WechatMiniprogram.PickerChange) { this.setData({ departmentIndex: Number(e.detail.value) }) },
  addGrant() {
    const staff = this.data.eligibleStaff[this.data.staffIndex]
    const department = this.data.departments[this.data.departmentIndex]
    if (staff && department) return this.changeGrant('POST', { staffId: staff.id, department })
    return undefined
  },
  revokeGrant(e: WechatMiniprogram.TouchEvent) {
    return this.changeGrant('DELETE', undefined, String(e.currentTarget.dataset.id))
  },
  changeGrant(method: 'POST' | 'DELETE', data?: { staffId: string; department: string }, id = '') {
    const path = this.grantPath()
    if (!path || this.data.grantsBusy) return
    this.setData({ grantsBusy: true })
    return run(async () => {
      try {
        if (method === 'DELETE' && !await confirm('撤销后，该员工将失去此项班级沟通授权。')) return
        if (this.owner !== token()) return
        await api(path + (id ? '/' + encodeURIComponent(id) : ''), method, data)
        const grants = await api<Grant[]>(path)
        if (this.owner === token()) this.setData({ grants, staffIndex: -1, departmentIndex: -1 })
      } finally { this.setData({ grantsBusy: false }) }
    })
  },
  async loadAlerts(classId: string) {
    const alerts = await api<FollowUpAlert[]>('/classes/' + classId + '/follow-up-alerts')
    if (this.owner !== token() || this.data.classes[this.data.classIndex]?.id !== classId) return
    const today = chinaToday()
    this.setData({ alerts: alerts.map(alert => {
      let status = alert.dueOn < today ? '已逾期' : alert.dueOn === today ? '今天到期' : '待跟进'
      if (alert.kind === 'attendance') status = '近30天有 ' + alert.count + ' 次缺课待安排补课'
      if (alert.kind === 'homework') status = '近30天有 ' + alert.count + ' 份作业逾期未交'
      return { ...alert, status }
    }) })
  },
  openAlert(e: WechatMiniprogram.TouchEvent) {
    const studentId = e.currentTarget.dataset.studentId
    return this.selectStudent(this.data.students.findIndex(student => student.id === studentId))
  },
  changeDueOn(e: WechatMiniprogram.PickerChange) {
    this.key = ''
    this.setData({ dueOn: String(e.detail.value) })
  },
  clearDueOn() {
    this.key = ''
    this.setData({ dueOn: '' })
  },
  async resolve(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id)
    const path = this.endpoint()
    const epoch = this.epoch
    const classId = this.data.classes[this.data.classIndex]?.id
    if (!path || !classId || this.data.saving || this.data.voice) return
    this.setData({ saving: true })
    return run(async () => {
      try {
        if (!await confirm('确认这项沟通已经跟进完成？', '完成跟进') || epoch !== this.epoch || this.owner !== token()) return
        await api(path + '/' + encodeURIComponent(id) + '/resolve', 'POST')
        if (epoch === this.epoch) await Promise.all([this.loadSummary(epoch), this.loadAlerts(classId)])
      } finally {
        if (epoch === this.epoch) this.setData({ saving: false })
      }
    })
  },
  reload() { return run(() => this.loadSummary(this.epoch)) },
  input(e: WechatMiniprogram.Input) {
    this.key = ''
    this.setData({ note: e.detail.value })
  },
  speechManager(): SpeechManager | null {
    // Temporarily disabled with owner approval: WechatSI currently denies this AppID.
    // Text entry and native keyboard dictation remain available.
    return null
  },
  startVoice() {
    if (!this.data.voiceAvailable || this.data.voice || this.data.saving || !this.endpoint()) return
    const manager = this.speechManager()
    if (!manager) {
      this.setData({ voiceAvailable: false })
      return
    }
    const epoch = this.epoch
    this.manager = manager
    this.setData({ voice: 'permission', voiceHint: '' })
    const active = () => this.manager === manager && this.epoch === epoch && this.owner === token()
    const fail = (voiceHint: string) => {
      if (!active()) return
      this.manager = null
      this.setData({ voice: '', voiceHint })
    }
    manager.onError = () => fail('语音转写失败，请重试或直接输入文字。')
    manager.onStop = ({ result }) => {
      if (!active()) return
      this.manager = null
      const text = result?.trim() || ''
      if (text) this.key = ''
      const note = [this.data.note.trimEnd(), text].filter(Boolean).join('\n')
      this.setData({
        voice: '', note: note.slice(0, 2000),
        voiceHint: !text ? '没有识别到内容，请重试。' : note.length > 2000 ? '已达到 2000 字上限，请核对后保存。' : '已转写，请核对后保存。'
      })
    }
    wx.authorize({
      scope: 'scope.record',
      success: () => {
        if (!active()) return
        this.setData({ voice: 'recording' })
        try { manager.start({ lang: 'zh_CN', duration: 60000 }) }
        catch { fail('无法开始录音，请重试或直接输入文字。') }
      },
      fail: () => fail('请在小程序设置中打开麦克风权限，或直接输入文字。')
    })
  },
  finishVoice() {
    if (this.data.voice !== 'recording' || !this.manager) return
    this.setData({ voice: 'finishing' })
    try { this.manager.stop() }
    catch { this.cancelVoice(); this.setData({ voiceHint: '无法完成录音，请重试。' }) }
  },
  cancelVoice() {
    const manager = this.manager
    this.manager = null
    if (manager && this.data.voice === 'recording') {
      try { manager.stop() } catch { /* Recorder may already have stopped. */ }
    }
    if (this.data.voice) this.setData({ voice: '' })
  },
  save() {
    const path = this.endpoint()
    const note = this.data.note.trim()
    if (!path || !note || this.data.saving || this.data.voice) return
    const epoch = this.epoch
    this.key ||= requestKey()
    this.setData({ saving: true })
    return run(async () => {
      try {
        await api(path, 'POST', { note, requestKey: this.key, dueOn: this.data.dueOn })
        if (this.epoch !== epoch || this.owner !== token()) return
        this.key = ''
        this.setData({ note: '', dueOn: '', voiceHint: '' })
        wx.showToast({ title: '沟通记录已保存', icon: 'success' })
        await Promise.all([this.loadSummary(epoch), this.loadAlerts(this.data.classes[this.data.classIndex].id)])
      } finally {
        if (this.epoch === epoch) this.setData({ saving: false })
      }
    })
  }
})
