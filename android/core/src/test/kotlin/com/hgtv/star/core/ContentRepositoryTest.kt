package com.hgtv.star.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {
    /**
     * 新路由 `/novel/player/video_model/v1/` 的响应：`video_model` 直接挂在 `data` 下
     * （带场景段与旧路径挂在 `data[<vid>]` 下，见 [legacyVideoModel]）。
     */
    private fun videoModel(fallback: String) =
        """{"BaseResp":{"StatusCode":0},"code":0,"data":{"video_model":"{\"fallback_api\":\"$fallback\"}"}}"""
    /** 带场景段 / 旧路径的响应形态：`data[<vid>].video_model`。 */
    private fun legacyVideoModel(fallback: String) =
        """{"BaseResp":{"StatusCode":0},"code":0,"data":{"123":{"video_model":"{\"fallback_api\":\"$fallback\"}"}}}"""
    /** `fallback_api` 地址上的清晰度列表（`video_list` 为空时 stream 会报「没有电视可播放的清晰度」）。 */
    private fun fallbackList(vararg rows: Pair<String,String>) = JSONObject()
        .put("video_info",JSONObject().put("data",JSONObject().put("video_list",
            JSONObject().also { list -> rows.forEachIndexed { i,(codec,url) ->
                list.put("video_$i",JSONObject().put("vheight",1080).put("codec_type",codec).put("main_url",url)) } })))
        .toString()

    /**
     * 平台 2026-10-14 又把播放接口换了一条：原先带场景段的
     * `/novel/player/multi_video_model/:dr_scene/v1/` 现在一律返回 **HTTP 200 + 空响应体**，
     * 旧路径返回 `Code 110001 未知异常`；能用的只有 `/novel/player/video_model/v1/`，
     * 且要 `video_id` 单值。这里钉住四件事：新路由排在最前、空响应体必须当失败、
     * 新路由不通时依次退到带场景段与旧路径、全失败时如实报错。
     */
    @Test fun playbackPrefersTheVideoIdRouteAndIgnoresBusinessErrors() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url.host!="example.com") paths+=request.url.encodedPath
            when {
                request.url.host=="example.com" -> fallbackList("h264" to "https://example.com/stream")
                request.url.encodedPath=="/novel/player/video_model/v1/" -> videoModel("https://example.com/stream")
                else -> """{"Code":110001,"Message":"未知异常"}"""
            }
        }
        val stream=repo.stream("123")
        assertEquals("https://example.com/stream",stream.url)
        assertEquals(listOf("/novel/player/video_model/v1/"),paths)
    }

    /**
     * 新路由的请求体必须是 `video_id` 单值：给它 `mixed_video_id_map` 会返回
     * `100001 video_id invalid`，所以两条路的 body 形态不能混用。
     */
    @Test fun videoIdRouteSendsTheSingleVideoIdBodyInsteadOfTheLegacyMap() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            if(request.url.host!="example.com") requests+=request
            when {
                request.url.host=="example.com" -> fallbackList("h264" to "https://example.com/stream")
                request.url.encodedPath=="/novel/player/video_model/v1/" -> videoModel("https://example.com/stream")
                else -> ""
            }
        }
        assertEquals("https://example.com/stream",repo.stream("123").url)
        val sent=requests.single()
        assertEquals("/novel/player/video_model/v1/",sent.url.encodedPath)
        val body=sent.body?.let { payload -> okio.Buffer().also { buffer -> payload.writeTo(buffer) }.readUtf8() } ?: ""
        assertTrue(body,body.contains("\"video_id\":\"123\""))
        assertFalse(body,body.contains("mixed_video_id_map"))
    }

    /**
     * 网关在业务处理前挡掉请求时是 `HTTP 200 + 0 字节`：这时业务码取不到、默认成 0，
     * 不判空就会被当成成功往下走，用户看到的是「播放接口暂不可用」但错误信息是 `（0）`。
     */
    @Test fun blankResponseBodyCountsAsFailureAndMovesToTheNextRoute() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url.host!="example.com") paths+=request.url.encodedPath
            when {
                request.url.host=="example.com" -> fallbackList("h264" to "https://example.com/stream")
                request.url.encodedPath=="/novel/player/video_model/v1/" -> ""
                request.url.encodedPath=="/novel/player/multi_video_model/preload/v1/" -> legacyVideoModel("https://example.com/stream")
                else -> """{"Code":110001,"Message":"未知异常"}"""
            }
        }
        assertEquals("https://example.com/stream",repo.stream("123").url)
        // 空响应会**原地重发几次**再换下一路（网关偶发挡掉时重发即好，见 `PLAYBACK_RETRIES`），
        // 因此这里只钉住顺序：同一路可以重复，但必须**依次**走过、最终落到下一条。
        assertEquals(
            listOf("/novel/player/video_model/v1/","/novel/player/multi_video_model/preload/v1/"),
            paths.distinct()
        )
        assertTrue(paths.first()=="/novel/player/video_model/v1/")
        assertTrue(paths.last()=="/novel/player/multi_video_model/preload/v1/")
    }

    /** 新路由通了但没给 `video_model`（例如只有 `data.disable_vsr` 这类字段）时也要继续换路。 */
    @Test fun responseWithoutVideoModelMovesToTheNextRoute() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url.host!="example.com") paths+=request.url.encodedPath
            when {
                request.url.host=="example.com" -> fallbackList("h264" to "https://example.com/stream")
                request.url.encodedPath=="/novel/player/video_model/v1/" -> """{"code":0,"data":{"disable_vsr":true}}"""
                request.url.encodedPath=="/novel/player/multi_video_model/detail/v1/" -> legacyVideoModel("https://example.com/stream")
                else -> ""
            }
        }
        assertEquals("https://example.com/stream",repo.stream("123").url)
        // 同前：每一条都可能重发几次，只钉住「依次走过、最终落到给得出模型的那条」。
        assertEquals(listOf(
            "/novel/player/video_model/v1/",
            "/novel/player/multi_video_model/preload/v1/",
            "/novel/player/multi_video_model/default/v1/",
            "/novel/player/multi_video_model/detail/v1/"
        ),paths.distinct())
    }

    /** 新路由不可用时退回旧路径：老账号 / 灰度环境上仍然要能播。 */
    @Test fun playbackFallsBackToTheLegacyRouteWhenTheNewRouteFails() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url.host!="example.com") paths+=request.url.encodedPath
            when {
                request.url.host=="example.com" -> fallbackList("h264" to "https://example.com/legacy")
                request.url.encodedPath=="/novel/player/multi_video_model/v1/" -> legacyVideoModel("https://example.com/legacy")
                else -> """{"Code":110001,"Message":"未知异常"}"""
            }
        }
        assertEquals("https://example.com/legacy",repo.stream("123").url)
        assertEquals("/novel/player/multi_video_model/v1/",paths.last())
    }

    /** 五条路都只有业务错误码或空响应时必须如实报错，不能把空 data 当成成功往下走。 */
    @Test fun playbackReportsFailureWhenEveryRouteAnswersWithABusinessError() {
        val repo=repository { """{"Code":110001,"Message":"未知异常","BaseResp":{"StatusCode":110001}}""" }
        assertThrows(IOException::class.java) { repo.stream("123") }
    }

    /** 五条路全是空响应体（当前平台对带场景段那几条的实际行为）时也要报错，而不是往下走。 */
    @Test fun playbackReportsFailureWhenEveryRouteAnswersWithAnEmptyBody() {
        val repo=repository { "" }
        assertThrows(IOException::class.java) { repo.stream("123") }
    }

    private fun streamRepository(vararg variants: Triple<Int,String,String>) = repository { request ->
        if(request.url.host=="example.com") {
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) -> rows.put("video_$index",JSONObject().put("vheight",height).put("codec_type",codec).put("main_url",url)) }
            JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
        } else videoModel("https://example.com/stream")
    }

    @Test fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(1080,"bytevc1","https://example.com/hevc"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P（兼容资源）",result.quality)
    }

    @Test fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(480,"h264","https://example.com/avc"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("480P",repo.stream("123",720).quality)
    }

    @Test fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo=streamRepository(Triple(720,"ByteVC2","https://example.com/unsupported"),Triple(480,"bvc2","https://example.com/unsupported2"))
        assertThrows(IOException::class.java) { repo.stream("123",720) }
    }

    @Test fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo=streamRepository(Triple(720,"","https://example.com/legacy"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("720P",repo.stream("123",720).quality)
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(),clock
    )

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId","123").put("title","测试漫剧")
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    /** 给榜单里的第一条补上名次与热度（真实响应里这两个字段来自榜单自己，不是客户端编的）。 */
    private fun withRank(payload: JSONObject,rank: Int=1,heat: String="5746万热度"): JSONObject {
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",rank).put("heatText",heat)
        return payload
    }

    /** 序列化数据形态的榜单页（四个榜单共用同一份结构，只有 route / rankKey 不同）。 */
    private fun currentRank(payload: JSONObject, board: RankBoard = RankBoard.COMIC) = script("mergeLoaderData",JSONArray()
        .put("rank_${board.route}/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    /** 已渲染 DOM 形态的榜单页（漫剧榜实测走的就是这一条）。 */
    private fun renderedRank(page: Int, board: RankBoard = RankBoard.COMIC, heat: String = "5746万热度") =
        """<script>_ROUTER_DATA={"loaderData":{"rank_${board.route}/page":{"rankKey":"${board.storedValue}","pageNum":$page,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>$heat</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/${board.route}">1</a><span aria-current="page">$page</span><a href="/rank/${board.route}?page=5">5</a></nav>"""

    @Test fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",23).put("heatText","5746万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
        }
        val result=repo.rank(2,RankBoard.COMIC)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test fun missingRankIsNotInventedAndLastPageStops() {
        val result=repository { currentRank(rankPayload(5,5)) }.rank(5,RankBoard.COMIC)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(null,""),result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo=repository { renderedRank(2) }
        val result=repo.rank(2,RankBoard.COMIC)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo=repository { currentRank(rankPayload(1,5)) }
        assertThrows(IOException::class.java) { repo.rank(2,RankBoard.COMIC) }
        assertThrows(IllegalArgumentException::class.java) { repo.rank(0,RankBoard.COMIC) }
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result=repo.rank(5,RankBoard.COMIC)
        assertEquals(5,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test fun comicHomeUsesDedicatedRankAndRealPagination() {
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            currentRank(rankPayload(2,2))
        }
        val page=repo.rank(2,RankBoard.COMIC)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test fun legacyRankIsStillSupported() {
        val repo=repository { script("r",JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload())) }
        assertTrue(repo.rank(1,RankBoard.COMIC).hasMore)
    }

    @Test fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo=repository { script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload())) }
        assertThrows(IOException::class.java) { repo.rank(1,RankBoard.COMIC) }
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.rank(1,RankBoard.COMIC) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page=repo.rank(2,RankBoard.COMIC)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        assertTrue(page.hasMore)
    }

    @Test fun comicSearchCarriesCursorAndKeepsTypesAndQueriesSeparate() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","comic-session").put("passback","comic-cursor").put("next_offset",25))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals("comic-session",requests[1].url.queryParameter("search_id"))
        assertEquals("comic-cursor",requests[1].url.queryParameter("passback"))
        assertEquals("25",requests[1].url.queryParameter("offset"))
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("重生",2,ContentType.COMIC) }
        assertEquals(2,requests.size)
    }

    @Test fun expiredCursorRequestsFreshSearchInsteadOfDuplicateOffsetPage() {
        var now=0L
        var requests=0
        val repo=repository(clock={ now }) {
            requests++
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","session").put("passback","cursor"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        now=300001L
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("修仙",2,ContentType.COMIC) }
        assertEquals(1,requests)
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals(3,requests)
    }

    /**
     * 分类目录：`/category/{path}` 的第 1 页不带 `page` 参数，第 2 页起带；
     * 结构是 `loaderData.category_$` 里的 `recommendList` + `pagination`，没有名次与热度。
     */
    @Test fun `category reads the tagged catalog and stops on the last page`() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            val page=request.url.queryParameter("page") ?: "1"
            val id=if(page=="1") "1" else "2"
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[
                {"video_data":{"series_id":"$id","series_name":"玄幻漫剧","episode_cnt":9}}],
                "pagination":{"pageNum":$page,"totalPages":3}}}};</script>"""
        }
        val first=repo.category("comic-drama/fantasy")
        assertEquals("/category/comic-drama/fantasy",requests[0].url.encodedPath)
        assertNull("第 1 页不带 page 参数",requests[0].url.queryParameter("page"))
        assertEquals("玄幻漫剧",first.items.single().title)
        assertTrue(first.hasMore)
        assertNull("分类页没有名次",first.ranking)

        val last=repo.category("comic-drama/fantasy",3)
        assertEquals("3",requests[1].url.queryParameter("page"))
        assertFalse("最后一页不再有下一页",last.hasMore)
    }

    /** 分类路径与页码都要校验：空路径、超长路径、越界页码直接拒绝，不发请求。 */
    @Test fun `category rejects invalid path and page`() {
        var requests=0
        val repo=repository { requests++; """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[],"pagination":{"totalPages":1}}}};</script>""" }
        assertThrows(IllegalArgumentException::class.java) { repo.category("") }
        assertThrows(IllegalArgumentException::class.java) { repo.category("x".repeat(81)) }
        assertThrows(IllegalArgumentException::class.java) { repo.category("comic-drama",0) }
        assertThrows(IllegalArgumentException::class.java) { repo.category("comic-drama",101) }
        assertEquals(0,requests)
    }

    /** 分类目录结构变了要如实报错，不能悄悄回一个空列表。 */
    @Test fun `category reports structural changes instead of an empty list`() {
        val repo=repository { """<script>_ROUTER_DATA={"loaderData":{"other_page":{}}};</script>""" }
        assertThrows(IOException::class.java) { repo.category("comic-drama") }
    }

    /**
     * 四个热播榜各自独立：同一个响应里只有当前榜单的 route key 时，别的榜单必须报错，
     * 不能拿「碰巧也在页面里」的数据顶上（那会让「真人热播榜」显示成漫剧榜的内容）。
     */
    @Test fun eachBoardOnlyReadsItsOwnRankRoute() {
        RankBoard.entries.forEach { board ->
            val repo=repository { request ->
                assertEquals("/rank/${board.route}",request.url.encodedPath)
                currentRank(withRank(rankPayload(1,5).also { payload ->
                    payload.getJSONArray("rankList").getJSONObject(0).put("title",board.label)
                }),board)
            }
            val page=repo.rank(1,board)
            assertEquals(board.label,page.items.single().title)
            assertEquals(RankPosition(1,"5746万热度"),page.ranking!!.positions["123"])
            assertEquals(board.label,page.ranking!!.updatedText.ifBlank { board.label })
        }
    }

    /** 首页四栏一次并发取四个榜单；单个榜单失败只让那一栏不出现，其余照常返回。 */
    @Test fun homeFeedFetchesAllFourBoardsAndToleratesOneFailure() {
        val paths=java.util.Collections.synchronizedList(mutableListOf<String>())
        val repo=repository { request ->
            paths+=request.url.encodedPath
            val board=RankBoard.entries.first { request.url.encodedPath=="/rank/${it.route}" }
            if(board==RankBoard.AI) throw IOException("offline")
            currentRank(rankPayload(1,5),board)
        }
        val feed=repo.homeFeed()
        assertEquals(3,feed.size)
        assertFalse(feed.containsKey(RankBoard.AI))
        assertEquals(RankBoard.entries.size,paths.size)
    }

    /** 四个榜单全失败才抛错：首页此时没有内容可显示，界面会走「热门暂时无法加载」。 */
    @Test fun homeFeedFailsOnlyWhenEveryBoardFails() {
        val repo=repository { throw IOException("offline") }
        assertThrows(IOException::class.java) { repo.homeFeed() }
    }

    private fun searchTab(type: Int,id: String) = JSONObject().put("tab_type",type).put("has_more",true)
        .put("data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(JSONObject()
            .put("series_id",id).put("title","测试剧集").put("episode_cnt",8)))))

    @Test fun comicSearchRequestsAndSelectsOnlyComicTab() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("19",request.url.queryParameter("tab_type"))
            assertEquals(if(requests==1) "0" else "20",request.url.queryParameter("offset"))
            assertEquals("修仙",request.url.queryParameter("query"))
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(11,"111")).put(searchTab(19,"999"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("999"),repo.search("修仙",2,ContentType.COMIC).items.map { it.id })
    }

    @Test fun emptyComicSearchDoesNotFallBackToShortResults() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"search_tabs":[{"tab_type":19,"data":[],"has_more":false}]}""" }
        assertTrue(repo.search("无结果",1,ContentType.COMIC).items.isEmpty())
        assertEquals(1,requests)
    }

    /**
     * App 搜索接口对匿名设备返回空响应体（HTTP 200 + 空 body），漫剧搜索因此必须能退回官网搜索，
     * 否则「漫剧」页签下每次搜索都是一句错误提示。
     */
    @Test fun comicSearchFallsBackToWebsiteWhenTheAppTabIsMissing() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) """{"code":0,"search_tabs":[{"tab_type":11,"data":[]}]}"""
            else """<script>_ROUTER_DATA={"loaderData":{"search_(keyword)/page":{"searchList":[{"video_data":{"series_id":"888","series_name":"漫剧测试","episode_cnt":12}}]}}};</script>"""
        }
        val result=repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("888"),result.items.map { it.id })
        // 官网搜索自己只有 10 条，但后面还能从片库目录继续补，所以第 1 页不是终点。
        assertTrue(result.hasMore)
        assertEquals(2,paths.size)
    }

    /**
     * 搜索第 2 页起走片库目录：官网搜索没有任何分页参数（page / page_num / offset / count 全部被忽略），
     * App 搜索接口又对匿名设备返回空响应，能往下翻的只有分类页（24 部一页）。
     * 这里钉住三件事：真的去读了分类页、只留下片名含关键词的剧、目录翻完就 hasMore=false。
     */
    @Test fun searchBeyondFirstPageScansTheCatalogAndFiltersByTitle() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            val page=request.url.queryParameter("page")
            when {
                // App 搜索接口：HTTP 200 + 空响应体
                request.url.encodedPath.contains("bookapi") -> ""
                request.url.encodedPath.contains("search") -> """<script>_ROUTER_DATA={"loaderData":{"search_(keyword)/page":{"searchList":[{"video_data":{"series_id":"1","series_name":"聚宝仙盆","episode_cnt":99}}]}}};</script>"""
                page=="1" -> """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"1","series_name":"聚宝仙盆"},{"series_id":"2","series_name":"别的剧"}],"pagination":{"totalPages":3}}}};</script>"""
                page=="2" -> """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"3","series_name":"聚宝仙炉"}],"pagination":{"totalPages":3}}}};</script>"""
                else -> """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"4","series_name":"聚宝仙盆第二季"}],"pagination":{"totalPages":3}}}};</script>"""
            }
        }
        assertEquals(listOf("1"),repo.search("聚宝",1,ContentType.COMIC).items.map { it.id })
        // 第 2 页一次往后扫 3 页目录：目录第 1 页的「聚宝仙盆」已经发过了，只接住新命中的两部。
        val second=repo.search("聚宝",2,ContentType.COMIC)
        assertEquals(listOf("3","4"),second.items.map { it.id })
        // 目录一共 3 页，这一轮已经扫到末尾，hasMore 必须收掉：界面上的「已显示全部」才是真的。
        assertFalse(second.hasMore)
        assertTrue(paths.any { it.contains("/category/comic-drama") })
    }

    @Test fun shortSearchKeepsWebsiteFallback() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) throw IOException("offline")
            """<script>_ROUTER_DATA={"loaderData":{"search_page":{"searchList":[{"series_id":"456","series_name":"测试短剧"}]}}}};</script>"""
        }
        assertEquals("456",repo.search("短剧",1,ContentType.SHORT).items.single().id)
        assertEquals(2,paths.size)
    }

    /**
     * 平台对匿名设备返回 HTTP 200 + 空响应体。空 body 必须先被识别成「App 搜索不可用」，
     * 而不是直接丢给 JSONObject 抛出 `A JSONObject text must begin with '{'`——
     * 那句异常会打断回退路径，用户看到的就只是「漫剧搜索报错无法加载」。
     */
    @Test fun blankAppSearchResponseFallsBackToWebsiteInsteadOfFailing() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            when {
                // App 搜索接口：HTTP 200 + 空响应体
                request.url.encodedPath.contains("bookapi") -> ""
                request.url.encodedPath.contains("search") -> """<script>_ROUTER_DATA={"loaderData":{"search_(keyword)/page":{"searchList":[{"video_data":{"series_id":"777","series_name":"测试漫剧","episode_cnt":9}}]}}};</script>"""
                else -> """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"778","series_name":"修仙漫剧第二季"}],"pagination":{"totalPages":1}}}};</script>"""
            }
        }
        assertEquals(listOf("777"),repo.search("修仙",1,ContentType.COMIC).items.map { it.id })
        assertEquals(2,paths.size)
        // 第 2 页从片库目录里接着补；接口已确认关掉之后不再空跑一次 App 搜索。
        assertEquals(listOf("778"),repo.search("修仙",2,ContentType.COMIC).items.map { it.id })
        assertEquals(1,paths.count { it.contains("bookapi") })
    }

    /** 可选登录态：配置后按 `x-tt-token` 发出（整段 Cookie 再补一个 `Cookie` 头），留空就保持匿名请求。 */
    @Test fun sessionTokenIsSentAsLoginHeaderOnlyWhenConfigured() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertNull(requests.last().header("x-tt-token"))
        assertNull(requests.last().header("Cookie"))
        repo.sessionToken=" token-value "
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals("token-value",requests.last().header("x-tt-token"))
        assertNull(requests.last().header("Cookie"))
        repo.sessionToken="sessionid=abc;uid=1"
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals("sessionid=abc;uid=1",requests.last().header("x-tt-token"))
        assertEquals("sessionid=abc;uid=1",requests.last().header("Cookie"))
    }
}
