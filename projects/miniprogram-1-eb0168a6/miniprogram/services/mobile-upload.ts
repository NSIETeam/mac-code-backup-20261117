import { api } from './api'
export async function openMobileUpload(context: Record<string, unknown>) {
  const ticket = await api<{id:string;url:string}>('/mobile-uploads', 'POST', context)
  await new Promise<void>((resolve, reject) => wx.navigateTo({
    url: '/pages/mobile-upload/mobile-upload?url=' + encodeURIComponent(ticket.url),
    success: () => resolve(),
    fail: () => reject(new Error('手机文件页面未能打开，请返回首页后重试'))
  }))
}
