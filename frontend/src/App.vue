<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import ChatView from './views/ChatView.vue'
import {
  clearAuth,
  getToken,
  getUsername,
  postAnonymous,
  saveAuth,
  UNAUTHORIZED_EVENT
} from './api/client'

const authed = ref(!!getToken())
const username = ref('')
const password = ref('')
const mode = ref('login')
const error = ref('')
const busy = ref(false)

function handleUnauthorized() {
  authed.value = false
  error.value = '登录已过期，请重新登录'
}

onMounted(() => window.addEventListener(UNAUTHORIZED_EVENT, handleUnauthorized))
onUnmounted(() => window.removeEventListener(UNAUTHORIZED_EVENT, handleUnauthorized))

async function submit() {
  if (!username.value.trim() || !password.value) {
    error.value = '用户名和密码都需要填写'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const path = mode.value === 'login' ? '/auth/login' : '/auth/register'
    const data = await postAnonymous(path, {
      username: username.value.trim(),
      password: password.value
    })
    saveAuth(data.token, data.username)
    password.value = ''
    authed.value = true
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

function logout() {
  clearAuth()
  authed.value = false
  username.value = ''
  password.value = ''
  error.value = ''
}
</script>

<template>
  <ChatView v-if="authed" :username="getUsername()" @logout="logout" />

  <div v-else class="entry">
    <div class="card">
      <h1>psy-agent</h1>
      <p class="sub">心理支持助手</p>

      <div class="tabs">
        <button :class="{ active: mode === 'login' }" @click="mode = 'login'; error = ''">登录</button>
        <button :class="{ active: mode === 'register' }" @click="mode = 'register'; error = ''">注册</button>
      </div>

      <label class="field">
        <span>用户名</span>
        <input v-model="username" maxlength="64" placeholder="3-64 位" />
      </label>
      <label class="field">
        <span>密码</span>
        <input
          v-model="password"
          type="password"
          maxlength="64"
          placeholder="6-64 位"
          @keyup.enter="submit"
        />
      </label>

      <button class="primary" :disabled="busy" @click="submit">
        {{ busy ? '处理中…' : mode === 'login' ? '登录' : '注册并登录' }}
      </button>

      <p v-if="error" class="error">{{ error }}</p>
    </div>
  </div>
</template>
