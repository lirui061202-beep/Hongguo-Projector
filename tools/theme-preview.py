#!/usr/bin/env python3
"""十四套主题的离线对比图（设置 → 主题外观）。

每个主题只需要三个色值就能完整描述一套新拟态材质：**背景与元素底色**、
**亮部阴影色（左上）**、**暗部阴影色（右下）**。这张图把每个主题的派生结果与状态台阶
并排画出来，用来在没有电视的情况下回答两个问题：

1. 三档明度（底色 / 凸起面 / 凹陷面）够不够分得开；
2. 「常态 = 凸起、选中 = 按进去、焦点 = 被打亮」这三个台阶，在浅色底和深色底上是不是都读得出来。

数值与 `TvTheme.kt` 一一对应（`tools/verify-button-system.py` 会核对这张表），
派生规则也一致：凸起面 = 底色 → 亮部阴影色 45%、凹陷面 = 底色 → 暗部阴影色 45%、
抬升面 72%、文字四级 = 文字基色的 100 / 80 / 65 / 40%。

用法：
    python3 tools/theme-preview.py [输出目录]      # 默认 ../docs/preview
依赖：Pillow、numpy；中文用系统 PingFang / Hiragino。
"""
import os, sys

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "preview")
os.makedirs(OUT, exist_ok=True)

from PIL import Image, ImageDraw, ImageFilter, ImageFont
import numpy as np

