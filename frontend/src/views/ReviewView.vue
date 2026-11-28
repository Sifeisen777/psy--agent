<script setup>
import { computed, onMounted, ref } from 'vue'
import { get, post } from '../api/client'

const emit = defineEmits(['back', 'logout'])

const STATUS_LABELS = {
  PENDING: '待处理',
  CLAIMED: '处理中',
  CLOSED: '已闭环',
  FALSE_POSITIVE: '误报'
}

const tasks = ref([])
const summary = ref(null)
const filter = ref('PENDING')
const loading = ref(false)
const error = ref('')
const closing = ref(null)
const conclusion = ref('')
const busy = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [s, list] = await Promise.all([
      get('/review/summary?hours=24'),
      get(`/review/tasks?status=${filter.value}&limit=50`)
    ])
    summary.value = s
    tasks.value = list || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

function changeFilter(value) {
  filter.value = value
  load()
}

const counts = computed(() => summary.value?.counts || {})

function formatTime(value) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function overdue(task) {
  if (!task.dueAt) return false
  if (task.status === 'CLOSED' || task.status === 'FALSE_POSITIVE') return false
  return new Date(task.dueAt).getTime() < Date.now()
}

async function claim(task) {
  busy.value = true
  error.value = ''
  try {
    await post(`/review/tasks/${task.id}/claim`)
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

function openClose(task) {
  closing.value = task
  conclusion.value = ''
}

async function submitClose(status) {
  if (!closing.value) return
  busy.value = true
  error.value = ''
  try {
    await post(`/review/tasks/${closing.value.id}/close`, {
      status,
      conclusion: conclusion.value.trim() || (status === 'FALSE_POSITIVE' ? '规则误判' : '已处理')
    })
    closing.value = null
    conclusion.value = ''
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="m-page">
    <header class="m-header">
      <div>
        <h1>人工复核</h1>
        <p class="m-sub">
          危机场景的跟进队列。高危工单 15 分钟内首次响应，超时即为逾期；数据来自 t_review_task
        </p>
      </div>
      <div class="m-actions">
        <button class="m-btn" :disabled="loading" @click="load">{{ loading ? '加载中…' : '刷新' }}</button>
        <button class="m-btn" @click="emit('back')">返回对话</button>
        <button class="m-btn" @click="emit('logout')">退出</button>
      </div>
    </header>

    <p v-if="error" class="m-error">{{ error }}</p>

    <section class="m-kpis">
      <div class="m-kpi">
        <span class="m-kpi-label">待处理</span>
        <strong>{{ counts.PENDING ?? 0 }}</strong>
        <span class="m-kpi-foot">还没有人认领</span>
      </div>
      <div class="m-kpi">
        <span class="m-kpi-label">处理中</span>
        <strong>{{ counts.CLAIMED ?? 0 }}</strong>
        <span class="m-kpi-foot">已认领未闭环</span>
      </div>
      <div class="m-kpi">
        <span class="m-kpi-label">逾期</span>
        <strong :class="{ 'r-warn': (summary?.overdue ?? 0) > 0 }">{{ summary?.overdue ?? 0 }}</strong>
        <span class="m-kpi-foot">超过 15 分钟未首次响应</span>
      </div>
      <div class="m-kpi">
        <span class="m-kpi-label">平均首响</span>
        <strong>{{ summary?.avgClaimSeconds == null ? '—' : summary.avgClaimSeconds }}<small>秒</small></strong>
        <span class="m-kpi-foot">近 24 小时已认领工单</span>
      </div>
    </section>

    <section class="m-card">
      <div class="r-toolbar">
        <div class="m-tabs">
          <button
            v-for="s in ['PENDING', 'CLAIMED', 'CLOSED', 'FALSE_POSITIVE']"
            :key="s"
            :class="{ active: filter === s }"
            @click="changeFilter(s)"
          >{{ STATUS_LABELS[s] }}</button>
        </div>
        <span class="r-count">{{ tasks.length }} 条</span>
      </div>

      <p v-if="!tasks.length && !loading" class="m-empty">当前没有工单。危机场景命中后会自动建单。</p>

      <table v-else class="m-table">
        <thead>
          <tr>
            <th>触发时间</th>
            <th>级别</th>
            <th>来源</th>
            <th>片段</th>
            <th>状态</th>
            <th>首次响应时限</th>
            <th>处理人</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in tasks" :key="task.id">
            <td>{{ formatTime(task.createdAt) }}</td>
            <td><span class="r-level">{{ task.level }}</span></td>
            <td>{{ task.source === 'RULE' ? '规则命中' : '模型评估' }}</td>
            <td class="r-excerpt" :title="task.excerpt">{{ task.excerpt }}</td>
            <td><span class="r-status" :class="`r-${task.status.toLowerCase()}`">{{ STATUS_LABELS[task.status] }}</span></td>
            <td :class="{ 'r-warn': overdue(task) }">
              {{ formatTime(task.dueAt) }}<em v-if="overdue(task)"> · 已逾期</em>
            </td>
            <td>{{ task.handler || '—' }}</td>
            <td class="r-actions">
              <button v-if="task.status === 'PENDING'" class="m-btn" :disabled="busy" @click="claim(task)">认领</button>
              <button v-if="task.status === 'CLAIMED'" class="m-btn" :disabled="busy" @click="openClose(task)">关闭</button>
              <span v-if="task.status === 'CLOSED' || task.status === 'FALSE_POSITIVE'" class="r-conclusion" :title="task.conclusion">
                {{ task.conclusion || '—' }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <div v-if="closing" class="r-mask" @click.self="closing = null">
      <div class="r-dialog">
        <h3>闭环工单</h3>
        <p class="r-dialog-excerpt">{{ closing.excerpt }}</p>
        <textarea v-model="conclusion" rows="3" maxlength="255" placeholder="处理结论：联系了谁、结论是什么（误报可写「规则误判」）" />
        <div class="r-dialog-actions">
          <button class="m-btn" :disabled="busy" @click="closing = null">取消</button>
          <button class="m-btn" :disabled="busy" @click="submitClose('FALSE_POSITIVE')">标为误报</button>
          <button class="m-btn r-primary" :disabled="busy" @click="submitClose('CLOSED')">标记已处理</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.r-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.r-count {
  font-size: 12px;
  color: var(--ink-soft);
}
.r-level {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 6px;
  background: #fdecea;
  color: #b3261e;
}
.r-status {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 6px;
  background: #f2efeb;
  color: var(--ink-soft);
}
.r-pending {
  background: #fff4e5;
  color: #a35b00;
}
.r-claimed {
  background: #e8f1fd;
  color: #1b5fb0;
}
.r-closed {
  background: #e9f5ec;
  color: #22713a;
}
.r-false_positive {
  background: #f2efeb;
  color: #7a7168;
}
.r-excerpt {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.r-warn {
  color: #b3261e;
  font-weight: 600;
}
.r-warn em {
  font-style: normal;
  font-size: 11px;
}
.r-actions {
  white-space: nowrap;
}
.r-conclusion {
  font-size: 12px;
  color: var(--ink-soft);
}
.r-mask {
  position: fixed;
  inset: 0;
  background: rgba(30, 25, 20, 0.28);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 30;
}
.r-dialog {
  width: min(460px, 92vw);
  background: var(--panel);
  border-radius: var(--radius);
  padding: 20px;
  box-shadow: 0 18px 48px rgba(40, 30, 20, 0.18);
}
.r-dialog h3 {
  margin: 0 0 8px;
  font-size: 15px;
}
.r-dialog-excerpt {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--ink-soft);
  background: #f7f4f0;
  border-radius: 8px;
  padding: 8px 10px;
}
.r-dialog textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 8px 10px;
  font: inherit;
  font-size: 13px;
  resize: vertical;
}
.r-dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
.r-primary {
  background: var(--brand);
  color: #fff;
  border-color: var(--brand);
}
</style>
