<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { apiFetch } from '@/api/auth.js'
const props = defineProps({ userId: { type: String, required: true } })
const query = ref('轮椅在哪里借用？'), mode = ref('hybrid'), result = ref(null), busy = ref(false), notice = ref('')
const checkpoint = ref(null), hospitalId = ref('DEMO001'), department = ref('内科'), visitDate = ref(''), sessionId = ref(''), schedules = ref(null)
let disposed = false
const controllers = new Set()
const phases = { UNDERSTANDING: '了解需求', NEEDS_CLARIFICATION: '补充需求', READY_FOR_DRAFT: '可以生成草稿', PENDING_CONFIRMATION: '等待亲自确认', CONFIRMED: '已确认预约', CANCELLED: '已取消', RECOVERY_REQUIRED: '等待恢复' }
function selectSession(item) { visitDate.value = item.visitDate; sessionId.value = item.sessionId; notice.value = '已选择场次，请保存已核对需求。尚未生成草稿或预约。' }
const key = `xiaozhi.workflow.${props.userId}`
async function request(path, method = 'GET', body) {
 if (disposed) throw new Error('页面已关闭。')
 const controller = new AbortController(); controllers.add(controller); const timer = setTimeout(() => controller.abort(), 120000)
 try {
 const response = await apiFetch(path, { method, signal: controller.signal, cache: 'no-store', headers: { 'Content-Type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}) })
 if (!response.ok) throw new Error(`操作未完成（HTTP ${response.status}）。请刷新状态后核对，避免重复操作。`)
 return await response.json()
 } finally { clearTimeout(timer); controllers.delete(controller) }
}
async function run(action) { if(busy.value) return; busy.value = true; notice.value = ''; try { await action() } catch(e) { if (!disposed) notice.value = e.message } finally { busy.value = false } }
function accept(cp) { if (disposed) return; checkpoint.value = cp; const f = cp.requirements; hospitalId.value = f.hospitalId || 'DEMO001'; department.value = f.department || '内科'; visitDate.value = f.visitDate || ''; sessionId.value = f.sessionId || ''; try { localStorage.setItem(key,cp.id) } catch { notice.value = '浏览器存储不可用，请记下工作流编号。' } }
const compare = () => run(async () => { result.value = await request('/api/knowledge/compare?' + new URLSearchParams({query:query.value,mode:mode.value})) })
const create = () => run(async () => { const c = await request('/api/conversations','POST',{conversationId:crypto.randomUUID()}); accept(await request('/api/workflows','POST',{conversationId:c.conversationId})); schedules.value=null })
const save = () => run(async () => accept(await request(`/api/workflows/${checkpoint.value.id}/requirements`,'PUT',{version:checkpoint.value.version,hospitalId:hospitalId.value,department:department.value,visitDate:visitDate.value || null,sessionId:sessionId.value || null})))
const searchSessions = () => run(async () => { schedules.value = await request(`/api/workflows/${checkpoint.value.id}/sessions`) })
const refresh = () => run(async () => accept(await request(`/api/workflows/${checkpoint.value.id}`)))
const action = name => { if (!window.confirm(({draft:'核对需求后生成待确认演示草稿？这不会占用号源。',confirm:'确认提交这份演示预约并占用演示号源？',cancel:'确认取消？已确认的预约将释放演示号源。'})[name])) return; return run(async () => accept(await request(`/api/workflows/${checkpoint.value.id}/${name}`,'POST',{version:checkpoint.value.version,confirmed:true}))) }
onMounted(() => { let id; try { id=localStorage.getItem(key) } catch { return } if(id && /^[0-9a-f-]{36}$/.test(id)) run(async () => accept(await request(`/api/workflows/${id}`))) })
onBeforeUnmount(() => { disposed = true; for (const controller of controllers) controller.abort() })
</script>
<template>
<section class="lab">
 <header class="lab-header"><div><span class="eyebrow">可检查的过程</span><h1>AI 工程实验室</h1><p>对照检索资料，查看预约流程的保存与恢复。所有排班和预约均为演示数据。</p></div><span class="demo-pill">实验功能</span></header>
 <p v-if="notice" class="lab-notice" role="status">{{ notice }}</p>
 <div class="lab-grid">
 <section class="lab-card" aria-label="检索对照"><span class="eyebrow">01 · 资料依据</span><h2>检索对照</h2><p class="muted">同一个问题，用不同方案查找资料。评分不是医疗准确率。</p>
 <label>问题<input v-model="query" maxlength="500" :disabled="busy" /></label>
 <label>方案<select v-model="mode" :disabled="busy"><option value="vector">向量检索</option><option value="hybrid">混合检索与特征重排</option><option value="llm">混合检索与模型重排（额外调用模型）</option><option value="dedicated">专用相关性模型重排（额外调用排序服务）</option></select></label>
 <button class="primary" :disabled="busy || !query.trim()" @click="compare">运行检索</button><span v-if="busy" class="muted" role="status">正在处理，请稍候…</span>
 <div v-if="result" class="retrieval-result"><div class="result-heading"><strong>采用 {{ result.accepted.length }} 个片段</strong><span class="demo-pill">{{ result.scoreType }}</span></div><p v-if="result.fallback" class="lab-notice">本次采用回退方案：{{ result.fallback }} {{ result.failureReason }}</p><article v-for="item in result.accepted" :key="item.index"><strong>{{ item.source }}</strong><p>{{ item.text }}</p></article><p v-if="!result.accepted.length" class="muted">没有达到阈值的资料，请调整问题后再试。</p></div>
 <div v-else class="lab-empty">检索结果会显示在这里。点击后才会发起查询。</div>
 </section>
 <section class="lab-card" aria-label="可恢复预约工作流"><span class="eyebrow">02 · 逐步办理</span><h2>可恢复预约工作流</h2><p class="muted">先核对需求，再生成草稿；预约必须单独确认。</p><button :disabled="busy" @click="create">新建演示工作流</button>
 <template v-if="checkpoint">
  <div class="workflow-state"><strong>{{ phases[checkpoint.phase] || checkpoint.phase }}</strong><button :disabled="busy" @click="refresh">刷新状态</button></div>
  <details class="workflow-details"><summary>流程编号与状态</summary><p>{{ checkpoint.id }}</p><p>{{ checkpoint.phase }} · 版本 {{ checkpoint.version }}</p></details>
  <fieldset :disabled="busy || Boolean(checkpoint.draftId)"><legend>核对预约需求</legend><div class="field-grid"><label>演示医院<input v-model="hospitalId" /></label><label>科室<input v-model="department" /></label><label>就诊日期（上海）<input v-model="visitDate" type="date" /></label><label>场次编号<input v-model="sessionId" placeholder="可从下方排班选择" /></label></div><button @click="save">保存已核对需求</button></fieldset>
  <button :disabled="busy" @click="searchSessions">查询已保存需求的排班</button>
  <div v-if="schedules" class="workflow-sessions"><p class="muted">查询不预留号源，选择后需保存需求。</p><button v-for="item in schedules.sessions || []" :key="item.sessionId" :disabled="busy || Boolean(checkpoint.draftId) || !['AVAILABLE','NOT_RELEASED'].includes(item.bookingStatus) || item.referenceRemaining <= 0" @click="selectSession(item)"><strong>{{ item.doctorName }} · {{ item.slotName }}</strong><span>{{ item.visitDate }} · {{ item.startTime }}–{{ item.endTime }}</span><span>参考可用 {{ item.referenceRemaining }} · 选择此场次</span></button><p v-if="!schedules.sessions?.length" class="muted">没有可展示的场次。</p><details><summary>查看完整排班数据</summary><pre>{{ JSON.stringify(schedules, null, 2) }}</pre></details></div>
  <div class="workflow-actions"><button :disabled="busy || !['READY_FOR_DRAFT','RECOVERY_REQUIRED'].includes(checkpoint.phase)" @click="action('draft')">生成或恢复草稿</button><button class="primary" :disabled="busy || checkpoint.phase !== 'PENDING_CONFIRMATION'" @click="action('confirm')">确认演示预约</button><button class="danger" :disabled="busy || !['PENDING_CONFIRMATION','CONFIRMED'].includes(checkpoint.phase)" @click="action('cancel')">取消演示预约或草稿</button></div>
  <p v-if="checkpoint.draft" class="lab-notice">{{ checkpoint.draft.message }}</p>
 </template>
 <div v-else class="lab-empty">新建后可逐步保存需求。重新打开此页面时，会恢复当前账号上次的流程。</div>
 </section></div>
</section>
</template>
<style scoped>
.lab {
  max-width:1320px;
  margin:0 auto;
  padding:36px 28px
}
.lab-header {
  display:flex;
  justify-content:space-between;
  align-items:flex-start;
  gap:16px;
  margin-bottom:26px
}
.lab-header h1 {
  font-size:27px;
  font-weight:550;
  margin:8px 0
}
.lab-header p {
  font-size:14px;
  color:#6b8075
}
.lab-grid {
  display:grid;
  grid-template-columns:1fr 1.1fr;
  gap:22px;
  align-items:start
}
.lab-card {
  background:white;
  border:1px solid var(--line);
  border-radius:14px;
  padding:26px;
  min-width:0
}
.lab-card h2 {
  font-size:20px;
  font-weight:600;
  margin:8px 0
}
.lab .muted {
  font-size:12px
}
.lab label {
  display:block;
  font-size:13px;
  margin:16px 0
}
.lab input,.lab select {
  display:block;
  width:100%;
  padding:10px 12px;
  border:1px solid #cfded4;
  background:#fafcf9;
  border-radius:8px;
  color:#365d47;
  margin-top:6px;
  min-width:0
}
.lab button {
  padding:9px 13px;
  border:1px solid #d2e2d6;
  border-radius:8px;
  background:#edf5ef;
  color:#466e52;
  margin:8px 8px 0 0;
  font-size:12px
}
.lab button.primary {
  background:#087f75;
  color:white;
  border-color:#087f75
}
.lab button.danger {
  background:#fff8f1;
  color:#956546
}
.lab article {
  border-left:3px solid #86b8a4;
  padding:12px 16px;
  background:#f7faf5;
  margin-top:12px;
  font-size:13px
}
.lab article p {
  white-space:pre-wrap;
  max-height:240px;
  overflow:auto
}
.lab-notice {
  background:#f9f3e6;
  border:1px solid #eadfc7;
  padding:12px;
  border-radius:8px;
  color:#836a3f;
  font-size:13px;
  overflow-wrap:anywhere
}
.lab-empty {
  margin-top:24px;
  padding:25px 20px;
  border:1px dashed #d7e2d4;
  border-radius:10px;
  color:#789077;
  font-size:13px;
  line-height:1.9
}
.workflow-state,.result-heading {
  display:flex;
  justify-content:space-between;
  align-items:center;
  gap:8px;
  margin-top:20px;
  font-size:14px;
  flex-wrap:wrap
}
.workflow-details {
  font-size:11px;
  color:#7a8b7c;
  overflow-wrap:anywhere;
  margin-top:10px
}
.lab details summary {
  cursor:pointer
}
.lab fieldset {
  margin-top:18px;
  border:1px solid #dee8db;
  border-radius:10px;
  padding:10px 14px
}
.lab legend {
  font-size:12px;
  color:#648267
}
.field-grid {
  display:grid;
  grid-template-columns:1fr 1fr;
  gap:0 12px
}
.field-grid label {
  margin:8px 0
}
.workflow-actions {
  border-top:1px solid var(--line);
  margin-top:20px;
  padding-top:12px
}
.workflow-sessions>button {
  display:flex;
  flex-direction:column;
  align-items:flex-start;
  width:100%;
  text-align:left;
  gap:5px;
  background:#f8fbf6
}
.workflow-sessions>details {
  margin-top:12px;
  font-size:12px;
  color:#6f826f
}
.lab pre {
  font-size:11px;
  max-height:240px;
  overflow:auto;
  white-space:pre-wrap;
  overflow-wrap:anywhere
}
@media(max-width:900px) {
  .lab-grid {
    grid-template-columns:1fr
  }
  .lab {
    padding:24px 16px
  }
}
@media(max-width:480px) {
  .lab {
    padding:20px 10px
  }
  .lab-card {
    padding:18px
  }
  .field-grid {
    grid-template-columns:1fr
  }
  .lab-header h1 {
    font-size:23px
  }
}
</style>
