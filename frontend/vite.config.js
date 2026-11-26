import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import http from 'node:http'

// 后端端口经常变（8080 被占用时常用 180xx），写死代理目标会反复踩坑。
// 探测优先级：环境变量 VITE_API_TARGET > 自动探测 > 兜底 8080
const CANDIDATE_PORTS = [8080, 18080, 18087, 18088, 18089]

/**
 * 不能只判断"端口有人监听"——8080 上经常是别的程序（Tomcat、nginx、另一个服务），
 * 那样会把代理指错地方。这里发一次极轻的存活探针 /api/system/ping，
 * 确认真的是 psy-agent。
 *
 * 注意不能用 /api/system/health：它会真实调一次模型（约 1.5 秒），
 * 600ms 的超时必然误判为"没有后端"。
 */
function probeAgent(port) {
  return new Promise((resolve) => {
    const req = http.get(
      { host: '127.0.0.1', port, path: '/api/system/ping', timeout: 800 },
      (res) => {
        let body = ''
        res.on('data', (chunk) => { body += chunk })
        res.on('end', () => resolve(res.statusCode === 200 && body.includes('"app":"psy-agent"')))
      }
    )
    req.on('timeout', () => { req.destroy(); resolve(false) })
    req.on('error', () => resolve(false))
  })
}

async function resolveApiTarget() {
  if (process.env.VITE_API_TARGET) {
    return { target: process.env.VITE_API_TARGET, note: '来自 VITE_API_TARGET' }
  }
  const skipped = []
  for (const port of CANDIDATE_PORTS) {
    if (await probeAgent(port)) {
      return { target: `http://localhost:${port}`, note: '自动探测到 psy-agent 后端' }
    }
    skipped.push(port)
  }
  return {
    target: 'http://localhost:8080',
    note: `未探测到 psy-agent 后端（已试 ${skipped.join('/')}），先用默认值；后端起来后重启本命令`
  }
}

export default defineConfig(async () => {
  const { target, note } = await resolveApiTarget()
  console.log(`\n  [proxy] /api  →  ${target}   (${note})\n`)

  return {
    plugins: [vue()],
    server: {
      port: 5173,
      open: true,
      proxy: {
        '/api': {
          target,
          changeOrigin: true
        }
      }
    }
  }
})
