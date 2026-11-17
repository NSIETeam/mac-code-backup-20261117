import { readFile } from 'node:fs/promises'
import { WSClient, generateReqId } from '@wecom/aibot-node-sdk'
import { createRouter } from './routing.mjs'

const botId = process.env.SIYUE_WECOM_BOT_ID?.trim()
const secret = process.env.SIYUE_WECOM_BOT_SECRET?.trim()
const allowed = new Set((process.env.SIYUE_WECOM_ALLOWED_USERS || '').split(',').map(id => id.trim()).filter(Boolean))
if (!botId || !secret || !allowed.size) {
  console.error('缺少思小跃机器人凭据或内部试点成员名单。')
  process.exit(1)
}
const configPath = process.env.SIYUE_WECOM_OA_CONFIG
let route
try {
  route = createRouter(configPath ? JSON.parse(await readFile(configPath, 'utf8')) : {})
} catch {
  console.error('无法读取 OA 入口配置，请检查文件格式和链接。')
  process.exit(1)
}
const quiet = { debug() {}, info() {}, warn() {}, error() {} }
const bot = new WSClient({ botId, secret, logger: quiet })
const seen = new Map()
bot.on('authenticated', () => console.log('思小跃已连接企业微信'))
bot.on('error', () => console.error('企业微信连接或回复异常'))
bot.on('message.text', async frame => {
  const message = frame.body
  if (message?.chattype !== 'single' || !allowed.has(message.from?.userid)
    || typeof message.msgid !== 'string' || !message.msgid || seen.has(message.msgid)) return
  seen.set(message.msgid, true)
  if (seen.size > 1000) seen.delete(seen.keys().next().value)
  try {
    await bot.replyStream(frame, generateReqId('siyue'), route(message.text?.content), true)
  } catch {
    console.error('思小跃回复失败')
  }
})
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { bot.disconnect(); process.exit(0) })
bot.connect()
