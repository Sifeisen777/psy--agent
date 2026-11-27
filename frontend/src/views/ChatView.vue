<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { del, get, streamChat } from '../api/client'

defineProps({
  username: { type: String, default: '' }
})
const emit = defineEmits(['logout'])

marked.setOptions({ breaks: true, gfm: true })

/**
 * 渲染模型输出的 Markdown。
 * <p>
 * 必须经过 DOMPurify 净化：模型输出属于不可信内容，一旦被注入
 * `<img src=x onerror=...>` 之类的片段，直接 v-html 就是可执行的 XSS。
 * 用户消息不走这里——用户自己的输入按纯文本处理，避免格式错乱与自我注入。
 */
function renderMarkdown(text) {
  if (!text) return ''
  return DOMPurify.sanitize(marked.parse(text))
}

const conversations = ref([])
const currentId = ref(null)
const messages = ref([])
const input = ref('')
const streaming = ref(false)
const error = ref('')
const lastCost = ref(null)
const crisisAlert = ref(false)
const listEl = ref(null)
const controller = ref(null)

onMounted(loadConversations)

async function loadConversations() {
  try {
    conversations.value = await get('/conversation/list')
  } catch (e) {
    error.value = e.message
  }
}

async function openConversation(item) {
  if (streaming.value) return
  currentId.value = item.id
  error.value = ''
  lastCost.value = null
  crisisAlert.value = false
  try {
    messages.value = await get(`/conversation/${item.id}/messages`)
    // 历史消息只带 risk_level，若最近一条用户消息为高风险，仍需给出求助入口
    const lastUser = [...messages.value].reverse().find((m) => m.role === 'user')
    crisisAlert.value = lastUser?.riskLevel === 'HIGH'
    scrollToBottom(true)
  } catch (e) {
    error.value = e.message
  }
}

function newConversation() {
  if (streaming.value) return
  currentId.value = null
  messages.value = []
  input.value = ''
  error.value = ''
  lastCost.value = null
  crisisAlert.value = false
}

/**
 * 删除会话。生成中不允许删除，避免流式回调把消息写回已删除的会话。
 */
async function removeConversation(item, event) {
  event.stopPropagation()
  if (streaming.value) {
    error.value = '请先停止当前生成，再删除会话'
    return
  }
  if (!window.confirm(`删除「${item.title || '未命名对话'}」？该会话将从列表移除。`)) {
    return
  }
  try {
    await del(`/conversation/${item.id}`)
    conversations.value = conversations.value.filter((c) => c.id !== item.id)
    if (currentId.value === item.id) {
      newConversation()
    }
  } catch (e) {
    error.value = e.message
  }
}

/**
 * 输入框键盘行为：Enter 发送，Shift + Enter 换行。
 * <p>
 * 中文输入法组合期间按 Enter 是"确认候选词"，此时必须放行，
 * 否则选词会被误当成发送。isComposing 与 keyCode 229 用于覆盖不同浏览器的实现差异。
 */
function onKeydown(e) {
  if (e.key !== 'Enter' || e.shiftKey) return
  if (e.isComposing || e.keyCode === 229) return
  e.preventDefault()
  send()
}

async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return

  const userMsg = { role: 'user', content: text }
  messages.value.push(userMsg)
  // 先把 assistant 气泡占位推进去，后续 delta 直接往里追加，视觉上就是"打字机"
  const reply = { role: 'assistant', content: '', pending: true }
  messages.value.push(reply)

  input.value = ''
  streaming.value = true
  error.value = ''
  lastCost.value = null
  scrollToBottom()

  const abort = new AbortController()
  controller.value = abort

  try {
    await streamChat({
      conversationId: currentId.value,
      message: text,
      signal: abort.signal,
      onMeta: (meta) => {
        // 新建会话时 id 由后端生成，前端靠 meta 帧拿到，否则后续消息会一直建新会话
        currentId.value = meta.conversationId
      },
      onDelta: (chunk) => {
        reply.content += chunk
        scrollToBottom()
      },
      onDone: (info) => {
        lastCost.value = info
        if (info.citations?.length) {
          reply.citations = info.citations
        }
        if (info.emotion) {
          // 评估结果描述的是用户此刻的状态，挂到用户这条消息上
          userMsg.emotion = info.emotion
          userMsg.riskLevel = info.emotion.riskLevel
          crisisAlert.value = info.emotion.riskLevel === 'HIGH'
        }
        reply.pending = false
      }
    })
  } catch (e) {
    if (e.name === 'AbortError') {
      error.value = '已停止生成'
    } else {
      error.value = e.message
    }
    // 一个字都没生成，就把空壳气泡删掉，避免界面上留个空白框
    if (!reply.content) {
      messages.value = messages.value.filter((m) => m !== reply)
    }
  } finally {
    streaming.value = false
    controller.value = null
    reply.pending = false
    await loadConversations()
    scrollToBottom(true)
  }
}

function stop() {
  controller.value?.abort()
}

/**
 * 只有用户本来就在底部附近时才自动滚动。
 * 否则用户往上翻历史时，每来一个字就把页面拽回底部，根本没法读。
 */
