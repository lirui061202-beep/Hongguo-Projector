// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.player

import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import com.hgtv.star.tv.EpisodeCell
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.focusBleed
import com.hgtv.star.tv.tvColumn
import com.hgtv.star.tv.tvDp
import com.hgtv.star.tv.tvRow
import com.hgtv.star.tv.tvScrollHost
import com.hgtv.star.tv.tvText

/**
 * 选集网格：简介页与播放界面共用同一份实现。
 *
 * 两处只有「宿主」不同——播放界面把它放进贴底的 [EpisodePanel] 弹窗，简介页直接贴在页面末尾——
 * 标题行、集数行、单元格尺寸、间距与翻页方式完全一致，改一处两处同步生效。
 *
 * 两处不一致的是**文字颜色与背板**，由 [player] 决定：播放界面压在视频上，永远用
 * [TvStyle.playerText] 的暖白（浅色主题下也不会变成深色字），背板是与播放设置面板同一份的浮层渐变；
 * 简介页贴在页面底色上，跟着主题走，**不画任何背板**——它就是页面里的一块内容，
 * 再套一层底板会读成「一个可以按进去的大盒子」。
 *
 * 一页 20 集（10 列 × 2 行），四向都是「到边缘再翻页」：左右到本行两端翻同一行的相邻页，
 * 第一行按上翻回上一页，最后一行（或本页只有一行时）按下翻到下一页。
 *
 * 没有目标页时 [turn] 返回 false，这次按键交回焦点系统做普通焦点移动：播放面板里焦点停在原地，
 * 简介页第一页第一行按上就离开选集、回到「继续观看 / 收藏 / 完整简介」那一行——
 * 这是简介页唯一的「向上出口」，所以 [turn] 的越界判断不能改成「吃掉按键」。
 * 只有确认单元格才真正换集，翻页只移动焦点。
 */
