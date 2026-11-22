package com.namblue.live

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Reads a profile's post list from the data TikTok's own page fetches (api/post/item_list),
 * instead of from the rendered grid. The grid only loads cover images for tiles scrolled into
 * view — never the case in the hidden reader — while the data has every cover, the view count,
 * whether a post is a photo album, and whether more pages exist.
 *
 * [CAPTURE_JS] runs before the page's own scripts (document-start) and keeps a slim copy of each
 * item_list response; [READ_JS] hands the collection back; [parse] turns it into posts.
 */
object ProfileFeed {

    data class Feed(val posts: List<ProfilePost>, val hasMore: Boolean)

    /** Installed at document start on tiktok.com; records item_list responses into window.__nbFeed. */
    const val CAPTURE_JS = """
        (function () {
          if (window.__nbFeed) return;
          var feed = window.__nbFeed = { pages: [] };
          var LIST = /\/api\/post\/item_list/;
          function first(list) { return list && list.length ? list[0] : ''; }
          function slim(it) {
            it = it || {};
            var v = it.video || {}, ip = it.imagePost;
            var images = ip && ip.images ? ip.images.map(function (im) {
              return { imageURL: { urlList: [first(im && im.imageURL && im.imageURL.urlList)] } };
            }) : [];
            return {
              id: String(it.id || ''), desc: it.desc || '',
              author: { uniqueId: (it.author && it.author.uniqueId) || '' },
              video: { cover: v.cover || '', originCover: v.originCover || '' },
              stats: { playCount: it.stats ? it.stats.playCount : null },
              statsV2: { playCount: it.statsV2 ? it.statsV2.playCount : null },
              music: { playUrl: (it.music && it.music.playUrl) || '' },
              imagePost: ip ? { cover: { imageURL: { urlList: [first(ip.cover && ip.cover.imageURL && ip.cover.imageURL.urlList)
                || first(ip.images && ip.images[0] && ip.images[0].imageURL && ip.images[0].imageURL.urlList)] } },
                images: images } : null
            };
          }
          function record(json) {
            if (!json || !(json.itemList || json.hasMore === false)) return;
            feed.pages.push({ itemList: (json.itemList || []).map(slim), hasMore: !!json.hasMore });
          }
          var origFetch = window.fetch;
          if (origFetch) {
            window.fetch = function (input) {
              var promise = origFetch.apply(this, arguments);
              try {
                var url = typeof input === 'string' ? input : (input && input.url) || '';
                if (LIST.test(url)) promise.then(function (r) { return r.clone().json(); }).then(record, function () {});
              } catch (e) {}
              return promise;
            };
          }
          var origOpen = XMLHttpRequest.prototype.open, origSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function (method, url) {
            this.__nbList = LIST.test(String(url || ''));
            return origOpen.apply(this, arguments);
          };
          XMLHttpRequest.prototype.send = function () {
            if (this.__nbList) {
              this.addEventListener('load', function () {
                try { record(JSON.parse(this.responseText)); } catch (e) {}
              });
            }
            return origSend.apply(this, arguments);
          };
        })();
    """

    const val READ_JS = "JSON.stringify(window.__nbFeed || { pages: [] })"

    /** Posts from every captured page, without duplicates. Pure; unit-tested. */
    fun parse(raw: String?): Feed {
        val pages = root(raw)?.optJSONArray("pages") ?: return Feed(emptyList(), hasMore = true)
        val seen = mutableSetOf<String>()
        val posts = (0 until pages.length()).flatMap { i ->
            val items = pages.optJSONObject(i)?.optJSONArray("itemList") ?: JSONArray()
            (0 until items.length()).mapNotNull { items.optJSONObject(it)?.let(::toPost) }
        }.filter { seen.add(it.id) }
        // Responses can land out of order; only the last page ever says there is nothing more.
        val hasMore = (0 until pages.length()).none { pages.optJSONObject(it)?.optBoolean("hasMore", true) == false }
        return Feed(posts, hasMore)
    }

    private fun toPost(item: JSONObject): ProfilePost? {
        val id = item.optString("id", "").takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val album = item.optJSONObject("imagePost")
        val video = item.optJSONObject("video")
        val thumb = if (album != null) {
            album.optJSONObject("cover")?.optJSONObject("imageURL")?.optJSONArray("urlList")?.optString(0, "")
        } else {
            video?.optString("cover", "")?.takeIf { it.startsWith("https://") } ?: video?.optString("originCover", "")
        }
        return ProfilePost(
            id = id,
            username = item.optJSONObject("author")?.optString("uniqueId", "").orEmpty(),
            kind = if (album != null) PostKind.PHOTO else PostKind.VIDEO,
            thumbUrl = thumb?.takeIf { it.startsWith("https://") },
            caption = item.optString("desc", "").trim().ifEmpty { null },
            views = playCount(item)?.let(LiveProfiles::formatViewers),
            images = album?.let(::albumImages).orEmpty(),
            musicUrl = item.optJSONObject("music")?.optString("playUrl", "")?.takeIf { it.startsWith("https://") },
        )
    }

    private fun albumImages(album: JSONObject): List<String> {
        val images = album.optJSONArray("images") ?: return emptyList()
        return (0 until images.length()).mapNotNull { i ->
            images.optJSONObject(i)?.optJSONObject("imageURL")?.optJSONArray("urlList")?.optString(0, "")
                ?.takeIf { it.startsWith("https://") }
        }
    }

    /** stats.playCount is a number; the newer statsV2.playCount is the same count as a string. */
    private fun playCount(item: JSONObject): Long? {
        val stats = item.optJSONObject("stats")?.opt("playCount")
        if (stats is Number) return stats.toLong()
        return (item.optJSONObject("statsV2")?.opt("playCount") as? String)?.toLongOrNull()
    }

    private fun root(raw: String?): JSONObject? {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) return null
        val value = runCatching { JSONTokener(text).nextValue() }.getOrNull()
        return when {
            value is JSONObject -> value
            // evaluateJavascript wraps the result in a JSON string literal; unwrap exactly once.
            value is String && text.startsWith('"') -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        }
    }
}