function scrollToBottom(force = false) {
  nextTick(() => {
    const el = listEl.value
    if (!el) return
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 120
    if (force || nearBottom) {
      el.scrollTop = el.scrollHeight
    }
  })
}

function formatTime(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(5, 16)
}

const RISK_TEXT = {
  NONE: '平稳',
  LOW: '低风险',
  MEDIUM: '需关注',
  HIGH: '高风险'
}

function riskText(level) {
  return RISK_TEXT[level] || RISK_TEXT.LOW
}

function riskClass(level) {
  return 'risk-' + String(level || 'LOW').toLowerCase()
}

/**
 * 引用按文档合并：同一篇可能命中多个片段，逐条列出会让来源标签变得很长。
 * 合并后显示为「标题 [1][2]」，与回答中的编号对应。
 */
function citationGroups(citations) {
  const groups = new Map()
  for (const item of citations || []) {
    if (!groups.has(item.title)) {
      groups.set(item.title, [])
    }
    groups.get(item.title).push(item.index)
  }
  return [...groups.entries()].map(([title, indexes]) => ({ title, indexes }))
}
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        <strong>psy-agent</strong>
      </div>

      <div class="account">
        <span class="account-name" :title="username">
          <span class="avatar-mini">{{ (username || '?').slice(0, 1).toUpperCase() }}</span>
          <span class="account-text">{{ username || '未登录' }}</span>
        </span>
        <button class="logout" @click="emit('logout')">退出</button>
      </div>

      <button class="new-chat" :disabled="streaming" @click="newConversation">+ 新建对话</button>

      <div class="conv-list">
        <div
          v-for="item in conversations"
          :key="item.id"
          class="conv-item"
          :class="{ active: item.id === currentId }"
          @click="openConversation(item)"
        >
          <div class="conv-body">
            <span class="conv-title">{{ item.title || '未命名对话' }}</span>
            <span class="conv-meta">{{ item.messageCount }} 条 · {{ formatTime(item.lastMessageAt) }}</span>
          </div>
          <button
            class="conv-del"
            title="删除会话"
            aria-label="删除会话"
            :disabled="streaming"
            @click="removeConversation(item, $event)"
          >×</button>
        </div>
        <p v-if="!conversations.length" class="empty">还没有对话，直接开始聊吧</p>
      </div>
    </aside>

    <main class="main">
      <header class="main-header">
        <span class="title">
          {{ conversations.find((c) => c.id === currentId)?.title || '新对话' }}
        </span>
        <span v-if="lastCost" class="cost">耗时 {{ lastCost.costMs }}ms · {{ lastCost.length }} 字</span>
      </header>

      <div v-if="crisisAlert" class="crisis">
        如果你正在经历危险，请让身边的人陪着你，并拨打全国心理援助热线
        <strong>12356</strong>（24 小时）；紧急情况请拨打 <strong>120</strong>。
      </div>

      <div ref="listEl" class="messages">
        <p v-if="!messages.length" class="welcome">
          这里是一个可以慢慢说的地方。<br />
          无论你想聊什么，我都会认真听。
        </p>

        <div v-for="(msg, index) in messages" :key="index" class="msg" :class="msg.role">
          <div class="avatar">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
          <div class="bubble-wrap">
            <div class="bubble" :class="{ md: msg.role === 'assistant' }">
              <template v-if="msg.content">
                <div
                  v-if="msg.role === 'assistant'"
                  class="md-body"
                  v-html="renderMarkdown(msg.content)"
                ></div>
                <span v-else>{{ msg.content }}</span>
              </template>
              <span v-else-if="msg.pending" class="typing"><i></i><i></i><i></i></span>
            </div>
            <div
              v-if="msg.citations?.length"
              class="citations"
            >
              <span class="citations-label">参考</span>
              <span
                v-for="group in citationGroups(msg.citations)"
                :key="group.title"
                class="citation-item"
              >
                {{ group.title }}
                <em v-for="i in group.indexes" :key="i">[{{ i }}]</em>
              </span>
            </div>
            <div
              v-if="msg.emotion || msg.riskLevel"
              class="emotion-tag"
              :class="riskClass(msg.emotion?.riskLevel || msg.riskLevel)"
            >
              <template v-if="msg.emotion">
                {{ msg.emotion.emotion }} · 强度 {{ msg.emotion.intensity }} · {{ riskText(msg.emotion.riskLevel) }}
              </template>
              <template v-else>{{ riskText(msg.riskLevel) }}</template>
            </div>
          </div>
        </div>
      </div>

      <p v-if="error" class="error bar">{{ error }}</p>

      <footer class="composer">
        <textarea
          v-model="input"
          rows="2"
          maxlength="2000"
          placeholder="说说你现在的心情…（Enter 发送，Shift + Enter 换行）"
          @keydown="onKeydown"
        ></textarea>
        <button v-if="!streaming" class="primary" :disabled="!input.trim()" @click="send">发送</button>
        <button v-else class="danger" @click="stop">停止</button>
      </footer>
    </main>
  </div>
</template>
