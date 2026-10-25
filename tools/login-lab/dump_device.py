# SPDX-License-Identifier: GPL-3.0-only
"""从已登录的模拟器 / 真机里把登录态与设备档案 dump 到本机（本工具唯一的取数入口）。

前提：设备已用官方 App 登录，且 `adb` 能连上（MuMu 默认 `127.0.0.1:16384`，需要 root）。

```bash
python3 tools/login-lab/dump_device.py                  # 自动找设备
python3 tools/login-lab/dump_device.py -s 127.0.0.1:16384
```

产出（都写进 `tools/login-lab/.runtime/`）：

| 文件 | 内容 |
| --- | --- |
| `cookieStore.xml` | App 的 `shared_prefs/cookieStore.xml` 原件（登录 cookie 都在里面） |
| `cookies_parsed.json` | 解析后的 `host|name -> value` |
| `cookie_header.txt` | 可直接当 `Cookie:` 头发出去的整段串（按 snssdk.com 域） |
| `device_profile.json` | 真机 device_id / install_id / cdid / openudid / 机型 / 系统版本 |

`cookieStore.xml` 里存的是 Java 序列化的 `SerializableHttpCookie`，值按
`TC_STRING(0x74) + u2 长度 + UTF-8` 排列，形如 `[name, value, domain, path]`。
"""
from __future__ import annotations

import argparse
import binascii
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RUNTIME = os.path.join(HERE, ".runtime")
PACKAGE = "com.phoenix.read"

COOKIE_KEYS = ["sessionid", "sessionid_ss", "sid_tt", "uid_tt", "uid_tt_ss", "d_ticket", "sid_guard",
               "session_tlb_tag", "store-region", "store-region-src", "install_id", "n_mh", "odin_tt", "ttreq"]
PROFILE_KEYS = ["device_id", "install_id", "device_type", "device_brand", "resolution", "dpi",
                "os_version", "os_api", "cdid", "openudid", "klink_egdi", "rom_version"]


def adb(serial: str | None, *args: str, timeout: int = 20) -> str:
    command = ["adb"] + (["-s", serial] if serial else []) + list(args)
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    if result.returncode != 0:
        raise RuntimeError("adb %s 失败：%s" % (" ".join(args), (result.stderr or "").strip()[:200]))
    return result.stdout


def find_device(explicit: str | None) -> str:
    if explicit:
        return explicit
    out = adb(None, "devices")
    serials = [line.split()[0] for line in out.splitlines()[1:] if line.strip().endswith("device")]
    if not serials:
        raise RuntimeError("没有可用设备：先启动模拟器，或 adb connect <ip:port>")
    return serials[0]


def tc_strings(blob: bytes) -> list[str]:
    """按 Java 序列化 TC_STRING(0x74) + u2 长度 取字符串。"""
    out, i, n = [], 0, len(blob)
    while i < n - 2:
        if blob[i] == 0x74:
            length = (blob[i + 1] << 8) | blob[i + 2]
            if 0 < length <= 2048 and i + 3 + length <= n:
                chunk = blob[i + 3:i + 3 + length]
                if all(0x20 <= byte < 0x7F for byte in chunk):
                    out.append(chunk.decode())
                    i += 3 + length
                    continue
        i += 1
    return out


