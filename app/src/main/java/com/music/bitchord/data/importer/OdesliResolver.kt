package com.music.bitchord.data.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Single songs from any service, through song.link (Odesli).
 *
 * Odesli's JSON API now answers `PUBLIC_API_ACCESS_DEPRECATED` without a
 * key, but its public page for a link — `song.link/<any music link>` —
 * still carries what the API did in its `__NEXT_DATA__`: the entity's title,
 * artist and runtime, its links on the platforms Odesli resolved, and a
 * YouTube id when it has one.
 *
 * Used for one link at a time, never per playlist row: Odesli rate-limits
 * hard, and a 429 surfaces as [ImportException.Reason.RATE_LIMITED].
 */
object OdesliResolver : PlaylistImporter {

    override val service = ImportService.ODESLI

    private const val BASE = "https://song.link/"

    /** What Odesli knows about a link. */
    data class Entity(
        /** `song` or `album`. */
        val type: String,
        val track: ImportTrack,
        val coverUrl: String?,
        /** A YouTube / YouTube Music video id, when Odesli resolved one. */
        val videoId: String?,
        /** The same release's links on other platforms, where Odesli resolved them. */
        val links: List<String>,
    )

    /** song.link / album.link / odesli.co pages themselves. */
    override fun canHandle(url: String): Boolean =
        ImportUrls.hostIs(url, "song.link", "album.link", "odesli.co", "pods.link")

    override suspend fun fetch(url: String): ImportedCollection {
        val entity = parse(ImportHttp.get(url)) ?: throw ImportException(ImportException.Reason.NOT_FOUND)
        return toCollection(entity, url)
    }

    /** Asks song.link about [url], a link on some other service. */
    suspend fun lookup(url: String): Entity? =
        parse(ImportHttp.get(BASE + URLEncoder.encode(url, "UTF-8").replace("+", "%20")))

    /**
     * The collection [entity] stands for. A song is one row; an album is read
     * through whichever of its other links a dedicated importer reads, since
     * the page lists no tracks of its own.
     */
    suspend fun toCollection(entity: Entity, sourceUrl: String): ImportedCollection {
        if (entity.type == "album") {
            val (importer, link) = entity.links.firstNotNullOfOrNull { link ->
                ImporterRegistry.findDedicated(link)?.takeIf { it !== this }?.let { it to link }
            } ?: throw ImportException(ImportException.Reason.UNSUPPORTED)
            // Still labelled, and re-synced, as the link the listener gave.
            return importer.fetch(link).let { read ->
                read.copy(sourceUrl = sourceUrl, service = ImportService.detect(sourceUrl) ?: read.service)
            }
        }
        return ImportedCollection(
            service = ImportService.detect(sourceUrl)?.takeUnless { it == ImportService.ODESLI } ?: service,
            sourceUrl = sourceUrl,
            title = entity.track.title,
            coverUrl = entity.coverUrl,
            tracks = listOf(entity.track),
        )
    }

    internal fun parse(html: String): Entity? {
        val raw = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        val root = runCatching { ImportHttp.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val pageData = ((root["props"] as? JsonObject)?.get("pageProps") as? JsonObject)
            ?.get("pageData") as? JsonObject ?: return null
        val entity = pageData["entityData"] as? JsonObject ?: return null
        val title = entity.text("title") ?: return null
        val sections = (pageData["sections"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val links = sections.flatMap { (it["links"] as? JsonArray).orEmpty() }.mapNotNull { it as? JsonObject }
        val videoId = links
            .filter { it.text("platform") in setOf("youtubeMusic", "youtube") }
            .firstNotNullOfOrNull { link -> link.text("url")?.let(::videoIdOf) }
            ?: sections.firstOrNull { it.text("sectionId")?.endsWith("embed|youtube") == true }
                ?.text("uniqueId")?.substringAfterLast('|')?.takeIf { VIDEO_ID.matches(it) }
        return Entity(
            type = entity.text("type") ?: "song",
            track = ImportTrack(
                title = title,
                artist = entity.text("artistName").orEmpty(),
                durationMs = entity.text("duration")?.toLongOrNull()?.takeIf { it > 0 },
                videoId = videoId,
            ),
            coverUrl = entity.text("thumbnailUrl"),
            videoId = videoId,
            links = links.mapNotNull { it.text("url") },
        )
    }

    private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

    /** `watch?v=`, `youtu.be/`, `music.youtube.com/watch?v=`. */
    internal fun videoIdOf(url: String): String? {
        val parsed = ImportUrls.parse(url) ?: return null
        val id = parsed.queryParameter("v")
            ?: parsed.pathSegments.lastOrNull()?.takeIf { ImportUrls.host(url) == "youtu.be" }
        return id?.takeIf { VIDEO_ID.matches(it) }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
}
