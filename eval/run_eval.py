#!/usr/bin/env python3
"""psy-agent 端到端评测 runner。

用法：
    python eval/run_eval.py --base-url http://localhost:18087
    python eval/run_eval.py --base-url http://localhost:18087 --mcp-token dev-mcp-token

断言只依赖结构化结果（route / steps / tools / citations / hitl / traceId / ttftMs），
不比对模型措辞，因此换模型、换温度后结论依然稳定。
为避免单人 10 次/分钟的限流，默认注册若干临时账号轮换使用。
"""

import argparse
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

# localhost 请求绕开系统代理
urllib.request.install_opener(
    urllib.request.build_opener(urllib.request.ProxyHandler({}))
)


def http_json(method, url, payload=None, token=None, headers=None, timeout=30):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    all_headers = {"Content-Type": "application/json"}
    if token:
        all_headers["Authorization"] = "Bearer " + token
    if headers:
        all_headers.update(headers)
    req = urllib.request.Request(url, data=body, headers=all_headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def parse_block(block):
    event = "message"
    data_lines = []
    for line in block.split("\n"):
        if line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:"):
            data_lines.append(line[5:].lstrip(" "))
    return event, "\n".join(data_lines)


def call_stream(base, token, message, timeout=120):
    body = json.dumps({"message": message}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        base + "/api/chat/stream",
        data=body,
        headers={
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
            "Authorization": "Bearer " + token,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            ctype = resp.headers.get("Content-Type", "")
            if "text/event-stream" not in ctype:
                raw = resp.read().decode("utf-8", "ignore")
                return {"error": "非流式响应: " + raw[:180]}
            events = []
            buf = ""
            while True:
                chunk = resp.read(4096)
                if not chunk:
                    break
                buf += chunk.decode("utf-8", "ignore")
                while "\n\n" in buf:
                    block, buf = buf.split("\n\n", 1)
                    if block.strip():
                        events.append(parse_block(block))
    except urllib.error.HTTPError as e:
        return {"error": "HTTP %s: %s" % (e.code, e.read().decode("utf-8", "ignore")[:180])}
    except Exception as e:  # 网络类异常不应中断整轮评测
        return {"error": "请求异常: %s" % e}

    text = "".join(d for n, d in events if n == "delta")
    done = {}
    meta = {}
    error_text = ""
    for name, data in events:
        if name == "done":
            try:
                done = json.loads(data)
            except json.JSONDecodeError:
                pass
        elif name == "meta":
            try:
                meta = json.loads(data)
            except json.JSONDecodeError:
                pass
        elif name == "error":
            error_text = data
    return {"text": text, "done": done, "meta": meta, "error": error_text}


def register_users(base, count, prefix):
    tokens = []
    for i in range(count):
        username = "%s_%d" % (prefix, i)
        result = http_json("POST", base + "/api/auth/register",
                           {"username": username, "password": "eval-pass-123"})
        token = (result.get("data") or {}).get("token")
        if not token:
            raise RuntimeError("注册临时账号失败: %s" % result)
        tokens.append(token)
    return tokens


def check(assertion, obs):
    """返回 (是否通过, 实际值摘要)。"""
    kind = assertion["type"]
    done = obs.get("done") or {}
    text = obs.get("text") or ""
    steps = [s.get("node", "") for s in (done.get("steps") or [])]
    tools = [t.get("name", "") for t in (done.get("tools") or [])]
    citations = done.get("citations") or []

    if kind == "route":
        actual = done.get("route")
        return actual == assertion["equals"], "route=%s" % actual
    if kind == "hitl":
        actual = bool(done.get("hitl"))
        return actual == assertion["equals"], "hitl=%s" % actual
    if kind == "text_contains":
        if "all" in assertion:
            missing = [w for w in assertion["all"] if w not in text]
            return not missing, "缺少 %s" % missing if missing else "全部命中"
        candidates = assertion.get("any") or [assertion.get("value", "")]
        hits = [w for w in candidates if w in text]
        return bool(hits), "命中 %s" % (hits or "无")
    if kind == "text_not_contains":
        words = assertion.get("any") or [assertion.get("value", "")]
        hits = [w for w in words if w in text]
        return not hits, ("未出现禁用词" if not hits else "出现 %s" % hits)
    if kind == "text_max_len":
        return len(text) <= assertion["value"], "%d 字" % len(text)
    if kind == "text_min_len":
        return len(text) >= assertion["value"], "%d 字" % len(text)
    if kind == "citations_min":
        return len(citations) >= assertion["value"], "%d 条" % len(citations)
    if kind == "citations_max":
        return len(citations) <= assertion["value"], "%d 条" % len(citations)
    if kind == "steps_contains":
        return assertion["value"] in steps, "轨迹 %s" % steps
    if kind == "steps_not_contains":
        return assertion["value"] not in steps, "轨迹 %s" % steps
    if kind == "tools_contains":
        return assertion["value"] in tools, "工具 %s" % tools
    if kind == "tools_not_contains":
        return assertion["value"] not in tools, "工具 %s" % tools
    if kind == "trace_exists":
        trace = done.get("traceId") or (obs.get("meta") or {}).get("traceId")
        return bool(trace), "traceId=%s" % trace
    if kind == "ttft_lt":
        value = done.get("ttftMs")
        return value is not None and value < assertion["value"], "ttft=%s" % value
    return False, "未知断言类型 %s" % kind


def mcp_checks(base, token, expected_tools):
    """MCP 握手与 tools/call 的最小验证，独立于对话通道。"""
    results = []
    headers = {"X-MCP-Token": token, "Accept": "application/json"}
    try:
        init = http_json("POST", base + "/mcp",
                         {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                          "params": {"protocolVersion": "2025-06-18"}}, headers=headers)
        version = (init.get("result") or {}).get("protocolVersion")
        results.append(("initialize 返回协议版本", bool(version), version or str(init)[:120]))

        listed = http_json("POST", base + "/mcp",
                           {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, headers=headers)
        names = [t.get("name") for t in (listed.get("result") or {}).get("tools", [])]
        missing = [t for t in expected_tools if t not in names]
        results.append(("tools/list 暴露全部工具", not missing,
                        "共 %d 个，缺失 %s" % (len(names), missing or "无")))

        called = http_json("POST", base + "/mcp",
                           {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                            "params": {"name": "scale_questions", "arguments": {"scale": "GAD7"}}},
                           headers=headers)
        payload = called.get("result") or {}
        structured = payload.get("structuredContent") or {}
        ok = payload.get("isError") is False and structured.get("itemCount") == 7
        results.append(("tools/call 返回结构化结果", ok,
                        "itemCount=%s" % structured.get("itemCount")))

        unauthorized = False
        try:
            http_json("POST", base + "/mcp",
                      {"jsonrpc": "2.0", "id": 4, "method": "tools/list"},
                      headers={"X-MCP-Token": "wrong-token"})
        except urllib.error.HTTPError as e:
            unauthorized = e.code == 401
        results.append(("错误令牌被拒绝", unauthorized, "HTTP 401" if unauthorized else "未拒绝"))
    except Exception as e:
        results.append(("MCP 端点可达", False, str(e)))
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:18087")
    parser.add_argument("--cases", default=os.path.join(os.path.dirname(__file__), "cases.json"))
    parser.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "reports", "eval-report.md"))
    parser.add_argument("--users", type=int, default=4, help="轮换使用的临时账号数，规避单人限流")
    parser.add_argument("--mcp-token", default=os.getenv("PSY_MCP_TOKEN", "dev-mcp-token"))
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    with open(args.cases, encoding="utf-8") as f:
        spec = json.load(f)
    cases = spec["cases"]

    print("注册 %d 个临时评测账号…" % args.users)
    tokens = register_users(base, args.users, "eval_%d" % int(time.time() * 1000))

    rows = []
    ttft_values = []
    total_start = time.time()
    for i, case in enumerate(cases):
        token = tokens[i % len(tokens)]
        started = time.time()
        obs = call_stream(base, token, case["message"], args.timeout)
        elapsed = int((time.time() - started) * 1000)

        if obs.get("error"):
            rows.append({"id": case["id"], "name": case["name"], "ok": False,
                         "route": "-", "cost": elapsed, "detail": obs["error"], "text": ""})
            print("  [%d/%d] %s … 请求失败" % (i + 1, len(cases), case["id"]))
            continue

        passed_all = True
        details = []
        for assertion in case["assertions"]:
            ok, actual = check(assertion, obs)
            passed_all = passed_all and ok
            details.append("%s %s → %s" % ("✓" if ok else "✗", assertion["type"], actual))
        done = obs.get("done") or {}
        ttft = done.get("ttftMs")
        if isinstance(ttft, int):
            ttft_values.append(ttft)
        rows.append({
            "id": case["id"], "name": case["name"], "ok": passed_all,
            "route": done.get("route", "-"), "cost": elapsed,
            "detail": "；".join(details), "text": (obs.get("text") or "")[:80],
            "tools": ",".join(t.get("name", "") for t in (done.get("tools") or [])),
        })
        print("  [%d/%d] %s … %s" % (i + 1, len(cases), case["id"], "通过" if passed_all else "失败"))

    mcp_rows = mcp_checks(base, args.mcp_token,
                          ["scale_questions", "scale_score", "create_appointment", "chat_stats", "crisis_alert"])

    passed = sum(1 for r in rows if r["ok"])
    mcp_passed = sum(1 for _, ok, _ in mcp_rows if ok)
    total = len(rows) + len(mcp_rows)
    total_passed = passed + mcp_passed

    lines = [
        "# psy-agent 端到端评测报告",
        "",
        "- 生成时间：%s" % datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "- 服务地址：`%s`" % base,
        "- 评测集：`eval/cases.json` v%s，共 %d 条对话用例 + %d 项 MCP 检查" % (
            spec.get("version", "-"), len(rows), len(mcp_rows)),
        "- **通过率：%d/%d（%.1f%%）**" % (total_passed, total, 100.0 * total_passed / total),
        "- 评测总耗时：%.1f 秒" % (time.time() - total_start),
        "",
        "## 延迟指标（首字，来自 SSE done 帧）",
        "",
    ]
    if ttft_values:
        ordered = sorted(ttft_values)
        p50 = ordered[int(0.5 * len(ordered)) - 1] if len(ordered) > 1 else ordered[0]
        p95 = ordered[int(0.95 * len(ordered)) - 1] if len(ordered) > 1 else ordered[0]
        lines += [
            "| 样本 | 平均 | p50 | p95 | 最小 | 最大 |",
            "| --- | --- | --- | --- | --- | --- |",
            "| %d | %dms | %dms | %dms | %dms | %dms |" % (
                len(ttft_values), int(statistics.mean(ttft_values)), p50, p95,
                min(ttft_values), max(ttft_values)),
            "",
        ]
    else:
        lines += ["未采集到首字延迟样本。", ""]

    lines += [
        "## 对话用例",
        "",
        "| 用例 | 路由 | 结果 | 耗时 | 工具 | 断言摘要 |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for r in rows:
        lines.append("| %s | %s | %s | %dms | %s | %s |" % (
            r["name"], r["route"], "✅" if r["ok"] else "❌", r["cost"],
            r.get("tools") or "-", r["detail"].replace("|", "/")))
    lines += ["", "## MCP 协议检查", "", "| 检查项 | 结果 | 说明 |", "| --- | --- | --- |"]
    for name, ok, detail in mcp_rows:
        lines.append("| %s | %s | %s |" % (name, "✅" if ok else "❌", detail.replace("|", "/")))
    lines += [
        "",
        "## 说明",
        "",
        "- 断言只检查结构化契约，不比对模型措辞，换模型后结论稳定。",
        "- 每次运行注册临时账号轮换调用，规避单人 10 次/分钟限流。",
        "- `crisis_alert` 用例会在 `t_tool_call` 留下告警审计，属于预期副作用。",
        "",
    ]

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("\n报告：%s" % args.out)
    print("通过率：%d/%d" % (total_passed, total))
    return 0 if total_passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
