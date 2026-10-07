import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import { Backend } from './backend.mjs'

test('reject remote credential destinations and URL credentials', () => {
  for (const baseUrl of ['https://example.com','http://127.0.0.1.evil.test','http://u:p@localhost','file:///tmp/a','http://localhost/path']) assert.throws(() => new Backend({baseUrl,username:'demo',password:'test'}))
})
test('real stdio handshake, tool schema, account session and no mutation tools', async () => {
  const reads=[]
  const app=http.createServer(async (req,res)=>{
    res.setHeader('content-type','application/json')
    if(req.url==='/api/auth/csrf'){res.setHeader('set-cookie','JSESSIONID=test; HttpOnly');res.end(JSON.stringify({headerName:'X-CSRF-TOKEN',token:'test-token'}));return}
    if(req.url==='/api/auth/login'){
      assert.equal(req.headers['x-csrf-token'],'test-token');assert.equal(req.headers.cookie,'JSESSIONID=test')
      let body='';for await(const chunk of req)body+=chunk
      assert.deepEqual(JSON.parse(body),{username:'test_user',password:'test_password_only'});res.end('{}');return
    }
    assert.equal(req.method,'GET');assert.equal(req.headers.cookie,'JSESSIONID=test');reads.push(req.url)
    res.end(JSON.stringify({status:'DEMO_DATA'}))
  })
  await new Promise(resolve=>app.listen(0,'127.0.0.1',resolve))
  const client=new Client({name:'contract-test',version:'1'})
  const transport=new StdioClientTransport({command:process.execPath,args:['server.mjs'],env:{...process.env,XIAOZHI_MCP_URL:`http://127.0.0.1:${app.address().port}`,XIAOZHI_MCP_USERNAME:'test_user',XIAOZHI_MCP_PASSWORD:'test_password_only'}})
  try {
    await client.connect(transport)
    const list=await client.listTools();assert.deepEqual(list.tools.map(t=>t.name).sort(),['query_appointment_sessions','search_knowledge'])
    assert.ok(list.tools.every(t=>t.annotations.readOnlyHint && !t.annotations.destructiveHint))
    const a=await client.callTool({name:'search_knowledge',arguments:{query:'轮椅'}});assert.ok(!a.isError)
    const b=await client.callTool({name:'query_appointment_sessions',arguments:{hospitalId:'DEMO001',department:'内科'}});assert.ok(!b.isError)
    const bad=await client.callTool({name:'search_knowledge',arguments:{query:''}});assert.ok(bad.isError)
    assert.equal(reads.length,2)
    assert.ok((await client.callTool({name:'confirm_appointment',arguments:{}})).isError)
  } finally { await client.close(); await transport.close(); await new Promise(resolve=>app.close(resolve)) }
})
