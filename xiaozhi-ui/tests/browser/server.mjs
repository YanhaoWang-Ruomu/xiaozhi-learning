// Controlled HTTP/SSE fixture: real browser transport, no cloud AI or business databases.
import http from 'node:http'
import { createServer, mergeConfig } from 'vite'
import viteConfig from '../../vite.config.js'
const conversation = { conversationId: 'browser-check', title: '就诊准备咨询', createdAt: '2026-10-07T00:00:00Z', updatedAt: '2026-10-07T00:00:00Z' }
const source = { source: 'knowledge/visit-handbook.pdf', index: 0, text: '演示资料：就诊前整理近期检查资料。' }
const session = { sessionId:'session-1', doctorId:'doctor-1', doctorName:'演示医生甲', slotId:'morning', slotName:'上午', startTime:'09:00:00', endTime:'12:00:00', visitDate:'2026-10-08', referenceRemaining:5, sessionRemaining:5, totalCapacity:5, dailyRemaining:20, activeAppointmentCount:0, bookingStatus:'AVAILABLE', releaseStatus:'RELEASED', bookable:true, releaseAt:'2026-10-07T08:00:00+08:00' }
const schedules = { status:'DEMO_DATA', hospitalId:'DEMO001', department:'内科', timeZone:'Asia/Shanghai', businessDate:'2026-10-07', bookingEnabled:true, sessions:[session] }
let state
const timers = new Set()
function reset() { for (const t of timers) clearTimeout(t); timers.clear(); state={authenticated:false,messages:[],processing:false,posts:0,creates:0,confirms:0,cancels:0,drafts:[]} }
reset()
function json(res, data, code=200) { res.writeHead(code, {'content-type':'application/json'});res.end(JSON.stringify(data)) }
const api = http.createServer(async(req,res)=>{
 const url=new URL(req.url,'http://127.0.0.1'); const path=url.pathname
 const chunks=[];for await(const chunk of req) chunks.push(chunk)
 const body=chunks.length?JSON.parse(Buffer.concat(chunks).toString()):{}
 if(path==='/__fixture/reset'){reset();return json(res,{ok:true})}
 if(path==='/__fixture/state')return json(res,state)
 if(path==='/api/auth/csrf')return json(res,{token:'fixture-csrf',headerName:'X-CSRF-TOKEN'})
 if(path==='/api/auth/login'){state.authenticated=true;return json(res,{userId:'fixture-owner',username:'demo_visitor'})}
 if(path==='/api/auth/me')return json(res,{userId:'fixture-owner',username:'demo_visitor'},state.authenticated?200:401)
 if(path==='/api/auth/logout'){state.authenticated=false;return json(res,{ok:true})}
 if(!state.authenticated)return json(res,{},401)
 if(path==='/api/conversations')return json(res,req.method==='POST'?{...conversation,conversationId:body.conversationId}:{items:[conversation],nextCursor:null})
 if(path.includes('/messages'))return json(res,{conversation,messages:state.messages,nextCursor:null,processing:state.processing})
 if(path==='/api/appointment/sessions')return json(res,schedules)
 if(path==='/api/appointments/drafts' && req.method==='GET')return json(res,state.drafts)
 if(path==='/api/appointments/drafts' && req.method==='POST'){
   state.creates++;state.drafts=[{draftId:'DRAFT-11111111-1111-4111-8111-111111111111',status:'PENDING_CONFIRMATION',hospitalId:'DEMO001',department:'内科',visitDate:session.visitDate,timeZone:'Asia/Shanghai',session}];return json(res,state.drafts[0])
 }
 if(path.startsWith('/api/appointments/drafts/') && req.method==='GET')return json(res,state.drafts[0])
 if(path.endsWith('/confirm')){
   state.confirms++;Object.assign(state.drafts[0],{status:'CONFIRMED',appointmentId:'DEMO-22222222-2222-4222-8222-222222222222'});return json(res,{...state.drafts[0],status:'DEMO_CREATED'})
 }
 if(path.startsWith('/api/appointments/') && path.endsWith('/cancel')){
   state.cancels++;state.drafts[0].status='APPOINTMENT_CANCELLED';return json(res,{...state.drafts[0],status:'DEMO_CANCELLED'})
 }
 if(path==='/api/knowledge/compare')return json(res,{scoreType:'RRF_FEATURE',fallback:false,accepted:[source]})
 if(path==='/api/chat/stream'){
   state.posts++;state.processing=true
   state.messages=[{id:'user-1',role:'user',content:body.message,state:'complete',sources:[]}]
   res.writeHead(200,{'content-type':'text/event-stream','cache-control':'no-cache','connection':'keep-alive'})
   const event=(name,data)=>{if(!res.destroyed)res.write(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`)}
   event('status',{message:'正在查询资料…'});event('token',{text:'正在整理就诊资料。'})
   const timer=setTimeout(()=>{
     const reply='请整理近期检查资料。<img src=x onerror=alert(1)> 是测试文本。'
     state.messages.push({id:'assistant-1',role:'assistant',content:reply,state:'complete',sources:[source]});state.processing=false
     event('done',{reply,sources:[source],drafts:state.drafts});res.end();timers.delete(timer)
   },3500);timers.add(timer);return
 }
 return json(res,{message:'fixture route missing'},404)
})
await new Promise(resolve=>api.listen(25179,'127.0.0.1',resolve))
const vite=await createServer(mergeConfig(viteConfig,{configFile:false,server:{host:'127.0.0.1',port:15179,strictPort:true,proxy:{'/api':{target:'http://127.0.0.1:25179'},'/__fixture':{target:'http://127.0.0.1:25179'}}}}))
await vite.listen()
const close=async()=>{for(const t of timers)clearTimeout(t);await vite.close();api.close();process.exit(0)}
process.on('SIGINT',close);process.on('SIGTERM',close)
