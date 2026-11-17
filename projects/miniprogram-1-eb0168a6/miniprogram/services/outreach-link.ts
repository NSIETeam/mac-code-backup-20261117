const origin = 'https://www.siyuejn.com'
const formPath = '/crm/public-form.html'
const parameterNames = new Set(['id', 'api', 'activityId', 'promotionLinkId', 'campaign', 'promotion'])

export function outreachLink(raw: string): string {
  const url = raw.trim()
  if (url.length > 1000) return ''
  const match = /^https:\/\/(?:www\.)?siyuejn\.com\/crm\/public-form\.html\?([^#]+)$/.exec(url)
  if (!match) return ''
  const params = new Map<string, string>()
  for (const field of match[1].split('&')) {
    const split = field.indexOf('=')
    if (split < 1) return ''
    const key = field.slice(0, split)
    if (!parameterNames.has(key) || params.has(key)) return ''
    let value: string
    try { value = decodeURIComponent(field.slice(split + 1)) } catch { return '' }
    if (!/^[A-Za-z0-9_-]{1,160}$/.test(value)) return ''
    params.set(key, value)
  }
  if (!params.has('id') || (params.has('api') && params.get('api') !== 'v2')) return ''
  for (const [name, alias] of [['activityId', 'campaign'], ['promotionLinkId', 'promotion']]) {
    if (!params.has(alias)) continue
    const value = params.get(alias)!
    if (params.has(name) && params.get(name) !== value) return ''
    params.set(name, value)
    params.delete(alias)
  }
  if (params.has('promotionLinkId') && !params.has('activityId')) return ''
  if (!params.has('api')) {
    if (params.has('activityId') && !params.has('promotionLinkId')) return ''
    // The deployed legacy form forwards the campaign and promotion query names.
    for (const [name, alias] of [['activityId', 'campaign'], ['promotionLinkId', 'promotion']]) {
      const value = params.get(name)
      if (!value) continue
      params.delete(name)
      params.set(alias, value)
    }
  }
  return origin + formPath + '?' + Array.from(params).map(([key, value]) => key + '=' + encodeURIComponent(value)).join('&')
}