def parse_cookies(xml: str) -> dict:
    cookies = {}
    for key, value in re.findall(r'<string name="([^"]+)">([^<]*)</string>', xml):
        host = key.split("|")[0].replace("http://", "").replace("https://", "").rstrip("/")
        name = key.split("|")[-1]
        try:
            fields = tc_strings(binascii.unhexlify(value))
        except Exception:
            fields = []
        parsed = None
        for index, field in enumerate(fields):
            if field == name and index + 1 < len(fields):
                parsed = fields[index + 1]
                break
        cookies["%s|%s" % (host, name)] = parsed
    return cookies


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("-s", "--serial", default=None, help="adb 设备号，默认自动取第一个")
    parser.add_argument("--package", default=PACKAGE)
    args = parser.parse_args()
    os.makedirs(RUNTIME, exist_ok=True)
    serial = find_device(args.serial)
    print("设备：%s" % serial)

    def read(path: str) -> str:
        return adb(serial, "shell", "cat %s" % path)

    # 1) cookieStore.xml
    xml = read("/data/data/%s/shared_prefs/cookieStore.xml" % args.package)
    open(os.path.join(RUNTIME, "cookieStore.xml"), "w", encoding="utf-8").write(xml)
    cookies = parse_cookies(xml)
    json.dump(cookies, open(os.path.join(RUNTIME, "cookies_parsed.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    cookie_header = "; ".join("%s=%s" % (name, cookies["snssdk.com|%s" % name])
                              for name in COOKIE_KEYS if cookies.get("snssdk.com|%s" % name))
    open(os.path.join(RUNTIME, "cookie_header.txt"), "w", encoding="utf-8").write(cookie_header)
    print("cookie：解析 %d 条，snssdk.com 域导出 %d 个字段" %
          (sum(1 for v in cookies.values() if v), cookie_header.count("=")))

    # 2) 设备档案
    applog = read("/data/data/%s/shared_prefs/applog_stats.xml" % args.package)
    cdid_xml = read("/data/data/%s/shared_prefs/com.ss.android.deviceregister.utils.Cdid.xml" % args.package)
    openudid_xml = read("/data/data/%s/shared_prefs/snssdk_openudid.xml" % args.package)

    def pick(source: str, name: str) -> str | None:
        match = re.search(r'name="%s"[^>]*value="([^"]*)"' % re.escape(name), source) \
            or re.search(r'name="%s">([^<]*)<' % re.escape(name), source)
        return match.group(1) if match else None

    profile = {
        "device_id": pick(applog, "device_id"),
        "install_id": pick(applog, "install_id"),
        "cdid": pick(cdid_xml, "cdid"),
        "openudid": pick(openudid_xml, "clientudid"),
        "uuid": pick(applog, "device_id"),
        "device_token": pick(applog, "device_token"),
        "klink_egdi": pick(applog, "klink_egdi"),
        "device_type": adb(serial, "shell", "getprop ro.product.model").strip(),
        "device_brand": adb(serial, "shell", "getprop ro.product.brand").strip(),
        "os_version": adb(serial, "shell", "getprop ro.build.version.release").strip(),
        "os_api": adb(serial, "shell", "getprop ro.build.version.sdk").strip(),
        "resolution": (lambda out: out.replace("Physical size: ", "").replace("x", "*").strip())(
            adb(serial, "shell", "wm size")),
        "dpi": (lambda out: out.replace("Physical density: ", "").strip())(adb(serial, "shell", "wm density")),
        # 官方抓包的 query 里每条都带 rom_version（= ro.build.display.id），我们也带上。
        "rom_version": adb(serial, "shell", "getprop ro.build.display.id").strip(),
        "store_region": cookies.get("snssdk.com|store-region"),
        "n_mh": cookies.get("snssdk.com|n_mh"),
        "ttreq": cookies.get("snssdk.com|ttreq"),
        "odin_tt": cookies.get("snssdk.com|odin_tt"),
        "d_ticket": cookies.get("snssdk.com|d_ticket"),
        "uid_tt": cookies.get("snssdk.com|uid_tt"),
        "sessionid": cookies.get("snssdk.com|sessionid"),
        "session_tlb_tag": cookies.get("snssdk.com|session_tlb_tag"),
    }
    json.dump(profile, open(os.path.join(RUNTIME, "device_profile.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    for key in PROFILE_KEYS:
        print("  %-14s %s" % (key, profile.get(key)))
    if not profile.get("device_id"):
        print("警告：没读到 device_id，App 可能还没登录过", file=sys.stderr)
    print("\n已写入 %s" % RUNTIME)


if __name__ == "__main__":
    main()
