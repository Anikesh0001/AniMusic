package com.music.bitchord.data.importer

/** Reads one service's public playlists, albums or songs. */
interface PlaylistImporter {
    val service: ImportService

    /** Whether [url] is this importer's. Pure string work, no network. */
    fun canHandle(url: String): Boolean

    /**
     * The collection at [url]. Throws [ImportException] for anything the
     * listener should be told about.
     */
    suspend fun fetch(url: String): ImportedCollection
}
