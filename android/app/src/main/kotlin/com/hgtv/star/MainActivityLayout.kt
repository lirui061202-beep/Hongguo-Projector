// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.TextUtils
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hgtv.star.core.*
import java.util.concurrent.Executors
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.SoftStyle
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.TvTheme
import com.hgtv.star.tv.TvThemeStore
import com.hgtv.star.tv.focusOverflow
import com.hgtv.star.list.showCatalog

// 本文件是从 MainActivity.kt 拆出来的「尺寸换算、控件工厂（按钮 / 文本 / 行 / 页骨架）与窗口背景、主题切换」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.dp(value: Number)=(value.toFloat()*resources.displayMetrics.density).toInt()
internal fun MainActivity.widthDp()=resources.displayMetrics.widthPixels/resources.displayMetrics.density
/** 页面左右安全边距。剧架按同一数值计算行内可用宽度，避免整行填不满。 */
/** 页面左右安全边距，对齐爆米花 77.5dp 栅格（1280dp 画布上左右各 78dp）。 */
internal fun MainActivity.pageInset()=dp(78)
/**
 * 页面容器比内容边距再早开始 [TvStyle.FOCUS_BLEED]。
 *
 * 容器的边界就是裁剪边界：内容从 78dp 开始，容器从 71dp 开始，滚动容器再把这个余量补回来，
 * 于是最左 / 最右那一张卡片（或按钮）放大后的焦点描边仍有地方画，不会被容器裁掉。
 * 内容本身的位置完全不变。
 */
internal fun MainActivity.pageBleed()=dp(TvStyle.FOCUS_BLEED)
internal fun MainActivity.lp(w: Int=LinearLayout.LayoutParams.MATCH_PARENT,h: Int=LinearLayout.LayoutParams.WRAP_CONTENT)=LinearLayout.LayoutParams(w,h)
/**
 * 内容区一行卡片的可用宽度（就是栅格铺满一行的宽度）。
 *
 * 页面左右各让出 [pageInset]，再各留 [TvStyle.FOCUS_BLEED] 的一半余量，
 * 因此一行 6 张卡的左右边界就是「内容线 → 内容线 + 这个宽度」。
 * 分类页二级分类那一行也按它定宽，两块区域因此左右对齐。
 */
internal fun MainActivity.gridContentWidth()=(resources.displayMetrics.widthPixels-2*pageInset()-2*dp(2)).coerceAtLeast(dp(320))
/**
 * 新拟态底板：凸起 / 凹陷 / 只描边 / 压图暗角。
 * 参数顺序固定，调用方一律用具名参数，避免把 band 和 radius 写反。
 */
internal fun MainActivity.softPanel(
    fill: Int = TvStyle.surface,
    radius: Int = TvStyle.RADIUS_CARD,
    band: Int = TvStyle.SOFT_BAND,
    style: SoftStyle = SoftStyle.RAISED,
    ring: Int = Color.TRANSPARENT,
    ringWidth: Int = 0,
    glow: Int = Color.TRANSPARENT,
    depth: Float = 1f
) = TvStyle.soft(this,fill,radius,band,style,ring,ringWidth,glow,depth)
internal fun MainActivity.text(value: String,size: Float=16f,color: Int=white)=TextView(this).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
internal fun MainActivity.column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; focusOverflow() }
internal fun MainActivity.row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; focusOverflow() }
/**
 * 全站按钮只有一个实现：[TvButton]。高度、内边距、字号、圆角、焦点反馈都来自
 * [ButtonSize] / [ButtonTone]，页面代码不再自己拼样式。
 */
internal fun MainActivity.button(label: String,selected: Boolean=false,tone: ButtonTone=ButtonTone.SECONDARY,size: ButtonSize=ButtonSize.NORMAL,pad: Int?=null,onClick: ()->Unit): TvButton =
    TvButton(this,label,size,tone,action=onClick,pad=pad).apply { if(selected) setSelectedState(true) }
/**
 * 往容器里放一个按钮，并保证间距只有一个来源：横向容器加左边距、纵向容器加上边距，
 * 第一个子控件不留边距。因此不会再有「两个按钮贴在一起」或「行尾多出 8dp 把整行推偏」。
 */
