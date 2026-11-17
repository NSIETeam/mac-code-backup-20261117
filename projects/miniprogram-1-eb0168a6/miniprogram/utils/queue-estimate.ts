export interface QueueEstimate {
  state: string
  ahead?: number | null
  waitSeconds?: number | null
  remainingSeconds?: number | null
  basis?: 'observed' | 'initial' | 'unknown'
  stage?: string | null
  current?: number | null
  total?: number | null
  updatedAt?: string | null
  delayed?: boolean
}

function duration(seconds: unknown): string {
  if (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds < 0) return ''
  const minutes = Math.max(1, Math.ceil(seconds / 60))
  if (minutes < 60) return minutes + ' 分钟'
  const hours = Math.floor(minutes / 60), rest = minutes % 60
  return hours + ' 小时' + (rest ? ' ' + rest + ' 分钟' : '')
}

// updatedAt is the server's estimate timestamp, not the beginning of a countdown.
// Only the server can mark a job delayed; old timestamps cannot imply zero time left.
export function formatQueueEstimate(queue?: QueueEstimate | null): string {
  if (!queue) return ''
  if (queue.state === 'waiting') return '等待前置步骤完成，暂无法估算等待时间'
  if (queue.state === 'retrying') return '正在等待重试，暂无法估算等待时间'
  if (queue.state !== 'queued' && queue.state !== 'processing' && queue.state !== 'running') return ''
  const queued = queue.state === 'queued'
  const ahead = typeof queue.ahead === 'number' && Number.isSafeInteger(queue.ahead) && queue.ahead >= 0
    ? '前面还有 ' + queue.ahead + ' 个任务' : '正在排队'
  if (queue.delayed === true) return queued ? ahead + ' · 等待时间超过预估，仍在排队' : '耗时超过预估，仍在继续处理'
  if (queue.basis === 'observed' || queue.basis === 'initial') {
    if (queued && queue.ahead === 0 && queue.waitSeconds === 0) return '即将开始' + (queue.basis === 'initial' ? '（初步估算）' : '')
    if (!queued && queue.remainingSeconds === 0) return '正在完成收尾'
  }
  const estimate = queue.basis === 'observed' || queue.basis === 'initial'
    ? duration(queued ? queue.waitSeconds : queue.remainingSeconds) : ''
  if (!estimate) return queued ? ahead + ' · 暂无法估算开始时间' : '正在处理，暂无法估算剩余时间'
  const initial = queue.basis === 'initial' ? '（初步估算）' : ''
  return (queued ? ahead + ' · 预计约 ' + estimate + '开始' : '预计还需约 ' + estimate) + initial
}
