// Reproducible UI recording against the controlled HTTP fixture, never a cloud AI demo.
import { chromium, expect } from '@playwright/test'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'
import { mkdir, writeFile, rename, readFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import net from 'node:net'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const output = resolve(process.argv[2] || join(root, 'test-results', 'demo'))
const base = 'http://127.0.0.1:15179'
await mkdir(output, { recursive: true })
for (const port of [15179, 25179]) {
 await new Promise((ok, fail) => {
  const s = net.createServer()
  s.once('error', () => fail(new Error('Recording port in use: ' + port)))
  s.listen(port, '127.0.0.1', () => s.close(ok))
 })
}
const server = spawn(process.execPath, ['tests/browser/server.mjs'], {cwd:root, stdio:['ignore','pipe','pipe']})
let log = ''
server.stdout.on('data', x => log += x)
server.stderr.on('data', x => log += x)
let browser, context, page, video
let started = 0
const steps = [], errors = []
try {
 for (let i=0; ; i++) {
  try { if ((await fetch(base + '/__fixture/state')).ok) break } catch {}
  if (i > 200 || server.exitCode !== null) throw new Error('Fixture did not start: ' + log)
  await new Promise(ok => setTimeout(ok,100))
 }
 await fetch(base + '/__fixture/reset', {method:'POST'})
 browser = await chromium.launch({headless:true})
 context = await browser.newContext({viewport:{width:1440,height:900},recordVideo:{dir:output,size:{width:1440,height:900}}})
 await context.addInitScript(() => {
  document.addEventListener('DOMContentLoaded', () => {
   const banner = document.createElement('div')
   banner.id = 'demo-scope'
   banner.style.cssText = 'position:fixed;inset:0 0 auto;z-index:99999;background:#183e3a;color:white;padding:8px 20px;font:14px sans-serif'
   banner.textContent = '受控 HTTP 界面演示 · 非真实模型回复 · 不连接业务数据库'
   document.body.prepend(banner)
   document.body.style.paddingTop = '34px'
   const caption = document.createElement('div')
   caption.id = 'demo-step'
   caption.style.cssText = 'position:fixed;bottom:10px;left:50%;transform:translateX(-50%);z-index:99999;background:#183e3a;color:white;padding:10px 20px;border-radius:8px;font:16px sans-serif;pointer-events:none'
   caption.textContent = '登录与导诊工作台'
   document.body.append(caption)
  })
 })
 started = Date.now()
 page = await context.newPage()
 video = page.video()
 page.on('pageerror', e => errors.push(e.message))
 const step = async (title, seconds=2) => {
  steps.push({atMs:Date.now()-started,title})
  await page.locator('#demo-step').evaluate((el,title)=>el.textContent=title,title)
  await page.waitForTimeout(seconds*1000)
 }
 const click = async name => {
  const button = page.getByRole('button',{name,exact:true})
  await button.scrollIntoViewIfNeeded()
  await button.click()
 }
 await page.goto(base)
 await step('01 登录：独立账号入口')
 await page.screenshot({path:join(output,'01-login.png')})
 await page.getByLabel('用户名',{exact:true}).fill('demo_visitor')
 await page.getByLabel('密码',{exact:true}).fill('demo-password-123')
 await click('登录')
 await expect(page.locator('textarea')).toBeEnabled()
 await step('02 导诊工作台：会话、交流与演示排班')
 await page.screenshot({path:join(output,'02-workspace.png')})
 await page.locator('textarea').fill('就诊需要准备什么？')
 await click('发送消息')
 await expect(page.getByText('正在整理就诊资料。',{exact:true})).toBeVisible()
 await step('03 SSE 接收：发送后避免重复提交',1)
 await expect(page.locator('.message-text').filter({hasText:'<img src=x'})).toBeVisible({timeout:15000})
 await expect(page.locator('.message-text img')).toHaveCount(0)
 await page.locator('.sources summary').click()
 await expect(page.getByText('演示资料：就诊前整理近期检查资料。')).toBeVisible()
 await step('04 来源展开；安全测试文本按文字显示')
 await page.screenshot({path:join(output,'03-sources.png')})
 await click('选择此场次，生成草稿')
 await expect(page.getByRole('button',{name:'确认创建演示预约',exact:true})).toBeEnabled()
 await step('05 选择时段只生成草稿，尚未创建预约')
 await click('确认创建演示预约')
 await expect(page.getByRole('region',{name:'操作确认区'})).toBeVisible()
 const before = await (await fetch(base+'/__fixture/state')).json()
 if (before.confirms!==0) throw new Error('Created before explicit confirmation')
 await step('06 核对草稿后，单独确认创建')
 await page.screenshot({path:join(output,'04-confirmation.png')})
 await click('核对无误，确认创建演示预约')
 await expect(page.getByRole('button',{name:'取消已确认的演示预约',exact:true})).toBeEnabled()
 await step('07 已确认的演示预约可以取消')
 await click('取消已确认的演示预约')
 await step('08 取消也需要核对与确认')
 await click('核对无误，取消已确认的演示预约')
 await expect(page.locator('.operation-notice')).toContainText('演示预约已取消，原记录保留')
 await step('09 取消保留记录；刷新后同步当前状态')
 await page.reload()
 await expect(page.locator('textarea')).toBeEnabled()
 await expect(page.getByText('查询时状态：预约已取消')).toBeVisible()
 await step('10 刷新恢复：聊天仅发送一次、取消仅执行一次')
 await page.screenshot({path:join(output,'05-restored.png')})
 await click('AI 工程实验室')
 await click('运行检索')
 await expect(page.getByText('采用 1 个片段')).toBeVisible()
 await step('11 工程实验室：独立查看检索结果',3)
 await page.screenshot({path:join(output,'06-lab.png')})
 const state = await (await fetch(base+'/__fixture/state')).json()
 if ([state.posts,state.creates,state.confirms,state.cancels].some(x=>x!==1)) throw new Error('Unexpected fixture request counts')
 if (errors.length) throw new Error('Browser errors: '+errors.join(';'))
 const manifest = {scope:'controlled HTTP/SSE UI demonstration, not live AI or business database verification',
  recordedAt:new Date().toISOString(),interactionDurationMs:Date.now()-started,
  timingDefinition:'step timestamps relative to page creation; encoded video duration may differ slightly',viewport:{width:1440,height:900},steps,
  assertions:{posts:state.posts,creates:state.creates,confirms:state.confirms,cancels:state.cancels,pageErrors:errors.length},
  credentials:'synthetic fixture only',video:'xiaozhi-ui-demo.webm'}
 await context.close(); context = null
 const videoPath = await video.path()
 await rename(videoPath,join(output,manifest.video))
 manifest.sha256 = createHash('sha256').update(await readFile(join(output,manifest.video))).digest('hex')
 await writeFile(join(output,'demo.json'),JSON.stringify(manifest,null,2)+'\n')
 console.log('UI_DEMO_RECORDED '+JSON.stringify(manifest.assertions))
} finally {
 if(context) await context.close()
 if(browser) await browser.close()
 server.kill()
 await Promise.race([new Promise(ok=>server.once('exit',ok)),new Promise(ok=>setTimeout(ok,5000))])
 await writeFile(join(output,'fixture.log'),log)
}
