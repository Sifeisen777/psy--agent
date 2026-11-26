const BASE = '/api'

/** 后端统一响应体：{ code, message, data, ts }，code !== 0 即为业务失败 */
async function unwrap(res) {
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
  return unwrap(await fetch(BASE + path))
}

export async function post(path, payload) {
  return unwrap(await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: payload === undefined ? undefined : JSON.stringify(payload)
  }))
}

/**
 * 流式对话。
 *
 * 为什么不用 EventSource：它只能发 GET，而我们的接口是 POST + JSON body。
 * 所以用 fetch + ReadableStream 手动解析 SSE 帧。
 *
 * 注意：限流、用户不存在这类错误在建立流之前就返回了 JSON（HTTP 200 + code!=0），
 * 必须靠 Content-Type 区分，不能只看 res.ok。
 */
export async function streamChat({ userId, conversationId, message, signal, onMeta, onDelta, onDone }) {
  const res = await fetch(BASE + '/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ userId, conversationId, message }),
    signal
  })

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
        // SSE 规范：data: 后可能有一个空格，去掉它，但保留正文前导空格
        dataLines.push(line.slice(5).replace(/^ /, ''))
      }
    }
    const data = dataLines.join('\n')
    if (event === 'meta') {
      try { onMeta?.(JSON.parse(data)) } catch { /* 忽略坏帧 */ }
    } else if (event === 'delta') {
      onDelta?.(data)
    } else if (event === 'done') {
      try { onDone?.(JSON.parse(data)) } catch { onDone?.({}) }
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
