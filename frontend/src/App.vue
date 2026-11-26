<script setup>
import { onMounted, ref } from 'vue'
import ChatView from './views/ChatView.vue'
import { post } from './api/client'

const userId = ref(localStorage.getItem('psy.userId') || '')
const username = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

async function register() {
  error.value = ''
  if (!username.value || !password.value) {
    error.value = '用户名和密码都要填'
    return
  }
  busy.value = true
  try {
    const data = await post('/auth/register', {
      username: username.value.trim(),
      password: password.value
    })
    userId.value = String(data.userId)
    localStorage.setItem('psy.userId', userId.value)
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

function enter() {
  const value = String(userId.value).trim()
  if (!/^\d+$/.test(value)) {
    error.value = 'userId 必须是纯数字'
    return
  }
  error.value = ''
  userId.value = value
  localStorage.setItem('psy.userId', value)
}

function logout() {
  localStorage.removeItem('psy.userId')
  userId.value = ''
  username.value = ''
  password.value = ''
}
</script>

<template>
  <ChatView v-if="userId" :user-id="userId" @logout="logout" />

  <div v-else class="entry">
    <div class="card">
      <h1>psy-agent</h1>
      <p class="sub">心理支持助手 · 阶段 3</p>

      <label class="field">
        <span>已有 userId？直接进入</span>
        <input v-model="userId" placeholder="例如 2125804034101911554" />
      </label>
      <button class="primary" :disabled="busy" @click="enter">进入</button>

      <div class="divider"><span>或注册新账号</span></div>

      <label class="field">
        <span>用户名</span>
        <input v-model="username" maxlength="64" placeholder="3-64 位" />
      </label>
      <label class="field">
        <span>密码</span>
        <input v-model="password" type="password" maxlength="64" placeholder="6-64 位" @keyup.enter="register" />
      </label>
      <button class="primary" :disabled="busy" @click="register">
        {{ busy ? '注册中…' : '注册并进入' }}
      </button>

      <p v-if="error" class="error">{{ error }}</p>
      <p class="hint">阶段 3 尚未接入登录鉴权，userId 由前端携带；阶段 4 会换成 JWT。</p>
    </div>
  </div>
</template>
