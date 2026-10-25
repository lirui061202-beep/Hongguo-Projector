#!/usr/bin/env python3
"""生成 README「维护者」一栏用的圆形头像。

三张图都输出成 **圆形、透明底** 的 PNG：GitHub 的 README 渲染会过滤掉 `style`
属性，靠 CSS 的 `border-radius` 做圆角并不可靠，所以圆角直接烧进图片里。

- 有 GitHub 账号的提交人：取 `avatars.githubusercontent.com/<用户名>`（与 GitHub 显示的一致）。
- 没有 GitHub 账号的提交人：按提交邮箱生成确定性的 identicon（同 seed 永远同一张图）。

用法：
    python3 tools/maintainer-avatars.py            # 写入 docs/maintainers/
    python3 tools/maintainer-avatars.py --check    # 只校验（文件在、是圆的、尺寸对）
依赖：Pillow；identicon 用标准库 colorsys + hashlib。
"""

import colorsys
import hashlib
import io
import os
import sys
import urllib.request

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
OUT = os.path.join(REPO, "docs", "maintainers")

SIZE = 256          # 输出边长
SS = 4              # 超采样倍数：圆边靠它抗锯齿
BG = (240, 242, 245)

# (输出文件名, GitHub 用户名) —— 用户名为 None 表示没有 GitHub 账号，走 identicon
PEOPLE = [
    ("pan8664716", "pan8664716"),
    ("N3urda", "N3urda"),
    ("ermilylu", None),
]
IDENTICON_SEEDS = {"ermilylu": "ermilylu@tencent.com"}


def fetch_avatar(user):
    """取 GitHub 头像；网络不可用时回落到仓库里已有的图。"""
    url = "https://avatars.githubusercontent.com/%s?s=%d" % (user, SIZE * 2)
    try:
        with urllib.request.urlopen(url, timeout=20) as resp:
            return Image.open(io.BytesIO(resp.read())).convert("RGBA")
    except Exception as exc:                                   # noqa: BLE001
        cached = os.path.join(OUT, "%s.png" % user)
        if os.path.exists(cached):
            print("  取头像失败（%s），用仓库里已有的图" % exc)
            return Image.open(cached).convert("RGBA")
        raise


def identicon(seed, size):
    """GitHub 风格 5×5 对称 identicon，圆形版本。

    5×5 网格的四角本来就落在圆形之外，直接套圆遮罩会把角上的格子切掉，
    所以这里反过来画：整块圆填主色，再在网格上盖浅色格子——圆边完整，图案也读得出来。
    """
    h = hashlib.sha256(seed.encode()).digest()
    hue = h[0] / 255.0
    r, g, b = colorsys.hsv_to_rgb(hue, 0.72, 0.58)
    base = (int(r * 255), int(g * 255), int(b * 255))
    r2, g2, b2 = colorsys.hsv_to_rgb(hue, 0.08, 0.99)
    cell_bg = (int(r2 * 255), int(g2 * 255), int(b2 * 255))

    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([0, 0, size - 1, size - 1], fill=base + (255,))
    cell = size / 5.0
    i = 0
    for col in range(3):
        for row in range(5):
            if h[2 + i] % 2 == 0:
                for c in (col, 4 - col):
                    d.rectangle([c * cell, row * cell, (c + 1) * cell - 1, (row + 1) * cell - 1],
                                fill=cell_bg + (255,))
            i += 1
    return img


def circle(img, size=SIZE):
    """先按短边居中裁成正方形，再套一个超采样的圆形遮罩。

    identicon 已经在圆里画好了（四角本来就是透明的），这里只做归一化。
    """
    side = min(img.width, img.height)
    img = img.crop(((img.width - side) // 2, (img.height - side) // 2,
                    (img.width + side) // 2, (img.height + side) // 2))
    img = img.resize((size * SS, size * SS), Image.LANCZOS)
    mask = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size * SS - 1, size * SS - 1], fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    img = img.resize((size, size), Image.LANCZOS)
    img.putalpha(mask)
    return img


def build():
    os.makedirs(OUT, exist_ok=True)
    made = []
    for name, user in PEOPLE:
        if user:
            src = fetch_avatar(user)
            how = "GitHub 头像"
        else:
            src = identicon(IDENTICON_SEEDS[name], SIZE * SS)
            how = "identicon（无 GitHub 账号）"
        path = os.path.join(OUT, "%s.png" % name)
        circle(src).save(path, optimize=True)
        made.append((path, how))
    return made


def check():
    bad = 0
    for name, _ in PEOPLE:
        path = os.path.join(OUT, "%s.png" % name)
        if not os.path.exists(path):
            print("缺失：%s" % os.path.relpath(path, REPO))
            bad += 1
            continue
        im = Image.open(path)
        if im.size != (SIZE, SIZE):
            print("尺寸不对：%s 是 %dx%d，应为 %dx%d"
                  % (os.path.relpath(path, REPO), im.width, im.height, SIZE, SIZE))
            bad += 1
        if im.mode != "RGBA" or im.getpixel((0, 0))[3] != 0:
            print("不是圆形透明底：%s（左上角应当完全透明）" % os.path.relpath(path, REPO))
            bad += 1
    if not bad:
        print("头像同源：%d 张 %dpx 圆形透明底 PNG" % (len(PEOPLE), SIZE))
    return bad


def main():
    if "--check" in sys.argv[1:]:
        sys.exit(1 if check() else 0)
    for path, how in build():
        print("写出 %s（%s，%d B）" % (os.path.relpath(path, REPO), how, os.path.getsize(path)))


if __name__ == "__main__":
    main()
