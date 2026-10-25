# 预览图（离线生成，非真机截图）

README 里展示的资源都在这里：

- `app-logo.png`：应用图标（与 APK 内图标同一份几何，`tools/app-icon.py` 生成）。
- `theme.png`：十四套材质主题排成 7 列 × 2 行的对比图（`tools/theme-preview.py`）。
- `1.jpg` – `3.jpg`、`7.jpg` – `9.jpg`：界面截图。
- `4.webp` – `6.webp`：界面截图（搜索 / 分类 / 热播榜，2026-10-14 更新，2560×1440）。
  WebP q88，画质近无损（与原图平均差 1.4），体积约 1/8；原始 PNG 留在 `.local/originals/` 不入库。

维护者头像在 [`docs/maintainers/`](../maintainers/)（256px 圆形透明底 PNG，由 `tools/maintainer-avatars.py` 生成）。

其余校对图（首页、按钮六态、强调色候选、图标白底对照）由脚本输出到 `docs/preview/.local/`，
只在本机查看，不入库：

```sh
python3 tools/neumorphism-preview.py     # → .local/home.png、sheet.png、palette.png
python3 tools/app-icon.py                # → app-logo.png 与 .local/icon.png
python3 tools/theme-preview.py           # → theme.png
# 都需要 Pillow + numpy；中文用系统 PingFang（macOS）
```

**这不是 Android 渲染器的替代品**：字体、字距、真实海报、遥控器焦点移动与低端 GPU 帧率
都不在这里验证，这些仍要在实体电视上验收。
