# SPDX-License-Identifier: GPL-3.0-only
"""红果 / 抖音 passport 登录 + 业务接口客户端（本机试跑用）。

三块能力：

* 登录：手机号 + 验证码（passport ``send_code`` / ``validate_code``），
  以及扫码（先试官方 ``qrcode/get``，不可用时退到本机扫码中继，见 server.py）。
* 搜索：``/reading/bookapi/search/tab/v1/``（分 tab、可翻页）。
* 同步：阅读进度 / 阅读历史 / 书架 / 书签 / 用户资料的读取接口。

签名由 ``hgsign`` 负责；登录态（Cookie + ``X-Tt-Token``）由本模块保存。
"""
from __future__ import annotations

import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import hgsign

RUNTIME = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".runtime")
DEVICE_PROFILE_FILE = os.path.join(RUNTIME, "device_profile.json")
COOKIE_FILE = os.path.join(RUNTIME, "cookie_header.txt")
SESSION_FILE = os.path.join(RUNTIME, "session.json")

PASSPORT_HOST = "https://is.snssdk.com"
API_HOSTS = [
    "https://api5-normal-sinfonlinea.fqnovel.com",
    "https://api5-normal-sinfonlinec.fqnovel.com",
    "https://api5-normal-sinfonlineb.fqnovel.com",
    "https://api5-normal-lf.fqnovel.com",
    "https://api5-normal-lq.fqnovel.com",
    "https://api5-normal-hl.fqnovel.com",
]
SITE = "https://hongguoduanju.com"

# 短信验证码的 type（2026-10-15 实测 aid=8662）：
#   1 = 登录 / 注册验证码（message=success，真下发短信）
#   8 = 同样下发（语义未确认）
#   3 = **绑定 / 换绑手机号**分支：号码已绑定时回 1001「请先解除原有绑定关系，再换绑到当前用户账号」
#   0 = 1204 验证码请求错误；2 / 4 / 5 / 35 … = 3052（该类型未开通）
SMS_TYPE = "1"
SMS_TYPE_ALTERNATES = ("1", "8")

# 扫码接口候选（aid 8662 = 红果短剧，1128 = 抖音 web）
QR_CANDIDATES = [
    ("open_qrcode", "/passport/open/qrcode/get/"),
    ("web_qrcode", "/passport/web/get_qrcode/"),
]
QR_CHECK_CANDIDATES = {
    "open_qrcode": "/passport/open/qrcode/check/",
    "web_qrcode": "/passport/web/qrcode/check/",
}


class HgError(RuntimeError):
    def __init__(self, message: str, payload=None):
        super().__init__(message)
        self.payload = payload


