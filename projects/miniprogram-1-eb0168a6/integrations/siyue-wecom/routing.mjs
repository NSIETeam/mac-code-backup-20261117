const dictionary = 'https://www.siyuejn.com/work/?view=library'
const oaHosts = new Set(['work.weixin.qq.com', 'open.work.weixin.qq.com', 'oa.weixin.qq.com'])
const builtInQueries = ['请假', '补卡', '报销', '采购', '出差', '用印', '退费', '试听', '教务交接', '岗位职责']

function safeOaLink(raw) {
  if (typeof raw !== 'string') throw new Error('OA 入口须为企业微信链接')
  const url = new URL(raw)
  if (url.protocol !== 'https:' || !oaHosts.has(url.hostname) || url.username || url.password || url.port
    || url.hash || !/^[a-zA-Z0-9/_-]+$/.test(url.pathname)
    || [...url.searchParams.keys()].some(key => /token|secret|ticket|password|redirect/i.test(key))) {
    throw new Error('OA 入口须使用企业微信 HTTPS 地址，且不含凭据或跳转参数')
  }
  return url.href.replaceAll('(', '%28').replaceAll(')', '%29')
}

export function createRouter(approvalEntries = {}) {
  if (!approvalEntries || typeof approvalEntries !== 'object' || Array.isArray(approvalEntries)) {
    throw new Error('OA 入口配置必须为申请名称与链接的映射')
  }
  const links = new Map()
  for (const [name, url] of Object.entries(approvalEntries)) {
    if (!/^[\p{Script=Han}a-zA-Z0-9]{2,20}$/u.test(name)) throw new Error('审批名称格式无效')
    links.set(name, safeOaLink(url))
  }
  const terms = [...new Set([...links.keys(), ...builtInQueries])].sort((a, b) => b.length - a.length)
  return function route(text) {
    const message = typeof text === 'string' ? text.trim().slice(0, 200) : ''
    const topic = terms.find(term => message.includes(term))
    const dictionaryLink = dictionary + (topic ? '&q=' + encodeURIComponent(topic) : '')
    const approval = /发起|提交申请|申请入口|我要申请|怎么申请|^申请/.test(message)
      || !!topic && (message === topic + '申请' || message === '我要' + topic)
    if (approval && topic && links.has(topic)) {
      return ['[打开「' + topic + '」申请](' + links.get(topic) + ')',
        '请在企业微信审批页核对申请内容并提交。',
        '[查看相关制度和流程](' + dictionaryLink + ')'].join('\n')
    }
    if (approval) {
      return ['请在企业微信「工作台 → 审批」选择申请类型。',
        '[查看相关制度和流程](' + dictionaryLink + ')'].join('\n')
    }
    return (topic ? '[在思跃词典查询「' + topic + '」]' : '[打开思跃组织知识库]') + '(' + dictionaryLink + ')\n打开后使用本人账号查看有权限的资料。'
  }
}
