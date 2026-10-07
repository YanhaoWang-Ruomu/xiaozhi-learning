<script setup>
import { ref, onMounted } from 'vue'
import { apiFetch } from '@/api/auth.js'
const props = defineProps({ userId: { type: String, required: true } })
const query = ref('轮椅在哪里借用？'), mode = ref('hybrid'), result = ref(null), busy = ref(false), notice = ref('')
const checkpoint = ref(null), hospitalId = ref('DEMO001'), department = ref('内科'), visitDate = ref(''), sessionId = ref(''), schedules = ref(null)
const key = `xiaozhi.workflow.${props.userId}`
async function request(path, method = 'GET', body) {
 const response = await apiFetch(path, { method, signal: AbortSignal.timeout(120000), cache: 'no-store', headers: { 'Content-Type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}) })
 if (!response.ok) throw new Error(`操作未完成（HTTP ${response.status}）。请刷新状态后核对，避免重复操作。`)
 return response.json()
}
async function run(action) { if(busy.value) return; busy.value = true; notice.value = ''; try { await action() } catch(e) { notice.value = e.message } finally { busy.value = false } }
function accept(cp) { checkpoint.value = cp; const f = cp.requirements; hospitalId.value = f.hospitalId || 'DEMO001'; department.value = f.department || '内科'; visitDate.value = f.visitDate || ''; sessionId.value = f.sessionId || ''; try { localStorage.setItem(key,cp.id) } catch { notice.value = '浏览器存储不可用，请记下工作流编号。' } }
const compare = () => run(async () => { result.value = await request('/api/knowledge/compare?' + new URLSearchParams({query:query.value,mode:mode.value})) })
const create = () => run(async () => { const c = await request('/api/conversations','POST',{conversationId:crypto.randomUUID()}); accept(await request('/api/workflows','POST',{conversationId:c.conversationId})); schedules.value=null })
const save = () => run(async () => accept(await request(`/api/workflows/${checkpoint.value.id}/requirements`,'PUT',{version:checkpoint.value.version,hospitalId:hospitalId.value,department:department.value,visitDate:visitDate.value || null,sessionId:sessionId.value || null})))
const searchSessions = () => run(async () => { schedules.value = await request(`/api/workflows/${checkpoint.value.id}/sessions`) })
const refresh = () => run(async () => accept(await request(`/api/workflows/${checkpoint.value.id}`)))
const action = name => { if (!window.confirm(({draft:'核对需求后生成待确认演示草稿？这不会占用号源。',confirm:'确认提交这份演示预约并占用演示号源？',cancel:'确认取消？已确认的预约将释放演示号源。'})[name])) return; return run(async () => accept(await request(`/api/workflows/${checkpoint.value.id}/${name}`,'POST',{version:checkpoint.value.version,confirmed:true}))) }
onMounted(() => { let id; try { id=localStorage.getItem(key) } catch { return } if(id && /^[0-9a-f-]{36}$/.test(id)) run(async () => accept(await request(`/api/workflows/${id}`))) })
</script>
<template>
<section class="lab">
 <h2>AI 工程实验室</h2><p>用于学习与效果对照；所有排班和预约均为演示数据。</p>
 <p role="status">{{ notice }}</p>
 <h3>检索对照</h3>
 <label>问题<input v-model="query" maxlength="500" :disabled="busy" /></label>
 <label>方案<select v-model="mode" :disabled="busy"><option value="vector">向量检索</option><option value="hybrid">混合检索与特征重排</option><option value="llm">混合检索与模型重排（额外调用模型）</option></select></label>
 <button :disabled="busy || !query.trim()" @click="compare">运行检索</button>
 <div v-if="result"><p>评分类型：{{ result.scoreType }} {{ result.fallback }} {{ result.failureReason }}</p><article v-for="item in result.accepted" :key="item.index"><strong>{{ item.source }}</strong><p>{{ item.text }}</p></article><p v-if="!result.accepted.length">没有达到阈值的资料。</p></div>
 <h3>可恢复预约工作流</h3><button :disabled="busy" @click="create">新建演示工作流</button>
 <template v-if="checkpoint">
  <p>编号：{{ checkpoint.id }} · 状态：{{ checkpoint.phase }}</p><button :disabled="busy" @click="refresh">刷新状态</button>
  <fieldset :disabled="busy || Boolean(checkpoint.draftId)"><label>医院<input v-model="hospitalId" /></label><label>科室<input v-model="department" /></label><label>就诊日期<input v-model="visitDate" type="date" /></label><label>场次编号<input v-model="sessionId" /></label><button @click="save">保存已核对需求</button></fieldset>
  <button :disabled="busy" @click="searchSessions">查询已保存需求的排班</button><pre v-if="schedules">{{ JSON.stringify(schedules, null, 2) }}</pre>
  <button :disabled="busy || !['READY_FOR_DRAFT','RECOVERY_REQUIRED'].includes(checkpoint.phase)" @click="action('draft')">生成或恢复草稿</button>
  <button :disabled="busy || checkpoint.phase !== 'PENDING_CONFIRMATION'" @click="action('confirm')">确认演示预约</button>
  <button :disabled="busy || !['PENDING_CONFIRMATION','CONFIRMED'].includes(checkpoint.phase)" @click="action('cancel')">取消演示预约或草稿</button>
  <p v-if="checkpoint.draft">{{ checkpoint.draft.message }}</p>
 </template>
</section>
</template>
<style scoped>
.lab{max-width:1000px;margin:24px auto;padding:24px;background:white;border-radius:12px}.lab label{display:block;margin:10px 0}.lab input,.lab select{margin-left:12px;max-width:90%;padding:6px}.lab button{padding:8px 12px;margin:8px}.lab article{border-left:3px solid #387966;padding:8px 16px;background:#f2faf7}.lab pre{max-height:320px;overflow:auto;white-space:pre-wrap}
</style>