class HgSession:
    """一个会话 = 一份设备档案 + 一份登录态（Cookie / token）。"""

    def __init__(self, mode: str = "hg", device: str = "repo"):
        """device：``repo`` = 仓库里那套固定设备参数（实测搜索最稳）；``emulator`` = 从模拟器 dump 的档案。"""
        self.mode = mode
        self.device_source = device
        self.signer = hgsign.Signer(device=self._device_profile() if device == "emulator" else None)
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.tt_token = ""
        self.mobile = ""
        self.last_ticket = ""
        self.last_sms_type = ""
        self.log: list[dict] = []
        self.load()

    @staticmethod
    def _device_profile() -> dict | None:
        """有真机档案就用真机档案（从模拟器里 dump 出来的），否则用仓库里的固定设备。"""
        try:
            with open(DEVICE_PROFILE_FILE, encoding="utf-8") as handle:
                profile = json.load(handle)
        except Exception:
            return None
        device = dict(hgsign.hgvendor.DEVICE)
        if profile.get("device_id"):
            device["device_id"] = str(profile["device_id"])
            device["uuid"] = str(profile.get("uuid") or profile["device_id"])
        if profile.get("iid"):
            device["iid"] = str(profile["iid"])
        if profile.get("cdid"):
            device["cdid"] = str(profile["cdid"])
        if profile.get("openudid"):
            device["openudid"] = str(profile["openudid"])
        for key in ("device_type", "device_brand", "resolution", "dpi", "os_version", "os_api"):
            if profile.get(key):
                device[key] = str(profile[key])
        return device

    # ------------------------------------------------------------------ 登录态
    @property
    def logged_in(self) -> bool:
        return bool(self.tt_token or any(c.name in ("sessionid", "sid_tt", "uid_tt") for c in self.jar))

    def cookies(self) -> dict:
        return {c.name: c.value for c in self.jar}

    def save(self) -> None:
        os.makedirs(RUNTIME, exist_ok=True)
        data = {"tt_token": self.tt_token, "mobile": self.mobile, "cookies": self.cookies(),
                "mobile_ticket": self.last_ticket, "sms_type": self.last_sms_type}
        with open(SESSION_FILE, "w", encoding="utf-8") as handle:
            json.dump(data, handle, ensure_ascii=False, indent=2)

    def load(self) -> None:
        try:
            with open(SESSION_FILE, encoding="utf-8") as handle:
                data = json.load(handle)
        except Exception:
            return
        self.tt_token = data.get("tt_token", "")
        self.mobile = data.get("mobile", "")
        self.last_ticket = data.get("mobile_ticket", "")
        self.last_sms_type = data.get("sms_type", "")
        for name, value in (data.get("cookies") or {}).items():
            self.jar.set_cookie(http.cookiejar.Cookie(0, name, value, None, False, "", False, False, "/", True,
                                                      False, None, True, None, None, {}))

    # ------------------------------------------------------------------ 有效期
    def session_expiry(self) -> dict:
        """从 `sid_guard` 算出会话有效期。

        `sid_guard` 形如 `sessionid|签发时间戳|max-age|人类可读过期时间`：
        **过期 = 签发 + max-age**（实测 max-age=5183999 ≈ 60 天），
        第三段不是过期时间戳。业务接口不会回 `Set-Cookie`，所以**不会滑动续期**。
        """
        guard = self.cookies().get("sid_guard") or ""
        raw = urllib.parse.unquote(guard)
        parts = raw.split("|")
        info = {"raw": raw, "sessionid": parts[0] if parts else "",
                "issued": None, "expires": None, "max_age": None,
                "remaining_days": None, "expired": None, "known": False}
        if len(parts) >= 3:
            try:
                issued, max_age = int(parts[1]), int(parts[2])
            except ValueError:
                return info
            now = time.time()
            info.update({"issued": issued, "max_age": max_age, "expires": issued + max_age,
                         "remaining_days": round((issued + max_age - now) / 86400.0, 2),
                         "expired": now >= issued + max_age, "known": True})
        return info

    def refresh_from_emulator(self, serial: str | None = None, min_days: float = 7.0) -> dict:
        """需要时从模拟器重取凭据。

        这是**唯一**的刷新方式：平台不发 `Set-Cookie`、没有可用的 refresh 端点，
        所以续期只能靠「模拟器里的 App 还登录着」时重新 dump 一次。
        `min_days` 是「剩余不足这么多天才重取」的阈值；传 0 表示强制重取。
        """
        before = self.session_expiry()
        if before.get("known") and not before.get("expired") \
                and before.get("remaining_days") is not None \
                and before["remaining_days"] > min_days:
            return {"refreshed": False, "reason": "剩余 %.2f 天，还没到阈值 %.1f 天"
                    % (before["remaining_days"], min_days), "before": before, "after": before}
        info = self.import_from_emulator(serial)
        return {"refreshed": True, "before": before, "after": self.session_expiry(), "info": info}

    def logout(self) -> None:
        self.tt_token = ""
        self.mobile = ""
        self.jar.clear()
        try:
            os.remove(SESSION_FILE)
        except OSError:
            pass

    def import_from_emulator(self, serial: str | None = None) -> dict:
        """跑 dump_device.py 从模拟器取登录态，然后接管会话。"""
        import subprocess
        script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dump_device.py")
        command = [sys.executable, script] + (["-s", serial] if serial else [])
        result = subprocess.run(command, capture_output=True, text=True, timeout=90)
        if result.returncode != 0:
            raise HgError("从模拟器导出失败：%s" % ((result.stderr or result.stdout).strip()[:300]))
        info = self.apply_device_dump()
        info["log"] = result.stdout.strip().splitlines()
        return info

    def apply_device_dump(self) -> dict:
        """从本机 .runtime/ 里已 dump 的模拟器登录态接管会话（见 dump_device.py）。"""
        cookie_path = os.path.join(RUNTIME, "cookie_header.txt")
        if not os.path.exists(cookie_path):
            raise HgError("还没有从模拟器导出登录态：先跑 python3 tools/login-lab/dump_device.py")
        token = open(cookie_path, encoding="utf-8").read().strip()
        if not token:
            raise HgError("导出的 Cookie 是空的：模拟器里的红果 App 可能还没登录")
        self.apply_token(token)
        profile = self._device_profile() or {}
        # 注意：这里**不**切换签名用的设备档案。实测用模拟器那套 device_id/机型反而会被平台限流，
        # 仓库里那套固定设备参数配这份登录 Cookie 才是稳的；档案只留作记录。
        return {"logged_in": self.logged_in, "mobile": self.mobile, "fields": len(token.split("; ")),
                "emulator_device_id": profile.get("device_id", ""),
                "device_used": self.signer.device.get("device_id", "")}

    def apply_token(self, token: str) -> None:
        """把手机端抓到的 x-tt-token（或整段 Cookie）灌进会话。"""
        token = (token or "").strip()
        if not token:
            raise HgError("token 为空")
        self.tt_token = token
        if "=" in token:
            for part in token.split(";"):
                if "=" in part:
                    name, _, value = part.strip().partition("=")
                    self.jar.set_cookie(http.cookiejar.Cookie(0, name, value, None, False, "", False, False, "/",
                                                              True, False, None, True, None, None, {}))
        self.save()

    # ------------------------------------------------------------------ 请求
    def _headers(self) -> dict:
        """登录态：整段 Cookie 走 `Cookie` 头（实测比 `X-Tt-Token` 稳），另外单独带上 `x-tt-token`。"""
        headers = {"User-Agent": hgsign.hgvendor.APP_UA,
                   "Accept": "application/json; charset=utf-8,application/x-protobuf"}
        if self.tt_token:
            headers["X-Tt-Token"] = self.tt_token
            if "=" in self.tt_token:
                headers["Cookie"] = self.tt_token
        else:
            cookies = self.cookies()
            if cookies:
                headers["Cookie"] = "; ".join("%s=%s" % (k, v) for k, v in cookies.items())
        return headers

    def request(self, path: str, params: dict | None = None, host: str = API_HOSTS[0],
                method: str = "GET", body: bytes | None = None, signed: bool = True,
                timeout: int = 12, form: bool = False) -> tuple[int, dict, bytes]:
        """发一次请求。

        ``form=True`` 时把 ``params`` 编成 ``application/x-www-form-urlencoded`` 放进 body
        （passport 的部分端点只认 POST + form，见 [sms_login]），签名与头照常。
        """
        params = dict(params or {})
        base = host.rstrip("/") + path
        if form:
            payload = urllib.parse.urlencode(params).encode()
            signed_request = self.signer.sign(base, self.signer.common_values({}), body=payload,
                                              mode=self.mode, extra_headers=self._headers())
            signed_request["headers"]["Content-Type"] = "application/x-www-form-urlencoded"
            url, headers, payload = signed_request["url"], signed_request["headers"], signed_request["body"]
            method = "POST"
        elif signed:
            signed_request = self.signer.sign(base, self.signer.common_values(params), body=body,
                                              mode=self.mode, extra_headers=self._headers())
            url, headers, payload = signed_request["url"], signed_request["headers"], signed_request["body"]
        else:
            url = base + ("?" + urllib.parse.urlencode(params) if params else "")
            headers, payload = self._headers(), body
        request = urllib.request.Request(url, data=payload, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=timeout) as response:
                status, response_headers, raw = response.status, dict(response.headers), response.read()
        except urllib.error.HTTPError as error:
            status, response_headers, raw = error.code, dict(error.headers or {}), error.read()
        # 头大小写不统一，统一按小写取，避免把 "…" 拼进字符串时踩到非字符串类型。
        lower = {str(k).lower(): v for k, v in response_headers.items()}
        entry = {"at": time.strftime("%H:%M:%S"), "path": path, "status": status, "bytes": len(raw),
                 "logid": str(lower.get("x-tt-logid", "") or ""),
                 "agw_login": str(lower.get("x-tt-agw-login", "") or ""),
                 "set_cookie": str(lower.get("set-cookie", "") or "")}
        self.log.append(entry)
        del self.log[:-80]
        return status, response_headers, raw

    def json(self, path: str, params: dict | None = None, host: str = API_HOSTS[0],
             method: str = "GET", body: bytes | None = None, signed: bool = True,
             form: bool = False) -> dict:
        status, _, raw = self.request(path, params, host, method, body, signed, form=form)
        if not raw:
            raise HgError("接口返回空响应体（HTTP %d）：平台对匿名设备常这样回" % status)
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            raise HgError("响应不是 JSON（HTTP %d）：%s" % (status, raw[:160].decode("utf-8", "replace")))

    # ------------------------------------------------------------------ 手机验证码
    def send_sms(self, mobile: str, sms_type: str = SMS_TYPE) -> dict:
        """发登录验证码（``type=1``）。返回平台原始 data（含 ``mobile_ticket``）。"""
        mobile = mobile.strip()
        if not (mobile.isdigit() and len(mobile) == 11):
            raise HgError("手机号格式不对（要 11 位数字）")
        data = self.json("/passport/mobile/send_code/v1/",
                         {"aid": "8662", "mobile": mobile, "type": str(sms_type),
                          "device_platform": "android", "app_name": "novelread", "version_code": "73932"},
                         host=PASSPORT_HOST)
        payload = data.get("data") or {}
        if data.get("message") != "success":
            raise HgError("验证码未发出：%s（error_code=%s）"
                          % (payload.get("description") or "未知原因", payload.get("error_code")), data)
        self.mobile = mobile
        self.last_ticket = payload.get("mobile_ticket", "")
        self.last_sms_type = str(sms_type)
        self.save()
        return payload

    def sms_login(self, mobile: str, code: str, mobile_ticket: str = "",
                  sms_type: str = "") -> dict:
        """验证码换登录态：把候选端点逐个试一遍，把每一次的平台原文都带回来。

        顺序依据：``validate_code`` 是校验码接口（先校验、再登录），
        但 aid=8662 上它一律回 ``error_code=1``（会话过期），说明它要真机那一整套
        passport 会话（device_register / 前置登录票据）才认；``login`` 系列则直接回
        ``error_code=16 该应用无权限``。这里两条路都留，哪个通就用哪个。
        """
        code = code.strip()
        # 红果的登录验证码实测是 **4 位**（「用于手机验证码登录」那条），所以这里放开长度。
        if not (code.isdigit() and 4 <= len(code) <= 8):
            raise HgError("验证码要 4~8 位数字")
        ticket = mobile_ticket or getattr(self, "last_ticket", "")
        kind = str(sms_type or getattr(self, "last_sms_type", SMS_TYPE))
        params = {"aid": "8662", "mobile": mobile, "code": code, "type": kind,
                  "device_platform": "android", "app_name": "novelread", "version_code": "73932"}
        if ticket:
            params["mobile_ticket"] = ticket

        attempts: list[dict] = []
        candidates = [
            ("validate_code/v1", "/passport/mobile/validate_code/v1/", False),
            ("validate_code/v1·form", "/passport/mobile/validate_code/v1/", True),
            ("login/v1", "/passport/mobile/login/v1/", False),
            ("login/v1·form", "/passport/mobile/login/v1/", True),
            ("login/v2", "/passport/mobile/login/v2/", False),
            ("login_or_register/v1", "/passport/mobile/login_or_register/v1/", False),
            ("register/v1", "/passport/mobile/register/v1/", False),
            ("combine_login/v1", "/passport/mobile/combine_login/v1/", False),
        ]
        for name, path, form in candidates:
            try:
                data = self.json(path, params, host=PASSPORT_HOST, form=form)
            except HgError as error:
                attempts.append({"step": name, "error": str(error)})
                continue
            attempts.append({"step": name, "response": data})
            payload = data.get("data") or {}
            sessionid = self.cookies().get("sessionid", "")
            if (data.get("message") == "success" and payload.get("user_id")) or sessionid:
                self.mobile = mobile
                self.tt_token = (payload.get("x_tt_token") or payload.get("sessionid") or sessionid
                                 or payload.get("user_id") and "")
                self.save()
                return {"ok": True, "steps": attempts, "cookies": self.cookies(),
                        "account": self.account_info()}
        last = (attempts[-1].get("response", {}).get("data") or {}) if attempts else {}
        raise HgError("登录未完成：%s（error_code=%s）"
                      % (last.get("description") or "平台未返回登录态", last.get("error_code")),
                      {"steps": attempts})

    def account_info(self) -> dict:
        """拿账号信息来判定「到底登录成功没有」：passport 侧 + App 侧各看一眼。"""
        result = {}
        try:
            result["passport"] = self.json("/passport/account/info/v2/", {"aid": "8662"}, host=PASSPORT_HOST)
        except HgError as error:
            result["passport"] = {"error": str(error)}
        try:
            result["app"] = self.json("/reading/user/profile/get/v1/", {})
        except HgError as error:
            result["app"] = {"error": str(error)}
        return result

    # ------------------------------------------------------------------ 扫码
    def qr_new(self) -> dict:
        """试官方扫码接口；全部不可用时由调用方退回本机扫码中继。"""
        attempts = []
        for name, path in QR_CANDIDATES:
            params = {"aid": "8662", "device_platform": "android", "type": SMS_TYPE,
                      "app_name": "novelread", "version_code": "73932", "passport-sdk-version": "50561"}
            try:
                data = self.json(path, params, host=PASSPORT_HOST)
            except HgError as error:
                attempts.append({"channel": name, "error": str(error)})
                continue
            payload = data.get("data") or {}
            if data.get("message") == "success" and (payload.get("qrcode") or payload.get("qr_code") or payload.get("url")):
                return {"ok": True, "channel": name, "check_path": QR_CHECK_CANDIDATES[name],
                        "token": payload.get("token") or payload.get("qrcode_token") or "",
                        "qrcode": payload.get("qrcode") or payload.get("qr_code") or payload.get("url"),
                        "attempts": attempts, "raw": payload}
            attempts.append({"channel": name, "error": payload.get("description") or "平台未返回二维码",
                             "error_code": payload.get("error_code")})
        return {"ok": False, "attempts": attempts}

    def qr_check(self, channel: str, token: str) -> dict:
        path = QR_CHECK_CANDIDATES.get(channel)
        if not path:
            raise HgError("未知扫码通道 %s" % channel)
        return self.json(path, {"aid": "8662", "token": token, "type": SMS_TYPE}, host=PASSPORT_HOST)

    # ------------------------------------------------------------------ 搜索
    def search(self, keyword: str, offset: int = 0, count: int = 20, tab_type: int = 19,
               host: str | None = None) -> dict:
        keyword = (keyword or "").strip()
        if not keyword:
            raise HgError("搜索词为空")
        values = {"query": keyword, "count": str(count), "offset": str(offset),
                  "tab_type": str(tab_type), "bookshelf_search_plan": "4", "use_correct": "true",
                  "user_is_login": "1" if self.logged_in else "0"}
        hosts = [host] if host else API_HOSTS[:3]
        last = None
        # 该接口会间歇性回空 body / PARAM_INVALID（实测约一半概率，与设备档案无关，
        # 同一请求重发即可），因此每个 host 多试几次。
        for candidate in hosts:
            for attempt in range(6):
                try:
                    data = self.json("/reading/bookapi/search/tab/v1/", values, host=candidate)
                except HgError as error:
                    last = error
                    time.sleep(0.5 + attempt)
                    continue
                if data.get("code") == 100103 and attempt < 5:
                    last = HgError("搜索接口暂不可用（code=100103）", data)
                    time.sleep(0.5 + attempt * 0.4)
                    continue
                data["_host"] = candidate
                data["_attempts"] = attempt + 1
                return data
        raise last or HgError("搜索接口不可用")

    def search_page(self, keyword: str, tab_type: int = 19, offset: int = 0) -> dict:
        """把 search/tab 的原始响应整理成界面要的卡片列表。"""
        raw = self.search(keyword, tab_type=tab_type, offset=offset)
        if raw.get("code", -1) != 0:
            raise HgError("搜索接口返回 code=%s message=%s" % (raw.get("code"), raw.get("message")), raw)
        tabs = raw.get("search_tabs") or (raw.get("data") or {}).get("search_tabs") or []
        tab = next((t for t in tabs if t.get("tab_type") == tab_type), None) or (tabs[0] if tabs else None)
        items = []
        if tab:
            for cell in tab.get("data") or []:
                rows = [cell]
                try:
                    rows += json.loads(cell.get("lynx_data") or "{}").get("cell_data") or []
                except Exception:
                    pass
                for row in rows:
                    video = (row.get("video_data") or [{}])[0]
                    series_id = video.get("series_id") or row.get("book_id") or row.get("search_result_id") or ""
                    title = ((row.get("search_high_light") or {}).get("title") or {}).get("text") \
                        or video.get("title") or row.get("cell_name") or ""
                    if not series_id:
                        continue
                    items.append({"id": str(series_id), "title": title,
                                  "cover": video.get("cover") or video.get("cover_url") or "",
                                  "desc": video.get("video_desc") or video.get("sub_title") or "",
                                  "episodes": video.get("episode_cnt") or 0,
                                  "url": "%s/detail?series_id=%s" % (SITE, series_id)})
        return {"keyword": keyword, "tab_type": tab_type, "offset": offset, "host": raw.get("_host"),
                "search_id": (tab or {}).get("search_id", ""), "has_more": (tab or {}).get("has_more", False),
                "next_offset": (tab or {}).get("next_offset", ""), "items": items, "raw_code": raw.get("code")}

    # ------------------------------------------------------------------ 同步
    def sync_progress(self) -> dict:
        body = json.dumps({"book_types": [1], "device_id": int(self.signer.device.get("device_id", 0) or 0),
                           "user_id": 0}).encode()
        return self.json("/reading/bookapi/read_progress/sync/v1/", {}, method="POST", body=body)

    def read_history(self, limit: int = 20) -> dict:
        return self.json("/reading/bookapi/read_history/list/v1/",
                         {"limit": str(limit), "offset": "0", "isFirstLoad": "true", "fullField": "true"})

    def read_progress_list(self) -> dict:
        return self.json("/reading/bookapi/read_progress/list/v1/", {})

    def bookshelf(self) -> dict:
        return self.json("/reading/bookapi/bookshelf/list/v1/", {"server_time": str(int(time.time() * 1000))})

    def bookmarks(self, book_id: str = "") -> dict:
        """书签列表：``book_id`` 必填，缺了平台回 PARAM_INVALID。"""
        if not book_id:
            raise HgError("书签列表要带 book_id：先看阅读历史里的书")
        return self.json("/reading/reader/bookmark/list/", {"book_id": str(book_id)})

    def profile(self, user_id: str = "") -> dict:
        """用户资料：匿名 / 未带 userId 时平台回 ``network err``，如实展示。"""
        params = {"userId": str(user_id)} if user_id else {}
        return self.json("/reading/user/profile/get/v1/", params)

    def sync_all(self) -> dict:
        """同步接口一把抓：哪一个失败只标记哪一个，界面照常显示其它。"""
        result = {}
        # 书签要 book_id：先拿阅读历史里的第一本书，拿不到就跳过这一步
        book_id = ""
        try:
            history = self.read_history()
            rows = ((history.get("data") or {}).get("data_list") or [])
            book_id = str((rows[0] or {}).get("book_id") or "") if rows else ""
        except Exception:
            pass
        steps = [("profile", self.profile), ("progress_sync", self.sync_progress),
                 ("progress_list", self.read_progress_list), ("read_history", self.read_history),
                 ("bookshelf", self.bookshelf)]
        if book_id:
            steps.append(("bookmarks", lambda: self.bookmarks(book_id)))
        for name, call in steps:
            try:
                result[name] = {"ok": True, "data": call()}
            except HgError as error:
                result[name] = {"ok": False, "error": str(error), "payload": error.payload}
            except Exception as error:  # 网络异常
                result[name] = {"ok": False, "error": "%s: %s" % (type(error).__name__, error)}
        return result
