import { api, run, upload, token } from '../../services/api'
import { outreachLink } from '../../services/outreach-link'

interface ExamQuestion { text: string; wrong: boolean }
interface Diagnosis {
  matched: { number: number; topics: string[] }[]
  teachers: { name: string; courses: string[] }[]
}

Page({
  data: {
    subjects: ['数学', '语文', '英语', '物理', '化学', '生物', '政治', '历史', '地理'],
    stages: ['小学', '初中', '高中'], subjectIndex: 0, stageIndex: 2, busy: false,
    questions: [] as ExamQuestion[], manual: '', form: '', result: null as Diagnosis | null
  },
  onLoad(query: Record<string, string>) {
    if (!query.form) return
    let form = outreachLink(query.form)
    if (!form) {
      try { form = outreachLink(decodeURIComponent(query.form)) } catch { /* Malformed activity path. */ }
    }
    this.setData({ form })
  },
  subject(e: WechatMiniprogram.PickerChange) {
    this.setData({ subjectIndex: Number(e.detail.value), result: null })
  },
  stage(e: WechatMiniprogram.PickerChange) {
    this.setData({ stageIndex: Number(e.detail.value), result: null })
  },
  choosePhoto() {
    if (this.data.busy) return
    wx.chooseImage({
      count: 1, sizeType: ['compressed'], sourceType: ['camera', 'album'],
      success: result => { void this.recognize(result.tempFilePaths[0]) }
    })
  },
  recognize(path: string) {
    if (!path || this.data.busy) return
    const owner = token()
    this.setData({ busy: true })
    return run(async () => {
      try {
        const text = await upload<string[]>('/outreach/diagnosis/photo', path)
        if (owner === token()) this.setData({ questions: text.map(value => ({ text: value, wrong: false })), result: null })
      } finally { this.setData({ busy: false }) }
    })
  },
  toggleWrong(e: WechatMiniprogram.TouchEvent) {
    const index = Number(e.currentTarget.dataset.index)
    const question = this.data.questions[index]
    if (question) this.setData({ ['questions[' + index + '].wrong']: !question.wrong, result: null })
  },
  editQuestion(e: WechatMiniprogram.Input) {
    const index = Number(e.currentTarget.dataset.index)
    if (this.data.questions[index]) this.setData({ ['questions[' + index + '].text']: e.detail.value, result: null })
  },
  manualInput(e: WechatMiniprogram.Input) { this.setData({ manual: e.detail.value }) },
  addWrong() {
    const text = this.data.manual.trim()
    if (text.length < 4 || this.data.questions.length >= 40) return
    this.setData({ questions: [...this.data.questions, { text, wrong: true }], manual: '', result: null })
  },
  diagnose() {
    if (this.data.busy) return
    const confirmedWrong = this.data.questions.filter(question => question.wrong).map(question => question.text.trim())
    if (!confirmedWrong.length || confirmedWrong.length > 20 || confirmedWrong.some(text => text.length < 4)) {
      wx.showToast({ title: '请核对并选择 1–20 道错题', icon: 'none' })
      return
    }
    const owner = token()
    this.setData({ busy: true, result: null })
    return run(async () => {
      try {
        const result = await api<Diagnosis>('/outreach/diagnosis', 'POST', {
          subject: this.data.subjects[this.data.subjectIndex], stage: this.data.stages[this.data.stageIndex], confirmedWrong
        })
        if (owner === token()) this.setData({ result })
      } finally { this.setData({ busy: false }) }
    })
  },
  book() {
    if (!this.data.result || this.data.busy) return
    if (this.data.form) {
      wx.navigateTo({ url: '/pages/outreach/outreach?form=' + encodeURIComponent(this.data.form) })
    } else {
      wx.navigateBack()
    }
  }
})