# ============ 主题表（与 TvTheme.kt 一一对应） ============
THEMES = [
    dict(key="soft-gray", label="经典浅灰 Soft Gray", scene="音乐播放器、智能家居控制台、计算器工具",
         base=(0xE0, 0xE5, 0xEC), light=(0xFF, 0xFF, 0xFF), dark=(0xA3, 0xB1, 0xC6),
         lit=(0xFF, 0xFF, 0xFF), on_lit=(0x2B, 0x34, 0x44), ink=(0x39, 0x43, 0x5A),
         accent=(0x5F, 0x6E, 0x8C), tips=(0xC8, 0x87, 0x1F), is_dark=False),
    dict(key="creamy-milk", label="优雅奶白 Creamy Milk", scene="极简笔记应用、生活记录、白噪音软件",
         base=(0xF0, 0xF3, 0xF4), light=(0xFF, 0xFF, 0xFF), dark=(0xD1, 0xD9, 0xE6),
         lit=(0xFF, 0xFF, 0xFF), on_lit=(0x3B, 0x42, 0x50), ink=(0x3B, 0x42, 0x50),
         accent=(0x8F, 0x73, 0x55), tips=(0xC0, 0x8A, 0x2A), is_dark=False),
    dict(key="deep-dark", label="深邃暗调 Deep Dark", scene="科技感仪表盘、车载 UI、暗黑模式切换",
         base=(0x2E, 0x32, 0x38), light=(0x38, 0x3D, 0x44), dark=(0x24, 0x27, 0x2C),
         lit=(0xF0, 0xEA, 0xE0), on_lit=(0x1A, 0x16, 0x12), ink=(0xF4, 0xF3, 0xF1),
         accent=(0xDF, 0xB7, 0x7C), tips=(0xFF, 0xC2, 0x4B), is_dark=True),
    dict(key="cool-mint", label="高级柔冷 Cool Mint", scene="医疗健康、天气应用、轻量级办公工具",
         base=(0xDE, 0xE4, 0xE7), light=(0xFF, 0xFF, 0xFF), dark=(0xB8, 0xC4, 0xC7),
         lit=(0xFF, 0xFF, 0xFF), on_lit=(0x2F, 0x3A, 0x40), ink=(0x37, 0x42, 0x4A),
         accent=(0x4E, 0x7F, 0x79), tips=(0xC0, 0x8A, 0x2A), is_dark=False),
    dict(key="porcelain", label="瓷白 Porcelain", scene="极简、奢华",
         base=(0xE8, 0xEB, 0xE7), light=(0xFB, 0xFC, 0xFA), dark=(0xC2, 0xC7, 0xC1),
         lit=(0xFB, 0xFC, 0xFA), on_lit=(0x26, 0x2C, 0x29), ink=(0x34, 0x3B, 0x37),
         accent=(0x6E, 0x7A, 0x72), tips=(0xB8, 0x86, 0x2B), is_dark=False),
    dict(key="moonstone", label="月岩灰 Moonstone", scene="冷静、现代",
         base=(0xDD, 0xE2, 0xE5), light=(0xF8, 0xFA, 0xFB), dark=(0xB7, 0xC0, 0xC5),
         lit=(0xF8, 0xFA, 0xFB), on_lit=(0x24, 0x2B, 0x31), ink=(0x33, 0x3A, 0x3F),
         accent=(0x5C, 0x6A, 0x73), tips=(0xB8, 0x86, 0x2B), is_dark=False),
    dict(key="ivory-sand", label="象牙沙 Ivory Sand", scene="高端、温暖",
         base=(0xE9, 0xE4, 0xDA), light=(0xFC, 0xFA, 0xF4), dark=(0xC6, 0xBF, 0xB2),
         lit=(0xFC, 0xFA, 0xF4), on_lit=(0x2C, 0x28, 0x20), ink=(0x3B, 0x36, 0x2D),
         accent=(0x8A, 0x7A, 0x5E), tips=(0xB0, 0x7C, 0x1F), is_dark=False),
    dict(key="silver-sage", label="银鼠尾草 Silver Sage", scene="精致、自然",
         base=(0xDC, 0xE3, 0xDE), light=(0xF7, 0xFB, 0xF8), dark=(0xB3, 0xBD, 0xB7),
         lit=(0xF7, 0xFB, 0xF8), on_lit=(0x23, 0x2A, 0x25), ink=(0x33, 0x3B, 0x36),
         accent=(0x5E, 0x7A, 0x68), tips=(0xA8, 0x7C, 0x22), is_dark=False),
    dict(key="blue-haze", label="蓝雾 Blue Haze", scene="科技、克制",
         base=(0xDC, 0xE4, 0xEA), light=(0xF7, 0xFB, 0xFE), dark=(0xB2, 0xBE, 0xC8),
         lit=(0xF7, 0xFB, 0xFE), on_lit=(0x20, 0x27, 0x2E), ink=(0x2F, 0x39, 0x44),
         accent=(0x4F, 0x6B, 0x87), tips=(0xB8, 0x86, 0x2B), is_dark=False),
    dict(key="champagne-silk", label="香槟丝绸 Champagne Silk", scene="酒店、奢侈品",
         base=(0xE8, 0xE1, 0xD5), light=(0xFD, 0xF9, 0xF1), dark=(0xC5, 0xBA, 0xA9),
         lit=(0xFD, 0xF9, 0xF1), on_lit=(0x2E, 0x28, 0x18), ink=(0x3B, 0x35, 0x2A),
         accent=(0x8A, 0x75, 0x51), tips=(0xB0, 0x7C, 0x1F), is_dark=False),
    dict(key="dusty-mauve", label="灰雾玫瑰 Dusty Mauve", scene="高级、柔雅",
         base=(0xE4, 0xDE, 0xE1), light=(0xFA, 0xF7, 0xF9), dark=(0xBC, 0xB2, 0xB8),
         lit=(0xFA, 0xF7, 0xF9), on_lit=(0x2A, 0x23, 0x29), ink=(0x3B, 0x33, 0x39),
         accent=(0x7D, 0x62, 0x73), tips=(0xB8, 0x86, 0x2B), is_dark=False),
    dict(key="pale-olive", label="苍岩橄榄 Pale Olive", scene="建筑、艺术",
         base=(0xDF, 0xE1, 0xD8), light=(0xF8, 0xF9, 0xF1), dark=(0xB9, 0xBD, 0xAE),
         lit=(0xF8, 0xF9, 0xF1), on_lit=(0x26, 0x2A, 0x20), ink=(0x37, 0x3A, 0x31),
         accent=(0x6B, 0x70, 0x50), tips=(0xA8, 0x7C, 0x22), is_dark=False),
    dict(key="graphite", label="石墨 Graphite", scene="专业、硬朗",
         base=(0x34, 0x38, 0x3D), light=(0x41, 0x46, 0x4D), dark=(0x25, 0x28, 0x2C),
         lit=(0xED, 0xEF, 0xF2), on_lit=(0x14, 0x18, 0x1C), ink=(0xF4, 0xF3, 0xF1),
         accent=(0x9F, 0xBF, 0xD8), tips=(0xFF, 0xC2, 0x4B), is_dark=True),
    dict(key="midnight-blue", label="午夜蓝 Midnight Blue", scene="科技、旗舰",
         base=(0x30, 0x36, 0x3F), light=(0x3D, 0x45, 0x50), dark=(0x24, 0x29, 0x30),
         lit=(0xEF, 0xF3, 0xF8), on_lit=(0x14, 0x18, 0x1D), ink=(0xF2, 0xF4, 0xF7),
         accent=(0x86, 0xC7, 0xE8), tips=(0xFF, 0xC2, 0x4B), is_dark=True),
]