internal class EpisodeGrid(
    context: Context,
    private val count: Int,
    private val current: Int,
    /** true：宿主是播放界面（压在视频上，文字固定暖白）；false：简介页（跟主题走）。 */
    private val player: Boolean,
    private val select: (Int) -> Unit
) : LinearLayout(context) {

    private val dp = { value: Number -> context.tvDp(value) }
    /** 标题行的字色：播放界面固定暖白，简介页跟主题。 */
    private val titleColor = if (player) TvStyle.playerText else TvStyle.textStrong
    private val dimColor = if (player) TvStyle.playerDim else TvStyle.muted
    private val scroll = ScrollView(context)
    /** 滚动容器的外壳：裁剪由它做，容器自己只留焦点余量（见 TvComponents.tvScrollHost）。 */
    private val scrollHost = context.tvScrollHost(scroll)
    private val grid = context.tvColumn()
    private val range = context.tvText("", 14f, dimColor)
    private var cells: List<EpisodeCell> = emptyList()
    private var group = current / 20
    private var focused = current

    /** true：纵向撑满宿主高度（播放器弹窗）；false：按内容高度自适应（简介页）。 */
    var fillHeight = false
        set(value) {
            field = value
            stretch()
        }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        // 背板：只有播放界面需要——它压在画面上，用与播放设置面板**同一份**浮层渐变
        // （TvStyle.overlayPanel()），两块浮层的透明度因此永远一致；简介页没有背板。
        background = if (player) TvStyle.overlayPanel() else null
        // 横向内边距只在浮层里有意义（浮层是整幅压屏的，内容要离开屏幕边）。
        // 简介页贴在页面里，内容线由页面给（容器的 pageInset），这里不再自己缩进，
        // 于是「选集」这一块与上面的海报、按钮同一条竖线。
        val side = if (player) dp(58) else 0
        setPadding(side, dp(18), side, dp(12))

        // 标题行与集数行跟着单元格一起右移 FOCUS_BLEED：单元格在内层滚动容器里多留了这么多余量。
        val head = context.tvRow().apply { setPadding(dp(TvStyle.FOCUS_BLEED), 0, dp(TvStyle.FOCUS_BLEED), 0) }
        head.addView(context.tvText("选集", 20f, titleColor, bold = true))
        head.addView(
            context.tvText("正在看第 ${current + 1} 集 · 共 $count 集", 14f, dimColor),
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) }
        )
        head.addView(View(context), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
        head.addView(
            context.tvText("左 / 右 / 上 / 下 到边缘翻页", 14f, dimColor)
        )
        addView(head)

        val rangeRow = context.tvRow().apply { setPadding(dp(TvStyle.FOCUS_BLEED), 0, dp(TvStyle.FOCUS_BLEED), 0) }
        rangeRow.addView(range, LinearLayout.LayoutParams(0, -2).apply { weight = 1f })
        addView(rangeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        scroll.isVerticalScrollBarEnabled = false
        scroll.setPadding(0, 0, 0, dp(TvStyle.GAP_TIGHT))
        scroll.focusBleed()
        scroll.addView(grid)
        addView(scrollHost, LinearLayout.LayoutParams(-1, 0))
        stretch()
        render()
    }

    /** 焦点落到当前集：弹窗打开后、或详情页重新构建后调用。 */
    fun focusCurrent() {
        if (cells.isEmpty()) return
        cells[(focused - group * 20).coerceIn(0, cells.lastIndex)].requestFocus()
    }

    /** 当前这一页的第一格：简介页用它把「按钮行 → 选集」的上下指向接上。 */
    fun firstCell(): View? = cells.getOrNull(0)

    /**
     * 到边缘再按方向键就翻页。返回 true 表示这次按键已经被网格用掉，
     * false 交回系统做普通焦点移动（例如第一页再按上就离开选集区域）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key=event.keyCode
        val arrows=listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)
        if(key in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if(event.action==KeyEvent.ACTION_UP) cells.firstOrNull { it.hasFocus() }?.performClick()
            return true
        }
        if(key in arrows && event.action==KeyEvent.ACTION_DOWN) {
            if(cells.none { it.hasFocus() }) { focusCurrent(); return true }
            if(pageKey(key)) return true
            val index=cells.indexOfFirst { it.hasFocus() }
            val target=when(key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if(index%10>0) index-1 else index
                KeyEvent.KEYCODE_DPAD_RIGHT -> if(index%10<9) index+1 else index
                KeyEvent.KEYCODE_DPAD_UP -> index-10
                else -> index+10
            }
            if(target in cells.indices) { cells[target].requestFocus(); return true }
            if(player) return true
        }
        if(key in arrows && event.action==KeyEvent.ACTION_UP) return true
        return super.dispatchKeyEvent(event)
    }

    private fun pageKey(key: Int): Boolean {
        val index = cells.indexOfFirst { it.hasFocus() }
        if (index < 0) return false
        val row = index / 10
        val column = index % 10
        return when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> column == 0 && turn(-1, row, 9)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                (column == 9 || index == cells.lastIndex) && turn(1, 0, 0)
            KeyEvent.KEYCODE_DPAD_UP -> row == 0 && turn(-1, 1, column)
            KeyEvent.KEYCODE_DPAD_DOWN ->
                (row >= 1 || cells.size <= 10) && turn(1, 0, column)
            else -> false
        }
    }

    /** 翻页并渲染；目标页不存在时返回 false，交给系统按普通焦点移动处理。 */
    private fun turn(delta: Int, row: Int, column: Int): Boolean {
        val target = group + delta
        if (target < 0 || target * 20 >= count) return false
        group = target
        focused = (target * 20 + row * 10 + column)
            .coerceIn(target * 20, minOf(target * 20 + 19, count - 1))
        render()
        return true
    }

    private fun stretch() {
        val params = scrollHost.layoutParams as? LinearLayout.LayoutParams ?: return
        if (fillHeight) {
            params.height = 0
            params.weight = 1f
        } else {
            params.height = LinearLayout.LayoutParams.WRAP_CONTENT
            params.weight = 0f
        }
        scrollHost.layoutParams = params
    }

    private fun render() {
        grid.removeAllViews()
        val page = mutableListOf<EpisodeCell>()
        (group * 20 until minOf((group + 1) * 20, count)).toList().chunked(10).forEach { indexes ->
            val line = context.tvRow().apply { setPadding(0, dp(TvStyle.GAP_TIGHT), 0, 0) }
            indexes.forEach { index ->
                val cell = EpisodeCell(context, "${index + 1}", index == current) { select(index) }
                cell.contentDescription = "第 ${index + 1} 集" + if (index == current) "，当前播放" else ""
                line.addView(
                    cell,
                    LinearLayout.LayoutParams(0, dp(TvStyle.BUTTON_NORMAL + 2 * TvStyle.SOFT_BAND)).apply {
                        weight = 1f
                        rightMargin = dp(4)
                    }
                )
                page += cell
            }
            repeat(10 - indexes.size) {
                line.addView(
                    Space(context),
                    LinearLayout.LayoutParams(0, dp(TvStyle.BUTTON_NORMAL + 2 * TvStyle.SOFT_BAND)).apply {
                        weight = 1f
                        rightMargin = dp(4)
                    }
                )
            }
            grid.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        cells = page

        page.forEachIndexed { index, cell ->
            cell.nextFocusLeftId = page[if (index % 10 == 0) index else index - 1].id
            cell.nextFocusRightId =
                page[if (index % 10 == 9 || index == page.lastIndex) index else index + 1].id
            cell.nextFocusUpId = if (index >= 10) page[index - 10].id else View.NO_ID
            cell.nextFocusDownId = if (index + 10 < page.size) page[index + 10].id else View.NO_ID
        }
        range.text = "第 ${group * 20 + 1}—${minOf((group + 1) * 20, count)} 集"
        focusCurrent()
    }
}
