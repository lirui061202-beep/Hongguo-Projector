// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/** One queue, two explicit meanings for the vertical navigation keys. */
class DramaBrowser {
    private var items=emptyList<Series>()
    private var index=0
    var active=false
        private set
    var preview=false
        private set
    val current:Series? get()=if(active) items.getOrNull(index) else null

    fun start(queue:List<Series>,selectedId:String?=null,preview:Boolean=true) {
        items=queue.distinctBy { it.id }
        index=items.indexOfFirst { it.id==selectedId }.coerceAtLeast(0)
        active=items.isNotEmpty()
        this.preview=active && preview
    }
    fun append(queue:List<Series>):Int {
        val old=items.size
        items=(items+queue).distinctBy { it.id }
        return items.size-old
    }
    fun move(direction:Int):Series? {
        if(!active || !preview) return null
        val target=index+direction
        if(target !in items.indices) return null
        index=target
        return current
    }
    fun enter() { if(active) preview=false }
    /** A failed preview can advance once; the finite queue prevents retry loops. */
    fun skipUnavailable():Series? {
        if(!active || !preview || index>=items.lastIndex) return null
        items=items.filterIndexed { position,_ -> position!=index }
        return current
    }
    fun browse() { if(active) preview=true }
    fun close() { active=false; preview=false }
    fun episodeAfterEnd(episode:Int,count:Int):Int? {
        if(!active) return null
        enter()
        return (episode+1).takeIf { it in 0 until count }
    }
}
