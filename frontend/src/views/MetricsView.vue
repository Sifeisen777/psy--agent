<script setup>
import { computed, onMounted, ref } from 'vue'
import { get } from '../api/client'

const emit = defineEmits(['back', 'logout'])

const hours = ref(24)
const data = ref(null)
const traces = ref([])
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [overview, list] = await Promise.all([
      get(`/metrics/overview?hours=${hours.value}`),
      get('/metrics/traces?limit=12')
    ])
    data.value = overview
    traces.value = list || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

function changeHours(value) {
  hours.value = value
  load()
}

const trend = computed(() => data.value?.trend || [])
const maxTrend = computed(() => Math.max(1, ...trend.value.map((t) => Number(t.cnt) || 0)))

function points(key) {
  const list = trend.value
  if (!list.length) return ''
  const width = 600
  const left = 30
  const bottom = 150
  const height = 120
  return list
    .map((item, i) => {
      const x = left + (list.length === 1 ? 0 : (i * width) / (list.length - 1))
      const value = Number(item[key]) || 0
      const y = bottom - (value / maxTrend.value) * height
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
}

const totalPoints = computed(() => points('cnt'))
const crisisPoints = computed(() => points('crisis_cnt'))
const toolCalls = computed(() =>
  (data.value?.tools || []).reduce((sum, t) => sum + (Number(t.count) || 0), 0)
)

function fmtTime(value) {
  if (value == null) return '-'
  if (typeof value === 'number') {
    return new Date(value).toLocaleString('zh-CN', { hour12: false }).slice(5)
  }
  return String(value).replace('T', ' ').slice(0, 16)
}

function bucketLabel(value) {
  return String(value || '').slice(5, 16)
}

function riskText(level) {
  return ({ HIGH: '高', MEDIUM: '中', LOW: '低' })[level] || level || '-'
}
</script>

<template>
  <div class="m-page">
    <header class="m-header">
      <div>
        <h1>运行观测</h1>
        <p class="m-sub">单轮对话的路由、延迟与工具调用，数据来自 t_chat_trace / t_tool_call</p>
      </div>
      <div class="m-actions">
        <div class="m-tabs">
          <button
            v-for="h in [24, 72, 168]"
            :key="h"
            :class="{ active: hours === h }"
            @click="changeHours(h)"
          >{{ h }}h</button>
        </div>
        <button class="m-btn" :disabled="loading" @click="load">{{ loading ? '加载中…' : '刷新' }}</button>
        <button class="m-btn" @click="emit('back')">返回对话</button>
        <button class="m-btn" @click="emit('logout')">退出</button>
      </div>
    </header>

    <p v-if="error" class="m-error">{{ error }}</p>

    <template v-if="data">
      <section class="m-kpis">
        <div class="m-kpi">
          <span class="m-kpi-label">对话轮次</span>
          <strong>{{ data.totalChats }}</strong>
          <span class="m-kpi-foot">mock {{ data.mockCount }} · 断连 {{ data.clientGoneCount }}</span>
        </div>
        <div class="m-kpi">
          <span class="m-kpi-label">危机 / 需人工复核</span>
          <strong class="danger-text">{{ data.hitlCount }}</strong>
          <span class="m-kpi-foot">CRISIS 路由单独计入分布</span>
        </div>
        <div class="m-kpi">
          <span class="m-kpi-label">首字延迟</span>
          <strong>{{ data.ttftP50Ms }}<small>ms p50</small></strong>
          <span class="m-kpi-foot">p95 {{ data.ttftP95Ms }}ms · 样本 {{ data.ttftSamples }}</span>
        </div>
        <div class="m-kpi">
          <span class="m-kpi-label">工具调用</span>
          <strong>{{ toolCalls }}</strong>
          <span class="m-kpi-foot">{{ (data.tools || []).length }} 类工具 · 平均总耗时 {{ data.avgTotalMs }}ms</span>
        </div>
      </section>

      <section class="m-grid">
        <div class="m-card">
          <h2>路由分布</h2>
          <div v-for="item in data.routes" :key="item.route" class="m-bar-row">
            <span class="m-bar-label">{{ item.label }}</span>
            <div class="m-bar-track">
              <div class="m-bar-fill" :style="{ width: item.pct + '%' }"></div>
            </div>
            <span class="m-bar-value">{{ item.count }} <em>{{ item.pct }}%</em></span>
          </div>
          <p class="m-note">四类路由补齐零值，避免"没有危机"被误读成"没统计"。</p>
        </div>

        <div class="m-card">
          <h2>近 {{ data.trendHours || hours }} 小时趋势（逐小时，零点补齐）</h2>
          <svg v-if="trend.length" class="m-chart" viewBox="0 0 640 180" preserveAspectRatio="none">
            <line x1="30" y1="150" x2="630" y2="150" class="m-axis" />
            <line x1="30" y1="90" x2="630" y2="90" class="m-grid-line" />
            <line x1="30" y1="30" x2="630" y2="30" class="m-grid-line" />
            <polyline :points="totalPoints" class="m-line-total" />
            <polyline :points="crisisPoints" class="m-line-crisis" />
          </svg>
          <p v-else class="m-empty">暂无数据，先发几条消息再回来</p>
          <div v-if="trend.length" class="m-axis-labels">
            <span>{{ bucketLabel(trend[0].bucket) }}</span>
            <span class="m-legend"><i class="dot total"></i>对话 <i class="dot crisis"></i>危机</span>
            <span>{{ bucketLabel(trend[trend.length - 1].bucket) }}</span>
          </div>
        </div>
      </section>

      <section class="m-grid">
        <div class="m-card">
          <h2>工具调用</h2>
          <table class="m-table">
            <thead><tr><th>工具</th><th>次数</th><th>成功</th><th>成功率</th><th>平均耗时</th></tr></thead>
            <tbody>
              <tr v-for="t in data.tools" :key="t.tool">
                <td><code>{{ t.tool }}</code></td>
                <td>{{ t.count }}</td>
                <td>{{ t.success }}</td>
                <td>{{ t.successRate }}%</td>
                <td>{{ t.avgCostMs }}ms</td>
              </tr>
              <tr v-if="!data.tools?.length"><td colspan="5" class="m-empty">暂无调用</td></tr>
            </tbody>
          </table>
        </div>

        <div class="m-card">
          <h2>最近轨迹</h2>
          <table class="m-table">
            <thead><tr><th>时间</th><th>路由</th><th>首字</th><th>总耗时</th><th>风险</th><th>工具</th></tr></thead>
            <tbody>
              <tr v-for="t in traces" :key="t.trace_id">
                <td>{{ fmtTime(t.created_at) }}</td>
                <td>{{ t.route }}</td>
                <td>{{ t.ttft_ms ?? '-' }}</td>
                <td>{{ t.total_ms }}ms</td>
                <td>{{ riskText(t.risk_level) }}</td>
                <td class="m-tool-cell">{{ t.tool_names || '-' }}</td>
              </tr>
              <tr v-if="!traces.length"><td colspan="6" class="m-empty">暂无轨迹</td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>

    <p v-else-if="!loading" class="m-empty">加载观测数据…</p>
  </div>
</template>