def mix(a, b, t):
    """底色往某一档阴影色挪 [t]（对应 TvTheme.mix）。"""
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def derive(theme):
    """把一个主题的三个色值派生成绘制需要的全套档位（对应 TvTheme 的派生属性）。"""
    base, light, dark, is_dark = theme["base"], theme["light"], theme["dark"], theme["is_dark"]
    return dict(
        theme,
        surface=mix(base, light, .45), surface_high=mix(base, light, .72),
        well=mix(base, dark, .45), well_deep=mix(base, dark, .72),
        shadow_dark_a=int((.62 if is_dark else .72) * 255),
        shadow_light_a=int((.62 if is_dark else .72) * 255),
        inner_dark_a=int((.50 if is_dark else .72) * 255),
        inner_light_a=int((.10 if is_dark else .50) * 255),
        lit_glow_a=int((.20 if is_dark else .35) * 255),
    )


FONT = "/System/Library/Fonts/Hiragino Sans GB.ttc"


def font(size, bold=False):
    for idx in ((2, 0) if bold else (0, 2)):
        try:
            return ImageFont.truetype(FONT, size, index=idx)
        except Exception:
            pass
    try:
        return ImageFont.truetype("/System/Library/Fonts/STHeiti Medium.ttc", size)
    except Exception:
        return ImageFont.load_default()


class Canvas:
    """复刻 SoftDrawable 的绘制顺序：柔光 = 形状的模糊剪影 + 偏移，凹陷 = 两条 45° 渐变裁剪到面板内。"""

    def __init__(self, w, h, bg):
        self.w, self.h = w, h
        self.img = Image.new("RGBA", (w, h), bg + (255,))
        self.d = ImageDraw.Draw(self.img)

    def mask(self, rect, radius):
        m = Image.new("L", (self.w, self.h), 0)
        ImageDraw.Draw(m).rounded_rectangle(rect, radius=radius, fill=255)
        return m

    def comp(self, layer):
        self.img = Image.alpha_composite(self.img, layer)
        self.d = ImageDraw.Draw(self.img)

    def tint(self, mask, color):
        l = Image.new("RGBA", (self.w, self.h), color[:3] + (255,))
        l.putalpha(mask.point(lambda v: int(v * color[3] / 255)))
        self.comp(l)

    def blur_off(self, mask, sigma, dx, dy):
        m = mask.filter(ImageFilter.GaussianBlur(max(sigma, .6)))
        return m.transform((self.w, self.h), Image.AFFINE, (1, 0, -dx, 0, 1, -dy))

    def flat(self, rect, radius, color, alpha=255):
        m = self.mask(rect, radius)
        if alpha < 255:
            m = m.point(lambda v: int(v * alpha / 255))
        self.tint(m, color + (255,))

    def plate(self, t, rect, radius, band, fill=None, depth=1.0, glow=None, q=1.0):
        """凸起：右下深投影 + 左上高光（+ 可选外发光），再画面板。"""
        x0, y0, x1, y1 = rect
        p = (x0 + band, y0 + band, x1 - band, y1 - band)
        if glow:
            self.tint(self.blur_off(self.mask(p, radius), band * 1.35 * q * .6, 0, 0), glow)
        if depth > 0:
            self.tint(self.blur_off(self.mask(p, radius), band * 1.05 * q * .6, band * .36 * depth * q, band * .46 * depth * q),
                      t["dark"] + (t["shadow_dark_a"],))
            self.tint(self.blur_off(self.mask(p, radius), band * .95 * q * .6, -band * .32 * depth * q, -band * .40 * depth * q),
                      t["light"] + (t["shadow_light_a"],))
        if fill:
            self.tint(self.mask(p, radius), fill + (255,))
        return p

    def inset(self, t, rect, radius, band, fill, depth=1.0):
        """凹陷：面板 + 裁剪到面板内的两条方向相反的内阴影。"""
        x0, y0, x1, y1 = rect
        p = (x0 + band, y0 + band, x1 - band, y1 - band)
        self.tint(self.mask(p, radius), fill + (255,))
        clip = np.asarray(self.mask(p, radius), dtype=np.float32) / 255.0
        yy, xx = np.mgrid[0:self.h, 0:self.w]
        reach = max(band * 2.6 * depth, 1.0)
        a_dark = (1 - np.clip(((xx - p[0]) + (yy - p[1])) / (2 * reach), 0, 1)) * t["inner_dark_a"] * clip
        a_light = (1 - np.clip(((p[2] - xx) + (p[3] - yy)) / (2 * reach), 0, 1)) * t["inner_light_a"] * clip
        base = np.asarray(self.img, dtype=np.float32)
        for a, color in ((a_dark, t["dark"]), (a_light, t["light"])):
            al = (a / 255.0)[..., None]
            base[..., :3] = base[..., :3] * (1 - al) + np.array(color, dtype=np.float32) * al
        self.img = Image.fromarray(np.clip(base, 0, 255).astype(np.uint8), "RGBA")
        self.d = ImageDraw.Draw(self.img)
        return p

    def text(self, xy, s, size=16, color=(255, 255, 255), bold=False, anchor="la"):
        self.d.text(xy, s, font=font(size, bold), fill=color + (255,), anchor=anchor)

    def rect(self, box, color, radius=0):
        self.d.rounded_rectangle(box, radius=radius, fill=color + (255,))

    def swatch(self, box, color, label, t, radius=6):
        self.rect(box, color, radius)
        self.text((box[0], box[3] + 6), label, 13, t["ink"] if sum(t["base"]) > 380 else (0xF4, 0xF3, 0xF1))


