import { readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
const [mode, portText, fixturePath]=process.argv.slice(2);
const port=Number(portText);
assert.ok([18081,18082].includes(port),'Only isolated app ports allowed');
const base='http://127.0.0.1:'+port;
const sessionPath='/api/appointment/sessions?hospitalId=DEMO001&department='+encodeURIComponent('内科');
function client(){
 let cookie='';
 async function call(path,body,status=200){
  const headers={Accept:'application/json'};
  if(cookie)headers.Cookie=cookie;
  if(body!==undefined){
   const csrf=await call('/api/auth/csrf');
   headers[csrf.headerName]=csrf.token;headers.Cookie=cookie;headers['Content-Type']='application/json';
  }
  const res=await fetch(base+path,{method:body===undefined?'GET':'POST',headers,
   ...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(20000)});
  for(const value of res.headers.getSetCookie())if(value.startsWith('JSESSIONID='))cookie=value.split(';')[0];
  assert.equal(res.status,status,path);
  return res.json();
 }
 return call;
}
const log=name=>console.log('PASS '+name);
if(mode==='seed'){
 const a=client(),b=client(),suffix=randomUUID().slice(0,8),password='TestOnly!'+randomUUID();
 const first={username:'restore_a_'+suffix,password},second={username:'restore_b_'+suffix,password};
 const account=await a('/api/auth/register',first,201);await a('/api/auth/login',first);
 await b('/api/auth/register',second,201);await b('/api/auth/login',second);
 const conversation=await a('/api/conversations',{},200);
 const conversationId=conversation.conversationId;
 const secondConversation=await b('/api/conversations',{},200);
 const sessions=await a(sessionPath);
 const selected=sessions.sessions.find(s=>s.bookable&&s.referenceRemaining>=3);
 assert.ok(selected,'No suitable available test session');
 const draftBody={hospitalId:'DEMO001',department:'内科',visitDate:selected.visitDate,sessionId:selected.sessionId};
 const create=()=>a('/api/appointments/drafts?conversationId='+conversationId,draftBody,201);
 const pending=await create(),active=await create(),cancelled=await create();
 const activeAppointment=await a('/api/appointments/drafts/'+active.draftId+'/confirm',{});
 const cancelledAppointment=await a('/api/appointments/drafts/'+cancelled.draftId+'/confirm',{});
 const cancelledResult=await a('/api/appointments/'+cancelledAppointment.appointmentId+'/cancel',{confirmed:true});
 const current=(await a(sessionPath)).sessions.find(s=>s.sessionId===selected.sessionId);
 assert.equal(current.sessionRemaining,selected.sessionRemaining-1);
 const fixture={first,second,userId:account.userId,conversationId,secondConversationId:secondConversation.conversationId,
  selected,pending:pending.draftId,active:active.draftId,cancelled:cancelled.draftId,
  activeAppointment:activeAppointment.appointmentId,cancelledAppointment:cancelledAppointment.appointmentId,
  cancelledAt:cancelledResult.cancelledAt,expectedRemaining:current.sessionRemaining};
 assert.ok(fixture.userId);
 await writeFile(fixturePath,JSON.stringify(fixture));
 log('Source fixture: two accounts, owned conversations, pending/active/cancelled appointments and one occupied slot');
}else if(mode==='verify'){
 const f=JSON.parse(await readFile(fixturePath,'utf8')),a=client(),b=client();
 const who=await a('/api/auth/login',f.first);assert.equal(who.userId,f.userId);
 log('Restored password hash authenticates with original account ID');
 const list=await a('/api/conversations');assert.ok(list.items.some(x=>x.conversationId===f.conversationId));
 const history=await a('/api/conversations/'+f.conversationId+'/messages');
 assert.equal(history.messages.length,2);assert.equal(history.messages[0].content,'备份演练中文问题');
 assert.equal(history.messages[1].content,'备份演练固定回复，不调用模型。');
 assert.equal(history.messages[1].sources[0].source,'knowledge/backup-fixture.txt');assert.equal(history.processing,false);
 log('Restored conversation, Chinese history and source metadata');
 for(const [id,state]of [[f.pending,'PENDING_CONFIRMATION'],[f.active,'CONFIRMED'],[f.cancelled,'APPOINTMENT_CANCELLED']]){
  assert.equal((await a('/api/appointments/drafts/'+id)).status,state);
 }
 assert.equal((await a('/api/appointments/'+f.activeAppointment)).status,'DEMO_CREATED');
 assert.equal((await a('/api/appointments/'+f.cancelledAppointment)).cancelledAt,f.cancelledAt);
 const selected=async()=>(await a(sessionPath)).sessions.find(s=>s.sessionId===f.selected.sessionId);
 assert.equal((await selected()).sessionRemaining,f.expectedRemaining);
 log('Restored Mongo draft/MySQL appointment IDs, states, cancellation timestamp and capacity agree');
 await b('/api/auth/login',f.second);
 assert.ok((await b('/api/conversations')).items.every(x=>x.conversationId!==f.conversationId));
 await b('/api/conversations/'+f.conversationId+'/messages',undefined,404);
 await b('/api/appointments/drafts/'+f.active,undefined,404);
 await b('/api/appointments/'+f.activeAppointment,undefined,404);
 log('Restored ownership rejects another account');
 const created=await a('/api/appointments/drafts/'+f.pending+'/confirm',{});
 assert.equal((await a('/api/appointments/drafts/'+f.pending+'/confirm',{})).appointmentId,created.appointmentId);
 assert.equal((await selected()).sessionRemaining,f.expectedRemaining-1);
 log('Restored pending draft confirms once; repeated confirmation does not consume twice');
 const cancel=await a('/api/appointments/'+f.activeAppointment+'/cancel',{confirmed:true});
 const again=await a('/api/appointments/'+f.activeAppointment+'/cancel',{confirmed:true});
 assert.equal(again.cancelledAt,cancel.cancelledAt);
 assert.equal((await selected()).sessionRemaining,f.expectedRemaining);
 log('Restored active appointment cancels idempotently and releases one slot');
 console.log('RESTORED_BUSINESS_OK checks=6');
}else throw Error('Unknown mode');
