import { settings } from './api'

const tokenKey = 'siyue_parent_token'
export function parentToken(): string { return wx.getStorageSync(tokenKey) || '' }

class ParentRequestError extends Error {
  constructor(message: string, readonly status: number) { super(message) }
}

function request<T>(path: string, method: 'GET' | 'POST', data: unknown, token: string): Promise<T> {
  return new Promise((resolve, reject) => wx.request({
    url: settings.baseUrl + '/academic-pilot/parent' + path,
    method, data: data as WechatMiniprogram.IAnyObject, header: { 'X-Parent-Token': token }, timeout: 20000,
    success: response => {
      if (response.statusCode >= 200 && response.statusCode < 300) resolve(response.data as T)
      else reject(new ParentRequestError((response.data as { message?: string })?.message || '暂时无法读取，请重试', response.statusCode))
    },
    fail: () => reject(Error('网络未连接，请稍后重试'))
  }))
}

let loginInFlight: Promise<void> | undefined
export function parentLogin(): Promise<void> {
  if (parentToken()) return Promise.resolve()
  if (!loginInFlight) {
    loginInFlight = (async () => {
      const code = await new Promise<string>((resolve, reject) => wx.login({
        success: result => result.code ? resolve(result.code) : reject(Error('请重新打开微信后重试')),
        fail: () => reject(Error('微信登录失败，请重试'))
      }))
      const result = await request<{ token: string }>('/wechat', 'POST', { code }, '')
      wx.setStorageSync(tokenKey, result.token)
    })().finally(() => { loginInFlight = undefined })
  }
  return loginInFlight
}

export async function parentApi<T>(path: string, method: 'GET' | 'POST' = 'GET', data?: unknown): Promise<T> {
  if (path === '/status') return request<T>(path, method, data, '')
  const token = parentToken()
  try {
    return await request<T>(path, method, data, token)
  } catch (error) {
    if (!(error instanceof ParentRequestError) || error.status !== 401) throw error
    if (token === parentToken()) wx.removeStorageSync(tokenKey)
    if (method !== 'GET' && !/^\/tasks\/[0-9a-f-]{36}\/open$/i.test(path)) throw error
    await parentLogin()
    return request<T>(path, method, data, parentToken())
  }
}