/**
 * 行内按钮。三种宽度策略，按需选一种：
 *
 * - 默认：按文字宽度自适应（[LinearLayout.LayoutParams.WRAP_CONTENT]）；
 * - [width]：固定像素宽度（很少用，像素要自己算，容易算错）；
 * - [weight]：**等分父容器的剩余宽度**（简介页那一排入口用的就是它）。
 *   宽度由父容器实测后分配，因此不用去猜「这一行到底有多少可用宽度」——
 *   简介页的按钮行在海报右侧的信息列里，不是整屏宽，按整屏算就会溢出。
 */
internal fun MainActivity.addButton(parent: LinearLayout,label: String,selected: Boolean=false,tone: ButtonTone=ButtonTone.SECONDARY,size: ButtonSize=ButtonSize.NORMAL,gap: Int=TvStyle.GAP_INLINE,width: Int?=null,weight: Float?=null,pad: Int?=null,onClick: ()->Unit): TvButton {
    val view=button(label,selected,tone,size,pad=pad,onClick=onClick)
    if(weight!=null) {
        // 等分时清掉最小宽度：`TvButton` 默认有 92dp 的最小宽度，等分到的宽度比它小时
        // 控件会按最小宽度量出来，四个加起来就顶破父容器（屏幕上就是「超出去了」）。
        // 这里让等分说了算，文字放不下由 ellipsize 收尾。
        view.minimumWidth=0
    }
    val params=lp().apply {
        if(weight!=null) { this.width=0; this.weight=weight } else this.width=width ?: LinearLayout.LayoutParams.WRAP_CONTENT
    }
    if(parent.childCount>0) {
        if(parent.orientation==LinearLayout.HORIZONTAL) params.leftMargin=dp(gap) else params.topMargin=dp(gap)
    }
    parent.addView(view,params); return view
}
/** 与 [addButton] 同一套间距规则，给需要自定义 LayoutParams 的行内控件用。 */
internal fun MainActivity.gapInto(row: LinearLayout,view: View,params: LinearLayout.LayoutParams,gap: Int=TvStyle.GAP_INLINE): View {
    if(row.childCount>0) {
        if(row.orientation==LinearLayout.HORIZONTAL) params.leftMargin=dp(gap) else params.topMargin=dp(gap)
    }
    row.addView(view,params); return view
}
internal fun MainActivity.applyWindowBackground() {
    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(TvStyle.background))
}
/**
 * 切换主题：落盘 → 换色（含丢掉柔光位图缓存）→ 重建当前页面。
 *
 * 重建这一步不能省：已经建好的 View 上挂着旧的背景 Drawable，里面是上一套材质的
 * 投影位图与色值。设置页的重建很轻（`showCatalog()` 在设置页只重画设置页），
 * 因此这里直接重建，再把焦点按回刚点的那一行主题。
 */
internal fun MainActivity.switchTheme(value: TvTheme) {
    if(value==TvStyle.theme) { themeRows[value]?.requestFocus(); return }
    TvThemeStore.save(this,value)
    TvStyle.applyTheme(value)
    applyWindowBackground()
    showCatalog()
    // 重建之后把焦点按回刚点的那一行：先同步要一次，再在下一帧要一次——
    // 新视图要等这一帧布局完成，滚动容器才把它带进可见区（设置页在 720p 画布上可能刚好要滚一点）。
    themeRows[value]?.let { row -> row.requestFocus(); row.post { row.requestFocus() } }

}
/**
 * 「我的」页的页头：**圆形头像 + 昵称（小字）+ 重新导入 / 清除**。
 *
 * 这一行在导航栏下面（与分类页的二级分类同一层）：左边是圆形头像，右边是昵称，
 * 再往右是那两个只在换账号时才用的按钮。上面那一行是「看哪一栏」，这一行是「这是谁」。
 *
 * 未导入参数时：头像是灰底占位、昵称写「未导入账号参数」，右边只有一个「导入账号参数」按钮——
 * 没登录时「重新导入 / 清除」没有意义。
 */
