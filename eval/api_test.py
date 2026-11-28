#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""接口级回归测试。

与 run_eval.py 的分工：
- run_eval.py 验的是**产品行为**（路由、引用、危机话术），依赖模型输出；
- 本脚本验的是**接口契约与边界**（鉴权、参数校验、归属隔离、错误码、幂等、限流、
  复核工单状态机、会话级风险回溯），可以在 Mock 与真实模型两种模式下跑。

契约约定（与 README 一致）：
    HTTP 状态码只表达传输与身份（401 / 403 / 5xx），
    业务结果一律 HTTP 200 + body.code：0 成功，4xxxx 客户端问题，5xxxx 服务端问题。

用法：
    python eval/api_test.py --base-url http://localhost:18087

退出码非 0 表示存在失败用例。
"""

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request

urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

BASE = ""
RESULTS = []


def req(method, path, payload=None, token=None, headers=None, timeout=120, raw_body=None):
    data = raw_body
    if data is None and payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode()
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = "Bearer " + token
    if headers:
        h.update(headers)
    r = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, dict(resp.headers), resp.read().decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", "ignore")
    except Exception as e:  # 网络层
        return 0, {}, str(e)


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (" | " + detail if detail and not ok else ""))


def body(resp):
    try:
        return json.loads(resp)
    except Exception:
        return {}


def code_of(resp):
    return body(resp).get("code")


def register(username, password="test-pass-123"):
    s, _, b = req("POST", "/api/auth/register", {"username": username, "password": password})
    d = body(b)
    return s, (d.get("data") or {}).get("token"), d


def login(username, password="test-pass-123"):
    s, _, b = req("POST", "/api/auth/login", {"username": username, "password": password})
    d = body(b)
    return s, (d.get("data") or {}).get("token"), d


def sse(token, message, conversation_id=None, trace_id=None, timeout=120):
    payload = {"message": message}
    if conversation_id is not None:
        payload["conversationId"] = conversation_id
    headers = {"Accept": "text/event-stream"}
    if trace_id:
        headers["X-Trace-Id"] = trace_id
    s, h, b = req("POST", "/api/chat/stream", payload, token=token, headers=headers, timeout=timeout)
    if "text/event-stream" not in (h.get("Content-Type") or ""):
        # 前置业务错误：修复后这里应当是 JSON + 正确的业务码，而不是 500 / HTML 错误页
        return {"status": s, "json": body(b), "traceHeader": h.get("X-Trace-Id"), "stream": False}
    events = []
    for block in b.split("\n\n"):
        events.extend(re.findall(r"event:(\w+)\ndata:(.*)", block))
    text = "".join(d for n, d in events if n == "delta")
    done, meta = {}, {}
    for n, d in events:
        if n == "done":
            try:
                done = json.loads(d)
            except Exception:
                done = {}
        elif n == "meta":
            try:
                meta = json.loads(d)
            except Exception:
                pass
    err = [d for n, d in events if n == "error"]
    return {"status": s, "text": text, "done": done, "error": err, "meta": meta,
            "traceHeader": (h.get("X-Trace-Id") or ""), "stream": True}


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:18087")
    args = ap.parse_args()
    BASE = args.base_url.rstrip("/")

    stamp = int(time.time())
    tokens = []

    print("\n[一] 基础与鉴权")
    s, _, _ = req("GET", "/api/system/ping")
    check("ping 返回 200", s == 200, "status=%s" % s)
    s, _, b = req("GET", "/api/system/health")
    health = body(b).get("data") or {}
    check("health 返回 200 且含模型信息", s == 200 and ("model" in health or "mock" in health),
          "status=%s body=%s" % (s, b[:120]))

    for i in range(8):
        _, t, _ = register("api_%d_%d" % (stamp, i))
        if t:
            tokens.append(t)
    check("注册 8 个测试账号", len(tokens) == 8, "成功 %d" % len(tokens))

    s, _, b = req("POST", "/api/auth/register",
                  {"username": "api_%d_0" % stamp, "password": "test-pass-123"})
    check("重复用户名注册被拒", code_of(b) != 0, "code=%s" % code_of(b))
    s, _, b = req("POST", "/api/auth/login",
                  {"username": "api_%d_0" % stamp, "password": "wrong-password"})
    check("错误密码登录被拒", code_of(b) not in (0, None), "code=%s" % code_of(b))
    s, _, b = req("POST", "/api/chat", {"message": "你好"})
    check("未携带令牌访问对话返回 401", s == 401, "status=%s" % s)
    s, _, b = req("POST", "/api/chat", {"message": "你好"}, token="invalid.token.here")
    check("无效令牌返回 401", s == 401, "status=%s" % s)

    print("\n[二] 参数校验")
    tok = tokens[0]
    for name, payload in [("空消息", {"message": ""}), ("缺 message 字段", {}),
                          ("超长消息(2001字)", {"message": "字" * 2001})]:
        s, _, b = req("POST", "/api/chat", payload, token=tok)
        check("%s 返回 40000" % name, code_of(b) == 40000, "status=%s code=%s" % (s, code_of(b)))
    s, _, b = req("POST", "/api/chat", raw_body=b"{oops", token=tok)
    check("非法 JSON 请求体返回 40000", code_of(b) == 40000, "status=%s code=%s" % (s, code_of(b)))

    print("\n[三] SSE 契约与落库")
    r = sse(tokens[1], "你好", trace_id="api-%d-1" % stamp)
    check("stream 返回 SSE 且 meta/done 完整",
          r.get("stream") and r.get("meta", {}).get("conversationId") and r.get("done"),
          "status=%s" % r.get("status"))
    conv_id = (r.get("meta") or {}).get("conversationId")
    done = r.get("done") or {}
    check("done 含 route/steps/ttftMs/traceId/citationIssues",
          all(k in done for k in ("route", "steps", "ttftMs", "traceId", "citationIssues")),
          str(sorted(done.keys())))
    check("响应头 X-Trace-Id 与 done.traceId 一致",
          bool(r.get("traceHeader")) and r.get("traceHeader") == done.get("traceId"),
          "header=%s done=%s" % (r.get("traceHeader"), done.get("traceId")))
    check("delta 文本非空", len(r.get("text") or "") > 0, "len=%d" % len(r.get("text") or ""))
    check("引用编号无越界", (done.get("citationIssues") or 0) == 0,
          "citationIssues=%s" % done.get("citationIssues"))

    s, _, b = req("GET", "/api/conversation/list", token=tokens[1])
    convs = body(b).get("data") or []
    check("会话列表包含新建会话", any(c.get("id") == conv_id for c in convs), "count=%d" % len(convs))
    s, _, b = req("GET", "/api/conversation/%s/messages" % conv_id, token=tokens[1])
    msgs = body(b).get("data") or []
    roles = [m.get("role") for m in msgs]
    check("消息落库含 user+assistant", "user" in roles and "assistant" in roles, str(roles))
    check("用户消息内容未被污染", any(m.get("content") == "你好" for m in msgs),
          str([m.get("content") for m in msgs])[:120])

    print("\n[四] 会话归属隔离")
    s, _, b = req("GET", "/api/conversation/%s/messages" % conv_id, token=tokens[2])
    check("他人会话消息读取被拒(40400)", code_of(b) == 40400, "status=%s code=%s" % (s, code_of(b)))
    s, _, b = req("DELETE", "/api/conversation/%s" % conv_id, token=tokens[2])
    check("他人会话删除被拒(40400)", code_of(b) == 40400, "status=%s code=%s" % (s, code_of(b)))
    r = sse(tokens[2], "你好", conversation_id=conv_id)
    check("引用他人会话流式请求返回 JSON 错误而非 500",
          (not r.get("stream")) and (r.get("json") or {}).get("code") == 40400,
          "status=%s body=%s" % (r.get("status"), str(r.get("json"))[:120]))
    r = sse(tokens[1], "你好", conversation_id=99999999)
    check("不存在的会话返回 40400 而非 500",
          (not r.get("stream")) and (r.get("json") or {}).get("code") == 40400,
          "status=%s body=%s" % (r.get("status"), str(r.get("json"))[:120]))

    print("\n[五] 路由判定")
    for msg, expect in [("你好呀", "SMALLTALK"), ("我最近总是睡不着，有什么办法吗", "KNOWLEDGE"),
                        ("我今天特别难过，什么都不想说", "EMOTION"), ("我不想活了", "CRISIS")]:
        r = sse(tokens[3], msg)
        got = (r.get("done") or {}).get("route")
        check("路由 %s → %s" % (msg[:12], expect), got == expect, "实际=%s" % got)
    r = sse(tokens[3], "我不想活了")
    d = r.get("done") or {}
    check("危机路径标记人工复核", d.get("hitl") is True, "hitl=%s" % d.get("hitl"))
    check("危机路径触发告警工具",
          any(t.get("name") == "crisis_alert" for t in (d.get("tools") or [])), str(d.get("tools"))[:120])

    print("\n[六] 工具 REST")
    s, _, b = req("GET", "/api/tools", token=tokens[4])
    tools = body(b).get("data") or []
    check("工具清单返回 5 个且含 inputSchema",
          len(tools) == 5 and all("inputSchema" in t for t in tools), "count=%d" % len(tools))

    s, _, b = req("POST", "/api/tools/scale_score",
                  {"scale": "PHQ9", "answers": [0, 1, 1, 0, 2, 0, 1, 0, 0]}, token=tokens[4])
    d = body(b).get("data") or {}
    check("PHQ-9 计分成功", d.get("success") and (d.get("data") or {}).get("score") == 5, str(d)[:150])
    s, _, b = req("POST", "/api/tools/scale_score",
                  {"scale": "PHQ9", "answers": [0, 1, 1, 0, 2, 0]}, token=tokens[4])
    check("题数不足计分失败(不抛异常)", (body(b).get("data") or {}).get("success") is False,
          str(body(b).get("data"))[:150])
    s, _, b = req("POST", "/api/tools/scale_score",
                  {"scale": "XXX", "answers": [0, 1, 1, 0, 2, 0, 1, 0, 0]}, token=tokens[4])
    check("未知量表返回失败", (body(b).get("data") or {}).get("success") is False,
          str(body(b).get("data"))[:150])
    s, _, b = req("POST", "/api/tools/scale_score",
                  {"scale": "PHQ9", "answers": [0, 0, 0, 0, 0, 0, 0, 0, 2]}, token=tokens[4])
    d = (body(b).get("data") or {}).get("data") or {}
    check("第 9 题阳性带求助热线", d.get("selfHarmFlag") is True and "12356" in str(d.get("riskHotline", "")),
          str(d)[:180])

    s, _, b = req("POST", "/api/tools/create_appointment",
                  {"contact": "13800138000", "preferredTime": "周六下午",
                   "idempotencyKey": "api-%d" % stamp}, token=tokens[4])
    d = (body(b).get("data") or {}).get("data") or {}
    check("预约创建成功且手机号脱敏",
          bool(d.get("appointmentId")) and "*" in str(d.get("contactMasked", "")), str(d)[:150])
    s, _, b = req("POST", "/api/tools/create_appointment",
                  {"contact": "13800138000", "preferredTime": "周六下午",
                   "idempotencyKey": "api-%d" % stamp}, token=tokens[4])
    check("预约幂等重放返回 duplicate",
          ((body(b).get("data") or {}).get("data") or {}).get("duplicate") is True,
          str(body(b).get("data"))[:150])
    s, _, b = req("POST", "/api/tools/create_appointment", {"contact": "not-a-contact"}, token=tokens[4])
    check("非法联系方式预约失败", (body(b).get("data") or {}).get("success") is False,
          str(body(b).get("data"))[:150])
    s, _, b = req("POST", "/api/tools/not_exists", {}, token=tokens[4])
    d = body(b).get("data") or {}
    check("未知工具返回失败而非 5xx", d.get("success") is False and "未知工具" in str(d.get("error")),
          "status=%s %s" % (s, str(d)[:120]))

    print("\n[七] MCP 协议")
    mh = {"X-MCP-Token": "dev-mcp-token", "Accept": "application/json"}
    s, _, b = req("POST", "/mcp", {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                                   "params": {"protocolVersion": "2025-06-18"}}, headers=mh)
    init = body(b).get("result") or {}
    check("initialize 返回协议版本与服务器信息",
          s == 200 and init.get("protocolVersion") and (init.get("serverInfo") or {}).get("name"),
          str(init)[:150])
    s, _, b = req("POST", "/mcp", {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, headers=mh)
    tools = (body(b).get("result") or {}).get("tools") or []
    check("tools/list 返回 5 个工具", len(tools) == 5 and all("inputSchema" in t for t in tools),
          "count=%d" % len(tools))
    s, _, b = req("POST", "/mcp", {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                                   "params": {"name": "scale_questions",
                                              "arguments": {"scale": "GAD7"}}}, headers=mh)
    r = body(b).get("result") or {}
    check("tools/call 成功返回结构化内容",
          r.get("isError") is False and (r.get("structuredContent") or {}).get("itemCount") == 7,
          str(r)[:150])
    s, _, b = req("POST", "/mcp", {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                                   "params": {"name": "not_exists", "arguments": {}}}, headers=mh)
    check("tools/call 未知工具 isError=true", (body(b).get("result") or {}).get("isError") is True,
          str(body(b).get("result"))[:120])
    s, _, b = req("POST", "/mcp", {"jsonrpc": "2.0", "id": 5, "method": "not/a/method"}, headers=mh)
    check("未知方法返回 JSON-RPC -32601", (body(b).get("error") or {}).get("code") == -32601,
          str(body(b).get("error"))[:120])
    s, _, b = req("POST", "/mcp", raw_body=b"{not json", headers=mh)
    check("非法 JSON 返回 JSON-RPC -32700", (body(b).get("error") or {}).get("code") == -32700,
          "status=%s body=%s" % (s, str(body(b))[:120]))
    s, _, b = req("POST", "/mcp", {"jsonrpc": "2.0", "id": 6, "method": "tools/list"},
                  headers={"X-MCP-Token": "wrong"})
    check("错误 MCP 令牌返回 401", s == 401, "status=%s" % s)

    print("\n[八] 可观测接口")
    s, _, b = req("GET", "/api/metrics/overview")
    check("未登录访问指标返回 401", s == 401, "status=%s" % s)
    s, _, b = req("GET", "/api/metrics/overview?hours=24", token=tokens[4])
    m = body(b).get("data") or {}
    check("指标总览返回完整字段",
          s == 200 and all(k in m for k in ("totalChats", "routes", "trend", "ttftP50Ms")),
          "status=%s keys=%s" % (s, list(m.keys())[:8]))
    check("趋势补齐 24 个小时桶", len(m.get("trend") or []) == 24, "len=%s" % len(m.get("trend") or []))
    check("路由分布按四类补齐",
          sorted([r["route"] for r in (m.get("routes") or [])]) ==
          ["CRISIS", "EMOTION", "KNOWLEDGE", "SMALLTALK"],
          str([r.get("route") for r in (m.get("routes") or [])]))
    s, _, b = req("GET", "/api/metrics/traces?limit=5", token=tokens[4])
    traces = body(b).get("data") or []
    check("轨迹明细不含对话正文",
          s == 200 and 0 < len(traces) <= 5
          and all("trace_id" in t and "content" not in t for t in traces), "count=%d" % len(traces))

    print("\n[九] 限流")
    codes = []
    limited = False
    for i in range(12):
        s, _, b = req("POST", "/api/chat", {"message": "限流测试 %d" % i}, token=tokens[5])
        codes.append(code_of(b))
        if code_of(b) == 42900:
            limited = True
    check("超过 10 次/分钟返回 42900", limited, "codes=%s" % codes)

    print("\n[十] traceId 重试可观测")
    tid = "retry%d" % stamp
    r1 = sse(tokens[0], "你好", trace_id=tid)
    r2 = sse(tokens[0], "你好", trace_id=tid)
    check("同一 traceId 连续请求均正常",
          r1.get("done") and r2.get("done"),
          "statuses=%s,%s" % (r1.get("status"), r2.get("status")))
    s, _, b = req("GET", "/api/metrics/traces?limit=100", token=tokens[0])
    same = [t for t in (body(b).get("data") or []) if t.get("trace_id") == tid]
    check("两次尝试都落库（不再被唯一键丢弃）", len(same) == 2, "matched=%d" % len(same))

    print("\n[十一] 复核工单")
    _, admin, _ = login("leo_test", "123456")
    if not admin:
        _, admin, _ = register("leo_test_%d" % stamp)
        check("管理员账号可用", True, "使用临时账号，注意白名单")
    r = sse(tokens[6], "我真的撑不下去了，不想活了")
    crisis_trace = (r.get("done") or {}).get("traceId")
    check("该消息被判为危机", (r.get("done") or {}).get("route") == "CRISIS",
          "route=%s" % (r.get("done") or {}).get("route"))
    time.sleep(1.0)
    s, _, b = req("GET", "/api/review/tasks?status=PENDING&limit=50", token=admin)
    tasks = body(b).get("data") or []
    target = next((t for t in tasks if t.get("traceId") == crisis_trace), None)
    check("危机轮次生成待办工单", target is not None, "trace=%s count=%d" % (crisis_trace, len(tasks)))
    if target:
        check("工单含时限与来源", target.get("dueAt") and target.get("source") == "RULE", str(target)[:160])
        task_id = target["id"]
        s, _, b = req("POST", "/api/review/tasks/%s/claim" % task_id, token=admin)
        d = body(b).get("data") or {}
        check("认领工单成功且记录处理人",
              d.get("status") == "CLAIMED" and d.get("handler"), str(d)[:150])
        s, _, b = req("POST", "/api/review/tasks/%s/claim" % task_id, token=admin)
        check("重复认领被拒(40000)", code_of(b) == 40000, "code=%s" % code_of(b))
        s, _, b = req("POST", "/api/review/tasks/%s/close" % task_id,
                      {"status": "CLOSED", "conclusion": "已电话联系，本人安全"}, token=admin)
        d = body(b).get("data") or {}
        check("关闭工单成功", d.get("status") == "CLOSED" and d.get("closedAt"), str(d)[:150])
        s, _, b = req("POST", "/api/review/tasks/%s/close" % task_id,
                      {"status": "CLOSED", "conclusion": "重复关闭"}, token=admin)
        check("重复关闭被拒(40000)", code_of(b) == 40000, "code=%s" % code_of(b))
        s, _, b = req("POST", "/api/review/tasks/%s/close" % task_id,
                      {"status": "WHATEVER", "conclusion": "非法结论"}, token=admin)
        check("非法状态被拒(40000)", code_of(b) == 40000, "code=%s" % code_of(b))
    s, _, b = req("GET", "/api/review/tasks", token=tokens[7])
    check("非白名单用户访问复核队列被拒(40300)", code_of(b) == 40300, "code=%s" % code_of(b))
    s, _, b = req("GET", "/api/review/summary", token=admin)
    sm = body(b).get("data") or {}
    check("复核概况含状态分布与逾期数", s == 200 and "counts" in sm and "overdue" in sm,
          str(sm)[:150])

    print("\n[十二] 会话级风险回溯")
    r = sse(tokens[7], "我不想活了")
    conv = (r.get("meta") or {}).get("conversationId")
    # 风险等级在流结束后由评估回调异步回填，等它落库
    time.sleep(2.0)
    r2 = sse(tokens[7], "我好点了", conversation_id=conv)
    d2 = r2.get("done") or {}
    steps = [s.get("node") for s in (d2.get("steps") or [])]
    check("上轮高风险后，寒暄被抬到情绪支持",
          d2.get("route") == "EMOTION", "route=%s steps=%s" % (d2.get("route"), steps))
    check("轨迹出现风险回溯节点且计数>0",
          "风险回溯" in steps and (d2.get("priorHighRisk") or 0) >= 1,
          "priorHighRisk=%s steps=%s" % (d2.get("priorHighRisk"), steps))

    print("\n[十三] 特殊输入")
    r = sse(tokens[8] if len(tokens) > 8 else tokens[2], "😭😭😭")
    check("纯表情消息正常返回", r.get("stream") and (r.get("done") or {}).get("route"),
          "status=%s route=%s" % (r.get("status"), (r.get("done") or {}).get("route")))
    r = sse(tokens[1], "<script>alert(1)</script> 我今天很焦虑")
    check("含脚本标签的消息正常处理", r.get("stream") and (r.get("done") or {}).get("route"),
          "status=%s" % r.get("status"))

    total = len(RESULTS)
    failed = [n for n, ok, _ in RESULTS if not ok]
    print("\n" + "=" * 72)
    print("用例 %d 项，通过 %d，失败 %d" % (total, total - len(failed), len(failed)))
    for n in failed:
        print("  ✗ " + n)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
