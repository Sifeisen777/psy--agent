#!/usr/bin/env python3
"""psy-agent SSE 压测 runner（标准库实现，无第三方依赖）。

用法：
    python eval/load_test.py --base-url http://localhost:18087 --users 20 --per-user 3 --concurrency 10

说明：
    - 每个临时账号只发 per-user 次请求，规避单人 10 次/分钟限流；
    - 统计客户端端到端耗时、服务端 done.costMs 与首字延迟 ttftMs 的 p50/p95/p99；
    - 压测包含完整链路（鉴权 → 编排 → 工具/检索 → 模型 → 落库 → 观测写入），
      因此数字代表"用户实际感受到的延迟"，不是裸模型延迟。
"""

import argparse
import json
import os
import statistics
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime

urllib.request.install_opener(
    urllib.request.build_opener(urllib.request.ProxyHandler({}))
)

MESSAGES = [
    "你好",
    "我今天有点难过",
    "最近总是睡不着，有什么办法吗",
    "我最近压力有点大，怎么调节",
    "我想做个量表",
    "最近系统运行情况怎么样",
    "今天天气怎么样",
    "谢谢你陪我聊",
]


def http_json(method, url, payload=None, token=None, timeout=30):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def parse_block(block):
    event, data_lines = "message", []
    for line in block.split("\n"):
        if line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:"):
            data_lines.append(line[5:].lstrip(" "))
    return event, "\n".join(data_lines)


def one_request(base, token, message, timeout):
    body = json.dumps({"message": message}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        base + "/api/chat/stream",
        data=body,
        headers={"Content-Type": "application/json", "Accept": "text/event-stream",
                 "Authorization": "Bearer " + token},
        method="POST",
    )
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            ctype = resp.headers.get("Content-Type", "")
            if "text/event-stream" not in ctype:
                raw = resp.read().decode("utf-8", "ignore")
                return {"ok": False, "reason": "非流式响应 " + raw[:120],
                        "clientMs": int((time.time() - started) * 1000)}
            done = {}
            first_chunk_at = None
            buf = ""
            while True:
                chunk = resp.read(4096)
                if not chunk:
                    break
                buf += chunk.decode("utf-8", "ignore")
                while "\n\n" in buf:
                    block, buf = buf.split("\n\n", 1)
                    if not block.strip():
                        continue
                    name, data = parse_block(block)
                    if name == "delta" and first_chunk_at is None:
                        first_chunk_at = time.time()
                    elif name == "done":
                        try:
                            done = json.loads(data)
                        except json.JSONDecodeError:
                            pass
                    elif name == "error":
                        return {"ok": False, "reason": data[:120],
                                "clientMs": int((time.time() - started) * 1000)}
        return {
            "ok": True,
            "clientMs": int((time.time() - started) * 1000),
            "serverMs": done.get("costMs"),
            "ttftMs": done.get("ttftMs"),
            "route": done.get("route"),
            "traceId": done.get("traceId"),
        }
    except Exception as e:
        return {"ok": False, "reason": str(e)[:120],
                "clientMs": int((time.time() - started) * 1000)}


def percentile(sorted_values, p):
    if not sorted_values:
        return 0
    index = max(0, min(len(sorted_values) - 1, int(p / 100.0 * len(sorted_values)) - 1))
    return sorted_values[index]


def stat_line(name, values):
    if not values:
        return "| %s | 0 | - | - | - | - | - |" % name
    ordered = sorted(values)
    return "| %s | %d | %dms | %dms | %dms | %dms | %dms |" % (
        name, len(values), int(statistics.mean(ordered)),
        percentile(ordered, 50), percentile(ordered, 95), percentile(ordered, 99),
        ordered[-1])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:18087")
    parser.add_argument("--users", type=int, default=20)
    parser.add_argument("--per-user", type=int, default=3)
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "reports", "load-report.md"))
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    total = args.users * args.per_user
    stamp = int(time.time() * 1000)
    print("注册 %d 个账号，共 %d 个请求，并发 %d…" % (args.users, total, args.concurrency))
    tokens = []
    for i in range(args.users):
        result = http_json("POST", base + "/api/auth/register",
                           {"username": "load_%d_%d" % (stamp, i), "password": "load-pass-123"})
        tokens.append((result.get("data") or {})["token"])

    jobs = []
    for i in range(total):
        jobs.append((tokens[i % len(tokens)], MESSAGES[i % len(MESSAGES)]))

    results = []
    wall_start = time.time()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(one_request, base, token, msg, args.timeout)
                   for token, msg in jobs]
        for i, future in enumerate(as_completed(futures), 1):
            results.append(future.result())
            if i % 10 == 0:
                print("  完成 %d/%d" % (i, total))
    wall_ms = int((time.time() - wall_start) * 1000)

    ok = [r for r in results if r["ok"]]
    failed = [r for r in results if not r["ok"]]
    client_times = [r["clientMs"] for r in ok]
    server_times = [r["serverMs"] for r in ok if isinstance(r.get("serverMs"), int)]
    ttft = [r["ttftMs"] for r in ok if isinstance(r.get("ttftMs"), int)]
    routes = {}
    for r in ok:
        routes[r.get("route") or "-"] = routes.get(r.get("route") or "-", 0) + 1

    reasons = {}
    for r in failed:
        reasons[r["reason"]] = reasons.get(r["reason"], 0) + 1

    lines = [
        "# psy-agent SSE 压测报告",
        "",
        "- 生成时间：%s" % datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "- 服务地址：`%s`" % base,
        "- 请求总数：**%d**，账号 %d（每人 %d 次，规避限流），并发 %d" % (
            total, args.users, args.per_user, args.concurrency),
        "- 总墙钟时间：%dms，等效吞吐 **%.2f req/s**" % (wall_ms, total * 1000.0 / max(1, wall_ms)),
        "- 成功 %d / 失败 %d，成功率 **%.1f%%**" % (
            len(ok), len(failed), 100.0 * len(ok) / max(1, total)),
        "",
        "## 延迟分布",
        "",
        "| 指标 | 样本 | 平均 | p50 | p95 | p99 | 最大 |",
        "| --- | --- | --- | --- | --- | --- | --- |",
        stat_line("客户端端到端", client_times),
        stat_line("服务端 costMs", server_times),
        stat_line("首字延迟 ttftMs", ttft),
        "",
        "## 路由分布（成功请求）",
        "",
        "| 路由 | 次数 | 占比 |",
        "| --- | --- | --- |",
    ]
    for route, count in sorted(routes.items(), key=lambda kv: -kv[1]):
        lines.append("| %s | %d | %.1f%% |" % (route, count, 100.0 * count / max(1, len(ok))))
    lines += ["", "## 失败原因", ""]
    if reasons:
        for reason, count in sorted(reasons.items(), key=lambda kv: -kv[1]):
            lines.append("- `%s` × %d" % (reason.replace("\n", " "), count))
    else:
        lines.append("无失败请求。")
    lines += [
        "",
        "## 说明",
        "",
        "- 压测覆盖完整链路：鉴权 → 编排 → 检索/工具 → 模型 → 落库 → 观测写入。",
        "- 若服务端处于 Mock 模式，模型耗时为逐字模拟；真实模型下的首字延迟另见评测报告。",
        "- 每账号请求数受 `psy.rate-limit.chat-per-minute` 约束，调整并发前请先确认限流配置。",
        "",
    ]

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("\n报告：%s" % args.out)
    print("成功率：%d/%d，吞吐 %.2f req/s" % (len(ok), total, total * 1000.0 / max(1, wall_ms)))
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
