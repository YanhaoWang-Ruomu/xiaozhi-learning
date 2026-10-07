import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import assert from 'node:assert/strict'
const [fixturePath,port]=process.argv.slice(2)
assert.ok(['18081','18082'].includes(port),'Only isolated app ports allowed')
const fixture=JSON.parse(await readFile(fixturePath,'utf8'))
const client=new Client({name:'isolated-live-probe',version:'1'})
const transport=new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL('./server.mjs',import.meta.url))],env:{...process.env,XIAOZHI_MCP_URL:`http://127.0.0.1:${port}`,XIAOZHI_MCP_USERNAME:fixture.first.username,XIAOZHI_MCP_PASSWORD:fixture.first.password}})
try {
 await client.connect(transport)
 const tools=await client.listTools();assert.equal(tools.tools.length,2)
 const response=await client.callTool({name:'query_appointment_sessions',arguments:{hospitalId:'DEMO001',department:'内科'}})
 assert.ok(!response.isError);const data=JSON.parse(response.content[0].text);assert.equal(data.status,'DEMO_DATA');assert.ok(data.sessions.length>0)
 assert.ok((await client.callTool({name:'confirm_appointment',arguments:{}})).isError)
 console.log('MCP_LIVE_PASS real_stdio authenticated_backend sessions_readonly')
}finally{await client.close();await transport.close()}
