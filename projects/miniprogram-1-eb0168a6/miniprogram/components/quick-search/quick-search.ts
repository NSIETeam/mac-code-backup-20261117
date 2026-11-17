import { classroomUrl, currentClassroom } from '../../services/classroom-context'
import { api, user, Workspace } from '../../services/api'
import { availableActions, matchingResults, classroomResults, QuickAction, SearchResult } from '../../services/quick-search'
Component({
  options: { pureDataPattern: /^_/ },
  properties: { classId: { type: String, value: '' }, courseId: { type: String, value: '' } },
  data: { query: '', opened: false, choosingClass: false, placeholder: '查找操作', results: [] as SearchResult[], loading: false, error: '', _active: false, _sequence: 0, _commands: [] as QuickAction[], _choices: [] as SearchResult[] },
  pageLifetimes: { hide() { this.close() } },
  lifetimes: { detached() { this.data._sequence++; this.data._active = false } },
  methods: {
    focus() { this.data._active = true; this.data._commands = availableActions(user()?.role || 'teacher'); this.setData({ opened: true, error: '' }); this.filter() },
    input(e: WechatMiniprogram.Input) { this.setData({ query: e.detail.value }); this.filter() },
    filter() { this.setData({ results: matchingResults(this.data.choosingClass ? this.data._choices : this.data._commands, this.data.query) }) },
    clear() { this.setData({ query: '' }); this.filter() },
    close() { this.data._active = false; this.data._sequence++; this.setData({ opened: false, query: '', choosingClass: false, placeholder: '查找操作', results: [], loading: false, error: '' }); wx.hideKeyboard() },
    async choose(e: WechatMiniprogram.TouchEvent) {
      if (this.data.loading) return
      const selected = this.data.results.find(result => result.id === e.currentTarget.dataset.id); if (!selected) return
      if(selected.id==='sessions'){this.navigate(classroomUrl());return}
      if (selected.url) { const context=currentClassroom();this.navigate(selected.url+(selected.id==='new-session'&&context?'?classId='+encodeURIComponent(context.classId):'')); return }
      const action = this.data._commands.find(command => command.id === selected.id); if (!action) return
      const sequence = ++this.data._sequence; this.setData({ loading: true, error: '' })
      try {
        const workspace = await api<Workspace>('/workspace')
        if (!this.data._active || sequence !== this.data._sequence) return
        this.data._choices = classroomResults(workspace, action)
        const context = this.data._choices.find(choice => choice.id === this.properties.classId + ':' + this.properties.courseId)
        if (context) { this.navigate(context.url!); return }
        this.setData({ choosingClass: true, query: '', placeholder: '选择班级 · ' + action.title }); this.filter()
      } catch (e) { if (this.data._active && sequence === this.data._sequence) this.setData({ error: e instanceof Error ? e.message : '读取班级失败，请重试' }) }
      finally { if (this.data._active && sequence === this.data._sequence) this.setData({ loading: false }) }
    },
    navigate(url: string) {
      const options = { url, success: () => this.close(), fail: () => this.setData({ error: '页面未能打开，请返回后重试' }) }
      if (getCurrentPages().length >= 9) wx.redirectTo(options); else wx.navigateTo(options)
    },
    back() { this.data._sequence++; this.setData({ choosingClass: false, query: '', placeholder: '查找操作', loading: false, error: '' }); this.filter() },
    preventScroll() {}
  }
})
