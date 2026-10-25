// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.Call
import okhttp3.Response
import java.io.IOException

/** Cancels only the work owned by one navigation/playback intent, including body reads. */
class RequestScope {
    private var cancelled = false
    private val calls = mutableSetOf<Call>()

    @Synchronized fun cancel() {
        cancelled = true
        calls.forEach { it.cancel() }
        calls.clear()
    }

    @Synchronized private fun attach(call: Call) {
        if (cancelled || Thread.currentThread().isInterrupted) {
            call.cancel()
            throw IOException("请求已取消")
        }
        calls += call
    }

    @Synchronized private fun detach(call: Call) { calls -= call }

    fun <T> run(action: () -> T): T {
        val previous = current.get()
        current.set(this)
        try {
            synchronized(this) { if (cancelled) throw IOException("请求已取消") }
            return action()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    companion object {
        private val current = ThreadLocal<RequestScope>()

        /**
         * 当前线程所属的取消域；不在任何域里时返回 null。
         *
         * 首页要并发取四个榜单，而 [RequestScope] 是**按线程**记录的：工作线程池里的线程
         * 看不到界面线程那一次导航的域，页面切走时也就取消不掉在途的请求。
         * 因此调用方先把界面线程的域取出来（[current]），再用 [withScope] 交给工作线程。
         */
        fun current(): RequestScope? = current.get()

        /** 让这段代码在给定的取消域里跑：域已经取消就直接抛「请求已取消」。 */
        fun <T> withScope(scope: RequestScope?, action: () -> T): T = if (scope == null) action() else scope.run(action)

        fun <T> execute(call: Call, consume: (Response) -> T): T {
            val owner = current.get()
            owner?.attach(call)
            try { return call.execute().use(consume) }
            finally { owner?.detach(call) }
        }
    }
}