def alpha(ink, ratio):
    return tuple(int(c * ratio) for c in ink)


def hexof(c):
    return "#%02X%02X%02X" % c


def luminance(c):
    """WCAG 相对亮度。"""
    channels = []
    for value in c:
        s = value / 255
        channels.append(s / 12.92 if s <= .03928 else ((s + .055) / 1.055) ** 2.4)
    return .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2]


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + .05) / (min(la, lb) + .05)


def over(color, ratio, background):
    """把 [ratio] 不透明度的 [color] 压在 [background] 上（Kotlin 侧的 alpha 令牌就是这么生效的）。"""
    return tuple(round(background[i] * (1 - ratio) + color[i] * ratio) for i in range(3))


COL = 470
PAD = 26
BLOCK = 1430                      # 每套主题一块：三档材质 + 状态台阶 + 海报卡 + 选集网格 + 线 + 文字
COLS = 7                          # 14 套排成 7 列 × 2 行；再宽就看不清单块里的细节了
ROWS = (len(THEMES) + COLS - 1) // COLS
W = PAD * 2 + COL * COLS
H = PAD + ROWS * (BLOCK + PAD)
canvas = Canvas(W, H, (0x18, 0x19, 0x1C))

for index, raw in enumerate(THEMES):
    t = derive(raw)
    x0 = PAD + (index % COLS) * COL
    x1 = x0 + COL - 22
    y0 = PAD + (index // COLS) * (BLOCK + PAD)
    ink = t["ink"]

    # 每一格就是一块完整的材质：先铺底色，再在上面画各个状态。
    canvas.rect((x0 - 8, y0, x1 + 8, y0 + BLOCK), t["base"], 18)
    canvas.text((x0 + 6, y0 + 40), t["label"], 26, ink, True)
    canvas.text((x0 + 6, y0 + 76), t["scene"], 13, over(ink, .65, t["base"]))
    canvas.text((x0 + 6, y0 + 100), "%s  %s  %s" % (hexof(t["base"]), hexof(t["light"]), hexof(t["dark"])), 13, over(ink, .65, t["base"]))

    # —— 三档材质 ——
    y = y0 + 140
    canvas.text((x0 + 6, y - 22), "三档材质", 15, over(ink, .8, t["base"]), True)
    for i, (name, color) in enumerate([("底色", t["base"]), ("凸起面", t["surface"]), ("抬升面", t["surface_high"]),
                                       ("凹陷面", t["well"]), ("深凹槽", t["well_deep"])]):
        box = (x0 + 6 + i * 84, y, x0 + 6 + i * 84 + 74, y + 54)
        canvas.rect(box, color, 8)
        canvas.text((box[0] + 5, box[1] + 6), name, 12, over(ink, .65, t["base"]))
    y += 92

    # —— 状态台阶：常态（凸起）/ 选中（按进去）/ 焦点（打亮）——
    canvas.text((x0 + 6, y - 22), "状态：凸起 / 按进去 / 打亮", 15, over(ink, .8, t["base"]), True)
    bw, bh = 132, 58
    normal = (x0 + 6, y, x0 + 6 + bw, y + bh)
    canvas.plate(t, normal, 26, 6, t["surface"])
    canvas.text(((normal[0] + normal[2]) / 2, (normal[1] + normal[3]) / 2), "常态", 16, ink, True, "mm")

    selected = (x0 + 6 + bw + 14, y, x0 + 6 + bw * 2 + 14, y + bh)
    canvas.inset(t, selected, 26, 6, t["well"], depth=1.15)
    canvas.text(((selected[0] + selected[2]) / 2, (selected[1] + selected[3]) / 2), "选中", 16, ink, True, "mm")

    focused = (x0 + 6, y + bh + 14, x0 + 6 + bw, y + bh * 2 + 14)
    canvas.plate(t, focused, 26, 6, t["lit"], depth=1.2, glow=t["lit"] + (t["lit_glow_a"],))
    canvas.text(((focused[0] + focused[2]) / 2, (focused[1] + focused[3]) / 2), "焦点", 16, t["on_lit"], True, "mm")
    canvas.text((x0 + 6 + bw + 14, y + bh + 30), "焦点 = 材质最亮的一档", 13, over(ink, .65, t["base"]))
    canvas.text((x0 + 6 + bw + 14, y + bh + 50), "选中 = 凹进去（无颜色）", 13, over(ink, .65, t["base"]))
    y += bh * 2 + 40

    # —— 海报卡：常态 / 焦点 ——
    canvas.text((x0 + 6, y - 22), "海报卡：常态 / 焦点（抬一档 + 更深的柔光）", 15, over(ink, .8, t["base"]), True)
    card_w = (COL - 34) / 2 - 8
    # 卡片高度按海报比例（4:3 竖版）反推：卡里先放海报井，再放一行剧名，否则海报会顶出卡片。
    card_h = 300
    for i, is_focus in enumerate((False, True)):
        cx = x0 + 6 + i * (card_w + 16)
        card = (cx, y, cx + card_w, y + card_h)
        p = canvas.plate(t, card, 14, 6, t["surface_high"] if is_focus else t["surface"],
                         depth=1.35 if is_focus else 1.0,
                         glow=(t["lit"] + (t["lit_glow_a"],)) if is_focus else None)
        inner = 12
        pr = (p[0] + inner, p[1] + inner + 2, p[2] - inner, p[1] + inner + 2 + (p[2] - p[0] - 2 * inner) * 4 / 3)
        canvas.flat(pr, 12, mix(t["base"], t["dark"], .62))
        canvas.text((p[0] + 9, pr[3] + 8), "这是剧名标题一行", 15, ink if is_focus else over(ink, .65, t["base"]), True)
    y += card_h + 36

    # —— 选集网格：常态 / 当前集（常亮，和主操作同一档）/ 焦点 ——
    canvas.text((x0 + 6, y - 22), "选集网格：常态 / 当前集 / 焦点", 15, over(ink, .8, t["base"]), True)
    panel = (x0 + 6, y, x1, y + 86)
    canvas.inset(t, panel, 24, 10, t["well"])
    cw = (panel[2] - panel[0] - 20 - 4 * 14) / 5
    for k in range(5):
        cx = panel[0] + 10 + k * (cw + 14)
        cell = (cx, panel[1] + 10, cx + cw, panel[1] + 68)
        if k == 2:
            canvas.plate(t, cell, 14, 6, t["lit"], depth=1.2, glow=t["lit"] + (t["lit_glow_a"],))
            canvas.text(((cell[0] + cell[2]) / 2, (cell[1] + cell[3]) / 2), str(k + 1), 16, t["on_lit"], True, "mm")
        elif k == 1:
            canvas.plate(t, cell, 14, 6, t["lit"], depth=1.15)
            canvas.text(((cell[0] + cell[2]) / 2, (cell[1] + cell[3]) / 2), str(k + 1), 16, t["on_lit"], True, "mm")
        else:
            canvas.plate(t, cell, 14, 6, t["surface"])
            canvas.text(((cell[0] + cell[2]) / 2, (cell[1] + cell[3]) / 2), str(k + 1), 16, ink, True, "mm")
    y += 122

    # —— 强调色只做线与小色块 ——
    canvas.text((x0 + 6, y - 22), "强调色只做线：进度条 / 名次星标 / 提示色", 15, over(ink, .8, t["base"]), True)
    track = (x0 + 6, y, x1, y + 8)
    canvas.rect(track, t["well_deep"], 4)
    canvas.rect((track[0], track[1], track[0] + (track[2] - track[0]) * .62, track[3]), t["accent"], 4)
    canvas.rect((x0 + 6, y + 26, x0 + 20, y + 40), t["accent"], 7)
    canvas.text((x0 + 30, y + 33), "热门推荐", 18, over(ink, .8, t["base"]), True, "lm")
    canvas.rect((x1 - 20, y + 24, x1, y + 42), t["tips"], 4)
    y += 74

    # —— 文字四级 ——
    canvas.text((x0 + 6, y - 22), "文字四级（正文 / 标题 / 次要 / 辅助）", 15, over(ink, .8, t["base"]), True)
    for i, (name, ratio) in enumerate([("正文 100%", 1.0), ("标题 84%", .84), ("次要 70%", .70), ("辅助 62%", .62)]):
        canvas.text((x0 + 6, y + i * 30), "第 12 集 · 03:41    " + name, 16, over(ink, ratio, t["base"]), i < 2)

canvas.img.convert("RGB").save(os.path.join(OUT, "theme.png"))

# ============ 派生结果自检 ============
# 十四套主题共用同一套派生规则，因此可以用一组硬门槛把「能不能看清」钉死：
# 正文过 WCAG AA（4.5:1）、辅助文字与非文字元素过 3:1、焦点面必须明显亮于底色、
# 凹陷面必须与底色分得开（电视 3 米外靠的是明度台阶，不是颜色）。
LIMITS = [
    ("正文 100%/base", lambda t: contrast(t["ink"], t["base"]), 4.5),
    ("正文 84%/base", lambda t: contrast(over(t["ink"], .84, t["base"]), t["base"]), 4.5),
    ("次要 70%/base", lambda t: contrast(over(t["ink"], .70, t["base"]), t["base"]), 3.0),
    ("辅助 62%/base", lambda t: contrast(over(t["ink"], .62, t["base"]), t["base"]), 3.0),
    ("亮面上的字 onLit/lit", lambda t: contrast(t["on_lit"], t["lit"]), 4.5),
    # 浅色主题的「打亮」是有上限的：底色到纯白只有 1.12–1.28:1，这是浅色新拟态的固有限制
    # （见 docs/UI_NEUMORPHISM.md 第 7 节的待验收项）。因此这里只要求「亮得出来」，
    # 真机上的辨识度还要靠光晕与 1.05 倍放大一起承担。
    ("焦点面 lit/base", lambda t: contrast(t["lit"], t["base"]), 1.10),
    ("凹陷面 well/base", lambda t: contrast(t["well"], t["base"]), 1.05),
    ("抬升面 surfaceHigh/base", lambda t: contrast(t["surface_high"], t["base"]), 1.05),
    ("强调色 accent/base", lambda t: contrast(t["accent"], t["base"]), 3.0),
]
print("written to", OUT)
print("派生结果自检（对比度）：")
failures = []
for raw in THEMES:
    t = derive(raw)
    values = []
    for name, ratio, limit in LIMITS:
        value = ratio(t)
        values.append("%s %.2f" % (name, value))
        if value < limit:
            failures.append("%s 的 %s 只有 %.2f:1（要求 ≥ %.2f:1）" % (t["label"], name, value, limit))
    print("  " + t["label"] + "：" + "，".join(values))
if failures:
    raise SystemExit("主题自检失败：\n  " + "\n  ".join(failures))
print("%d 套主题全部通过。" % len(THEMES))
