# SPDX-License-Identifier: GPL-3.0-only
"""红果登录试跑台：本机 HTTP 服务 + 网页界面。

启动：

    python3 tools/login-lab/server.py --port 8770

然后浏览器打开 http://127.0.0.1:8770/ 。三块功能：

* 登录：手机号 + 验证码（走 passport send_code / validate_code）；
  扫码两种：平台官方扫码接口（当前 aid 无权限时如实显示平台原文）、
  本机扫码中继（手机扫码打开局域网页面，在手机上完成登录，网页这边自动接管会话）。
* 搜索：App 搜索接口 /reading/bookapi/search/tab/v1/，支持两个 tab 与翻页。
* 同步：阅读进度 / 阅读历史 / 书架 / 书签 / 用户资料，逐个显示成功或平台原文。
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import os
import socket
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import hgclient
import hgsign

HERE = os.path.dirname(os.path.abspath(__file__))
STATIC = os.path.join(HERE, "static")

RUNTIME = hgclient.RUNTIME
os.makedirs(RUNTIME, exist_ok=True)

SESSION = hgclient.HgSession()
LOCK = threading.Lock()
# 一次性扫码中继令牌：手机扫到的地址里带它，页面上的表单提交时校验
RELAY_TOKEN = os.urandom(8).hex()
RELAY_STATE = {"used": False}


def qr_svg(text: str) -> str:
    """把文本画成内联 SVG（segno 缺失时返回空串，界面自己降级成纯文本）。"""
    try:
        import io
        import segno
        buffer = io.BytesIO()
        segno.make(text, error="m").save(buffer, kind="svg", scale=4, border=2, dark="#17171b", light="#e8e8ec")
        return buffer.getvalue().decode("utf-8")
    except Exception:
        return ""


def lan_ip() -> str:
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("223.5.5.5", 80))
        address = probe.getsockname()[0]
        probe.close()
        return address
    except Exception:
        return "127.0.0.1"


class Handler(BaseHTTPRequestHandler):
    server_version = "hg-login-lab"

    # ------------------------------------------------------------------ 工具
    def _send(self, status: int, payload, content_type: str = "application/json; charset=utf-8") -> None:
        if isinstance(payload, (dict, list)):
            raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        elif isinstance(payload, str):
            raw = payload.encode("utf-8")
        else:
            raw = payload
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        if not raw:
            return {}
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {key: value[0] for key, value in urllib.parse.parse_qs(raw.decode("utf-8", "replace")).items()}

    def log_message(self, fmt, *args):  # 保持终端干净
        pass

    # ------------------------------------------------------------------ 路由
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        route, query = parsed.path, urllib.parse.parse_qs(parsed.query)
        if route in ("/", "/index.html"):
            return self._file("index.html", "text/html; charset=utf-8")
        if route == "/relay":
            return self._relay_page(query.get("t", [""])[0])
        if route == "/api/session":
            return self._send(200, self._session_state())
        if route == "/api/log":
            return self._send(200, {"log": SESSION.log[-40:]})
        if route == "/api/search":
            keyword = (query.get("kw") or [""])[0]
            tab = int((query.get("tab") or ["19"])[0])
            offset = int((query.get("offset") or ["0"])[0])
            with LOCK:
                try:
                    page = SESSION.search_page(keyword, tab_type=tab, offset=offset)
                    return self._send(200, {"ok": True, "page": page})
                except hgclient.HgError as error:
                    return self._send(200, {"ok": False, "error": str(error), "payload": error.payload})
                except Exception as error:
                    return self._send(200, {"ok": False, "error": "%s: %s" % (type(error).__name__, error)})
        if route == "/api/sync":
            with LOCK:
                return self._send(200, {"ok": True, "result": SESSION.sync_all()})
        if route in ("/api/qr", "/api/qr/new"):
            with LOCK:
                RELAY_STATE["used"] = False
                return self._send(200, self._qr_state())
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        route = urllib.parse.urlparse(self.path).path
        payload = self._body()
        if route == "/api/sms/send":
            with LOCK:
                try:
                    data = SESSION.send_sms(payload.get("mobile", ""))
                    return self._send(200, {"ok": True, "data": data})
                except hgclient.HgError as error:
                    return self._send(200, {"ok": False, "error": str(error), "payload": error.payload})
                except Exception as error:
                    return self._send(200, {"ok": False, "error": "%s: %s" % (type(error).__name__, error)})
        if route == "/api/sms/login":
            with LOCK:
                try:
                    result = SESSION.sms_login(payload.get("mobile", ""), payload.get("code", ""),
                                               payload.get("mobile_ticket", ""))
                    return self._send(200, {"ok": True, "result": result, "session": self._session_state()})
                except hgclient.HgError as error:
                    return self._send(200, {"ok": False, "error": str(error), "payload": error.payload})
                except Exception as error:
                    return self._send(200, {"ok": False, "error": "%s: %s" % (type(error).__name__, error)})
        if route == "/api/refresh":
            with LOCK:
                try:
                    result = SESSION.refresh_from_emulator(min_days=float(payload.get("min_days", 7)))
                    return self._send(200, {"ok": True, "result": result, "session": self._session_state()})
                except hgclient.HgError as error:
                    return self._send(200, {"ok": False, "error": str(error)})
                except Exception as error:
                    return self._send(200, {"ok": False, "error": "%s: %s" % (type(error).__name__, error)})
        if route == "/api/import":
            with LOCK:
                try:
                    info = SESSION.import_from_emulator(payload.get("serial") or None)
                    return self._send(200, {"ok": True, "info": info, "session": self._session_state()})
                except hgclient.HgError as error:
                    return self._send(200, {"ok": False, "error": str(error)})
                except Exception as error:
                    return self._send(200, {"ok": False, "error": "%s: %s" % (type(error).__name__, error)})
        if route in ("/api/refresh", "/api/sync", "/api/qr/new", "/api/logout"):
            with LOCK:
                if route == "/api/refresh":
                    try:
                        result = SESSION.refresh_from_emulator(min_days=0)
                        return self._send(200, {"ok": True, "result": result, "session": self._session_state()})
                    except Exception as error:
                        return self._send(200, {"ok": False, "error": str(error)[:300]})
                if route == "/api/sync":
                    return self._send(200, {"ok": True, "result": SESSION.sync_all()})
                if route == "/api/qr/new":
                    RELAY_STATE["used"] = False
                    return self._send(200, self._qr_state())
                SESSION.logout()
                return self._send(200, {"ok": True, "session": self._session_state()})
        if route == "/api/token":
            with LOCK:
                try:
                    SESSION.apply_token(payload.get("token", ""))
                    RELAY_STATE["used"] = True
                    return self._send(200, {"ok": True, "session": self._session_state()})
                except hgclient.HgError as error:
                    return self._send(200, {"ok": False, "error": str(error)})
        if route == "/api/qr/check":
            with LOCK:
                try:
                    data = SESSION.qr_check(payload.get("channel", ""), payload.get("token", ""))
                    return self._send(200, {"ok": True, "data": data})
                except hgclient.HgError as error:
                    return self._send(200, {"ok": False, "error": str(error)})
        return self._send(404, {"error": "not found"})

    # ------------------------------------------------------------------ 状态
    def _session_state(self) -> dict:
        expiry = SESSION.session_expiry()
        return {"logged_in": SESSION.logged_in, "mobile": SESSION.mobile,
                "expiry": {"known": expiry.get("known"), "remaining_days": expiry.get("remaining_days"),
                           "expires": expiry.get("expires"), "issued": expiry.get("issued"),
                           "expired": expiry.get("expired")},
                "token_tail": ("…" + SESSION.tt_token[-8:]) if SESSION.tt_token else "",
                "cookies": sorted(SESSION.cookies().keys()), "mode": SESSION.mode,
                "device": getattr(SESSION, "device_source", "repo"),
                "host": self.headers.get("Host", ""), "relay_used": RELAY_STATE["used"]}

    def _qr_state(self) -> dict:
        platform = SESSION.qr_new()
        relay_url = "http://%s:%d/relay?t=%s" % (lan_ip(), self.server.server_address[1], RELAY_TOKEN)
        state = {"platform": platform, "relay_url": relay_url, "relay_svg": qr_svg(relay_url),
                 "relay_used": RELAY_STATE["used"], "session": self._session_state()}
        if platform.get("ok"):
            state["platform_svg"] = qr_svg(platform.get("qrcode") or "")
        return state

    # ------------------------------------------------------------------ 静态与中继
    def _file(self, name: str, content_type: str | None = None) -> None:
        path = os.path.join(STATIC, name)
        if not os.path.isfile(path):
            return self._send(404, {"error": "missing " + name})
        with open(path, "rb") as handle:
            raw = handle.read()
        guessed = content_type or mimetypes.guess_type(path)[0] or "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type", guessed)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def _relay_page(self, token: str) -> None:
        """手机扫码后打开的页面：在手机上完成登录 / 粘 token，网页这边自动接管。"""
        if token != RELAY_TOKEN:
            return self._send(403, "<h1>链接已失效</h1>", "text/html; charset=utf-8")
        session = self._session_state()
        html = RELAY_TEMPLATE.replace("__SESSION__", json.dumps(session, ensure_ascii=False))
        self._send(200, html, "text/html; charset=utf-8")


RELAY_TEMPLATE = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>红果登录中继</title>
<style>
:root{color-scheme:dark}
body{margin:0;padding:24px;background:#1b1b1f;color:#e8e8ec;font:15px/1.6 -apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif}
h1{font-size:19px;margin:0 0 4px}p{color:#a5a5b0;font-size:13px;margin:4px 0 16px}
.card{background:#212127;border-radius:16px;padding:16px;margin-bottom:14px;box-shadow:0 1px 0 #2c2c33 inset,0 -1px 0 #17171a inset}
label{display:block;font-size:12px;color:#8f8f9a;margin:10px 0 4px}
input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #33333c;background:#1b1b1f;color:#e8e8ec;font-size:16px}
button{width:100%;margin-top:12px;padding:12px;border:0;border-radius:10px;background:#c8f560;color:#12120f;font-size:15px;font-weight:600}
button.ghost{background:#2c2c33;color:#e8e8ec}
pre{white-space:pre-wrap;word-break:break-all;font-size:12px;color:#9ee6a0;margin:8px 0 0}
.state{font-size:12px;color:#8f8f9a;margin-top:8px}
</style></head><body>
<h1>红果登录中继</h1>
<p>在手机上完成下面任一步，电脑 / 电视端的试跑台会自动接管登录态。关闭本页即结束。</p>
<div class="card">
  <div style="font-weight:600">① 手机号 + 验证码登录</div>
  <label>手机号</label><input id="mobile" inputmode="numeric" placeholder="11 位手机号">
  <button onclick="sendCode()">发送验证码</button>
  <label>验证码</label><input id="code" inputmode="numeric" placeholder="6 位数字">
  <button onclick="smsLogin()">登录</button>
</div>
<div class="card">
  <div style="font-weight:600">② 粘贴 x-tt-token</div>
  <label>从手机 App 抓到的 x-tt-token（整段 Cookie 也可以）</label>
  <input id="token" placeholder="粘贴 token">
  <button class="ghost" onclick="useToken()">保存到试跑台</button>
</div>
<div class="card"><div style="font-weight:600">当前状态</div><div class="state" id="state"></div><pre id="out"></pre></div>
<script>
let mobileTicket = "";
const state = __SESSION__;
function show(o){ document.getElementById("out").textContent = JSON.stringify(o, null, 2).slice(0, 1200); }
function draw(){ const s = state.logged_in ? ("已登录" + (state.mobile ? " · " + state.mobile : "") + " · token " + state.token_tail) : "未登录"; document.getElementById("state").textContent = s; }
async function post(path, body){ const r = await fetch(path, {method:"POST", headers:{"Content-Type":"application/json"}, body: JSON.stringify(body)}); return r.json(); }
async function sendCode(){ const j = await post("/api/sms/send", {mobile: document.getElementById("mobile").value}); mobileTicket = (j.data && j.data.mobile_ticket) || ""; show(j); }
async function smsLogin(){ const j = await post("/api/sms/login", {mobile: document.getElementById("mobile").value, code: document.getElementById("code").value, mobile_ticket: mobileTicket}); if (j.session) Object.assign(state, j.session); draw(); show(j); }
async function useToken(){ const j = await post("/api/token", {token: document.getElementById("token").value}); if (j.session) Object.assign(state, j.session); draw(); show(j); }
draw();
</script></body></html>"""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8770)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--mode", default="hg", choices=["hg", "ss"],
                        help="hg = 当前 APK 的签名头；ss = hgApi 的 ss_queries 模型")
    parser.add_argument("--device", default="repo", choices=["repo", "emulator"],
                        help="repo = 仓库里的固定设备参数（默认，实测最稳）；emulator = 模拟器 dump 的档案")
    args = parser.parse_args()
    SESSION.mode = args.mode
    SESSION.device_source = args.device
    SESSION.signer = hgclient.hgsign.Signer(device=SESSION._device_profile() if args.device == "emulator" else None)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    with open(os.path.join(RUNTIME, "relay_token.txt"), "w", encoding="utf-8") as handle:
        handle.write(RELAY_TOKEN)
    print("试跑台已启动：http://127.0.0.1:%d/" % args.port)
    print("手机扫码中继：http://%s:%d/relay?t=%s" % (lan_ip(), args.port, RELAY_TOKEN))
    print("签名模式：%s（ss 模式会给 query 加 ss_queries 与 X-SS-QUERIES 头）" % args.mode)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")


if __name__ == "__main__":
    main()
