const BASE = '/api'
const TOKEN_KEY = 'psy.token'
const USER_KEY = 'psy.username'

/** 登录态失效时广播，由外层回到登录页 */
export const UNAUTHORIZED_EVENT = 'psy:unauthorized'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function getUsername() {
  return localStorage.getItem(USER_KEY) || ''
}

export function saveAuth(token, username) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, username)
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

function authHeaders(extra = {}) {
  const token = getToken()
  return token ? { ...extra, Authorization: `Bearer ${token}` } : extra
}

function notifyUnauthorized() {
  clearAuth()
  window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
}

async function unwrap(res) {
  if (res.status === 401) {
    notifyUnauthorized()
    throw new Error('登录已过期，请重新登录')
  }
  const body = await res.json().catch(() => null)
  if (!body) {
    throw new Error(`响应解析失败（HTTP ${res.status}）`)
  }
  if (body.code !== 0) {
    const err = new Error(body.message || '请求失败')
    err.code = body.code
    throw err
  }
  return body.data
}

export async function get(path) {
  return unwrap(await fetch(BASE + path, { headers: authHeaders() }))
}

export async function post(path, payload) {
  return unwrap(await fetch(BASE + path, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: payload === undefined ? undefined : JSON.stringify(payload)
  }))
}

/** 登录与注册接口不携带令牌 */
export async function postAnonymous(path, payload) {
  return unwrap(await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  }))
}

/**
 * 流式对话。
 *
 * 使用 fetch + ReadableStream 而非 EventSource：后者仅支持 GET，无法传递 JSON 请求体与认证头。
 * 鉴权失败等错误在建立流之前以 JSON 返回，需通过 Content-Type 区分。
 */
export async function streamChat({ conversationId, message, signal, onMeta, onDelta, onDone }) {
  const res = await fetch(BASE + '/chat/stream', {
    method: 'POST',
    headers: authHeaders({
      'Content-Type': 'application/json',
      Accept: 'text/event-stream'
    }),
    body: JSON.stringify({ conversationId, message }),
    signal
  })

  if (res.status === 401) {
    notifyUnauthorized()
    throw new Error('登录已过期，请重新登录')
  }

  const contentType = res.headers.get('content-type') || ''
  if (!contentType.includes('text/event-stream')) {
    const body = await res.json().catch(() => null)
    const err = new Error(body?.message || `请求失败（HTTP ${res.status}）`)
    err.code = body?.code
    throw err
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  const dispatch = (raw) => {
    let event = 'message'
    const dataLines = []
    for (const line of raw.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, ''))
      }
    }
    const data = dataLines.join('\n')
    if (event === 'meta') {
      try {
        onMeta?.(JSON.parse(data))
      } catch {
        // 忽略无法解析的帧
      }
    } else if (event === 'delta') {
      onDelta?.(data)
    } else if (event === 'done') {
      try {
        onDone?.(JSON.parse(data))
      } catch {
        onDone?.({})
      }
    } else if (event === 'error') {
      throw new Error(data || '生成失败')
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let boundary
    while ((boundary = buffer.indexOf('\n\n')) >= 0) {
      const raw = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      if (raw.trim()) dispatch(raw)
    }
  }
}
