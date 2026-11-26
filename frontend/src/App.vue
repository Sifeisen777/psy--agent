<script setup>
import { ref } from 'vue'
import ChatView from './views/ChatView.vue'
import { get, post } from './api/client'

/**
 * 两个独立的变量，不能合并：
 * - enteredId 决定进入哪个页面（v-if 的依据）
 * - inputId   是输入框的草稿值
 * 如果输入框直接绑定 enteredId，每敲一个字符 v-if 就会翻转为 true，人还没输完就跳进对话页了。
 */
const enteredId = ref(localStorage.getItem('psy.userId') || '')
const inputId = ref('')
const username = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

function commit(id) {
  enteredId.value = String(id)
  localStorage.setItem('psy.userId', enteredId.value)
  inputId.value = ''
  error.value = ''
}

async function enter() {
  const value = inputId.value.trim()
  if (!/^\d{1,19}$/.test(value)) {
    error.value = 'userId 应为纯数字，可先用下面的表单注册一个'
    return
  }
  busy.value = true
  error.value = ''
  try {
    // 先探一次会话列表：userId 不存在会返回 40400，
    // 这样在入口处就能报错，而不是进去发第一条消息才发现
    await get(`/conversation/list?userId=${value}`)
    commit(value)
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function register() {
  if (!username.value.trim() || !password.value) {
    error.value = '用户名和密码都要填'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const data = await post('/auth/register', {
      username: username.value.trim(),
      password: password.value
    })
    commit(data.userId)
    username.value = ''
    password.value = ''
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

function logout() {
  localStorage.removeItem('psy.userId')
  enteredId.value = ''
  username.value = ''
  password.value = ''
  error.value = ''
}
</script>

<template>
  <ChatView v-if="enteredId" :user-id="enteredId" @logout="logout" />

  <div v-else class="entry">
    <div class="card">
      <h1>psy-agent</h1>
      <p class="sub">心理支持助手</p>

      <label class="field">
        <span>已有 userId？直接进入</span>
        <input
          v-model="inputId"
          inputmode="numeric"
          placeholder="例如 2125804467377700865"
          @keyup.enter="enter"
        />
      </label>
      <button class="primary" :disabled="busy" @click="enter">
        {{ busy ? '校验中…' : '进入' }}
      </button>

      <div class="divider"><span>或注册新账号</span></div>

      <label class="field">
        <span>用户名</span>
        <input v-model="username" maxlength="64" placeholder="3-64 位，已注册过会提示" />
      </label>
      <label class="field">
        <span>密码</span>
        <input v-model="password" type="password" maxlength="64" placeholder="6-64 位" @keyup.enter="register" />
      </label>
      <button class="primary" :disabled="busy" @click="register">
        {{ busy ? '注册中…' : '注册并进入' }}
      </button>

      <p v-if="error" class="error">{{ error }}</p>
      <p class="hint">当前版本尚未接入身份验证，userId 由前端携带；后续将改为令牌校验。</p>
    </div>
  </div>
</template>
