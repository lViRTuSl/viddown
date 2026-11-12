package com.viddown

import org.junit.Assert.*
import org.junit.Test

class BasicTests {
    @Test fun video() {
        val p = parseYoutubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(UrlKind.VIDEO, p.kind); assertEquals("dQw4w9WgXcQ", p.videoId)
    }
    @Test fun short() = assertEquals(UrlKind.VIDEO, parseYoutubeUrl("https://youtu.be/dQw4w9WgXcQ").kind)
    @Test fun playlist() = assertEquals(UrlKind.PLAYLIST, parseYoutubeUrl("https://www.youtube.com/playlist?list=PLabcdefghij").kind)
    @Test fun both() = assertEquals(UrlKind.BOTH, parseYoutubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabcdefghij").kind)
    @Test fun invalid() = assertEquals(UrlKind.INVALID, parseYoutubeUrl("https://example.com/watch?v=dQw4w9WgXcQ").kind)
    @Test fun extract() = assertEquals(2, extractUrls("a https://youtu.be/dQw4w9WgXcQ, b https://youtu.be/abcdefghijk.").size)
    @Test fun sizes() { assertTrue(fmtSize(1536L * 1024).contains("MB")); assertTrue(safeFileName("a/b:c").isNotEmpty()) }
    @Test fun configRoundTrip() {
        val c = Config(maxConcurrent = 5, speedLimitKbps = 2048, mp3Quality = "320k")
        assertEquals(c, ConfigManager.fromJson(ConfigManager.toJson(c)))
        assertEquals(10, Config(maxConcurrent = 99).sanitized().maxConcurrent)
    }
    @Test fun summary() {
        val s = summarize(emptyList()); assertEquals(0, s.active)
    }

    @Test fun otherSites() {
        assertEquals(UrlKind.OTHER, classifyUrl("https://x.com/user/status/123").kind)
        assertEquals(UrlKind.OTHER, classifyUrl("https://www.instagram.com/reel/abc/").kind)
        assertEquals(UrlKind.VIDEO, classifyUrl("https://youtu.be/dQw4w9WgXcQ").kind)
        assertEquals(UrlKind.INVALID, classifyUrl("not a url").kind)
        assertEquals(2, extractUrls("bak https://x.com/a/status/1 ve https://www.tiktok.com/@u/video/2").size)
    }
    @Test fun selectorCompat() {
        val c = YtArgs.formatSelector(MediaFormat.MP4, "1080p", true)
        assertTrue(c.startsWith("bv*[vcodec~='^(avc|h264)'][height<=1080]+ba[acodec~='^(mp4a|aac)']"))
        assertTrue(c.endsWith("/b"))
        assertEquals("bestvideo+bestaudio/best", YtArgs.formatSelector(MediaFormat.MP4, "Best", false))
    }
    @Test fun recodeOptions() {
        val item = DownloadItem(1, "u", "", "t", 0.0, MediaFormat.MP4, "Best", reencode = true)
        val o = YtArgs.downloadOptions(item, "/tmp", 0, true)
        assertTrue(o.any { it.name == "--recode-video" && it.value == "mp4" })
        val o2 = YtArgs.downloadOptions(item.copy(reencode = false), "/tmp", 0, true)
        assertTrue(o2.any { it.name == "--remux-video" })
        assertFalse(o2.any { it.name == "--recode-video" })
    }
    @Test fun metaCompat() {
        val ok = """{"id":"a","title":"t","requested_formats":[{"vcodec":"avc1.64","acodec":"none"},{"vcodec":"none","acodec":"mp4a.40.2"}]}"""
        val bad = """{"id":"a","title":"t","extractor_key":"Twitter","requested_formats":[{"vcodec":"vp09","acodec":"none"},{"vcodec":"none","acodec":"opus"}]}"""
        assertFalse((MetadataParser.parse(ok, "u") as Probe.Video).meta.nonCompat)
        val b = (MetadataParser.parse(bad, "https://x.com/a") as Probe.Video).meta
        assertTrue(b.nonCompat); assertEquals("X", b.site); assertEquals("https://x.com/a", b.url)
    }
    @Test fun readyPersists() {
        val i = DownloadItem(1, "u", "", "t", 0.0, MediaFormat.MP4, "Best", status = Status.READY, site = "X", reencode = true)
        val r = QueueStore.fromJson(QueueStore.toJson(listOf(i, i.copy(id = 2, status = Status.DOWNLOADING))))
        assertEquals(Status.READY, r[0].status); assertTrue(r[0].reencode); assertEquals("X", r[0].site)
        assertEquals(Status.STOPPED, r[1].status)
        assertEquals(1, summarize(r).ready)
    }
}
