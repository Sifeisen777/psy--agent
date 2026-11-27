<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { get, streamChat } from '../api/client'

defineProps({
  username: { type: String, default: '' }
})
const emit = defineEmits(['logout'])

const conversations = ref([])
const currentId = ref(null)
const messages = ref([])
const input = ref('')
const streaming = ref(false)
const error = ref('')
const lastCost = ref(null)
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
  try {
    messages.value = await get(`/conversation/${item.id}/messages`)
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
}

async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return

  messages.value.push({ role: 'user', content: text })
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
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        <strong>psy-agent</strong>
      </div>

      <div class="account">
        <span class="account-name" :title="username">
          <span class="avatar">{{ (username || '?').slice(0, 1).toUpperCase() }}</span>
          <span class="account-text">{{ username || '未登录' }}</span>
        </span>
        <button class="logout" @click="emit('logout')">退出</button>
      </div>

      <button class="new-chat" :disabled="streaming" @click="newConversation">+ 新建对话</button>

      <div class="conv-list">
        <button
          v-for="item in conversations"
          :key="item.id"
          class="conv-item"
          :class="{ active: item.id === currentId }"
          @click="openConversation(item)"
        >
          <span class="conv-title">{{ item.title || '未命名对话' }}</span>
          <span class="conv-meta">{{ item.messageCount }} 条 · {{ formatTime(item.lastMessageAt) }}</span>
        </button>
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

      <div ref="listEl" class="messages">
        <p v-if="!messages.length" class="welcome">
          这里是一个可以慢慢说的地方。<br />
          无论你想聊什么，我都会认真听。
        </p>

        <div v-for="(msg, index) in messages" :key="index" class="msg" :class="msg.role">
          <div class="avatar">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
          <div class="bubble">
            <span v-if="msg.content">{{ msg.content }}</span>
            <span v-else-if="msg.pending" class="typing"><i></i><i></i><i></i></span>
          </div>
        </div>
      </div>

      <p v-if="error" class="error bar">{{ error }}</p>

      <footer class="composer">
        <textarea
          v-model="input"
          rows="2"
          maxlength="2000"
          placeholder="说说你现在的心情…（Ctrl + Enter 发送）"
          @keydown.ctrl.enter.prevent="send"
        ></textarea>
        <button v-if="!streaming" class="primary" :disabled="!input.trim()" @click="send">发送</button>
        <button v-else class="danger" @click="stop">停止</button>
      </footer>
    </main>
  </div>
</template>
