package com.music.bitchord.data.stats

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Turns the player's ticking into the numbers [ListeningStats] keeps.
 *
 * ## Why it samples rather than measures
 *
 * The tempting implementation reads the track's position when it ends and calls
 * that the listening. It is wrong in every direction that matters: a track
 * skipped at 0:20 reports twenty seconds it did play, a track seeked back over
 * reports less than it played, a track left paused for an hour reports the same
 * as one played through, and a track the process is killed under reports
 * nothing at all.
 *
 * So this is fed from the service's existing progress sampler, which only ticks
 * while audio is actually coming out, and each tick contributes the *wall-clock*
 * time since the last one. Seeking cannot inflate it because position is never
 * consulted. Pausing cannot inflate it because no tick happens. The gap either
 * side of a pause is capped at [MAX_STEP_MS], which is what stops an hour on the
 * lock screen from arriving as an hour of listening on the first tick after
 * resume.
 *
 * The cost is that the few seconds before the first tick of each track are not
 * counted. That is a systematic undercount of at most one sample interval per
 * track, and it is the right way to be wrong: every alternative overcounts, and
 * a Replay that flatters is worth less than one that is a little shy.
 *
 * ## Minutes and plays are different questions
 *
 * Minutes accumulate continuously. A *play* is counted once, when enough of the
 * track has gone by to call it listened to — the same half-or-four-minutes rule
 * the scrobbler uses, so the two never disagree about what a play is.
 */
object ListeningRecorder {

    private var currentId: String? = null
    private var currentSong: Song? = null
    private var currentLengthMs = 0L
    private var lastSampleAt: Long = 0L
    private var playedThisTrack: Long = 0L
    private var playCounted = false
    private var samplesSinceFlush = 0

    /**
     * One tick of the player's sampler.
     *
     * [durationMs] is the decoder's figure when it has one; the row's stated
     * runtime stands in until it does, and a track with neither simply has to
     * clear the thirty-second floor to count as a play.
     */
    @Synchronized
    fun onSample(song: Song, durationMs: Long) {
        val now = System.currentTimeMillis()
        if (song.videoId != currentId) {
            // How the track that just ended went, for the taste profile.
            recordOutcome()
            // A new track anchors the clock and contributes nothing yet — see
            // the class note on undercounting.
            currentId = song.videoId
            currentSong = song
            lastSampleAt = now
            playedThisTrack = 0L
            playCounted = false
            return
        }
        val step = (now - lastSampleAt).coerceIn(0L, MAX_STEP_MS)
        lastSampleAt = now
        if (step <= 0L) return
        playedThisTrack += step

        val length = durationMs.takeIf { it > 0 } ?: song.durationMillis()
        currentLengthMs = length
        val threshold = if (length > 0) {
            min(length / 2, PLAY_CEILING_MS).coerceAtLeast(PLAY_FLOOR_MS)
        } else {
            PLAY_FLOOR_MS
        }
        val counts = !playCounted && playedThisTrack >= threshold
        if (counts) playCounted = true

        // Incognito: the clock above still runs, so turning it off mid-track
        // carries on from here rather than crediting the incognito stretch.
        if (!com.music.bitchord.data.settings.AppSettings.incognitoMode.value) ListeningStats.record(enriched(song), step, counts)

        if (++samplesSinceFlush >= FLUSH_EVERY) {
            samplesSinceFlush = 0
            ListeningStats.flush()
        }
    }

    /**
     * Playback stopped, paused, or moved on.
     *
     * Forgetting the current track is what makes the *next* tick anchor rather
     * than contribute: without it, a player paused for an afternoon would hand
     * [MAX_STEP_MS] of listening to whatever was on screen when it resumed.
     */
    @Synchronized
    fun onStopped() {
        currentLengthMs = 0L
        // Not an outcome: a pause is not a skip, and the listen was already
        // counted the moment it crossed the threshold.
        currentId = null
        currentSong = null
        playedThisTrack = 0L
        playCounted = false
        samplesSinceFlush = 0
        ListeningStats.flush()
        TasteStore.flush()
    }

    /**
     * Files the track that has just been left behind: heard through if it
     * crossed the play threshold, skipped if it was left within
     * [TasteModel.SKIP_MS], and nothing in between — half a minute of a song
     * you then stopped is not evidence either way.
     *
     * A listen is filed here rather than when the threshold is crossed so each
     * track gets exactly one verdict.
     */
    private fun recordOutcome() {
        val song = currentSong ?: return
        if (com.music.bitchord.data.settings.AppSettings.incognitoMode.value) return
        if (!com.music.bitchord.data.settings.AppSettings.tasteLearning.value) return
        // No lower bound on the time played: the first tick only anchors, so a
        // track left within one sampler interval has played 0 by this count
        // and is the commonest skip there is. A track that short which ran
        // to its end is not one.
        val ranOut = currentLengthMs > 0 && playedThisTrack >= currentLengthMs * 0.9
        when {
            playCounted -> TasteStore.recordListen(song)
            playedThisTrack < TasteModel.SKIP_MS && !ranOut -> TasteStore.recordSkip(song)
        }
    }

    // ── Filling in what the queue didn't carry ──────────────────────────────

    /**
     * What a lookup found out about a track, by video id.
     *
     * ## Why a lookup is needed at all
     *
     * Most tracks reach the player without an album. A row off the home feed,
     * a search hit, an AutoPlay suggestion — none of them state one, because
     * nothing on those surfaces draws one. That is invisible everywhere else in
     * the app and fatal here: an album chart counted off what the queue carries
     * is empty for almost everybody, and the artist rows have no page to open.
     *
     * The player already asks this same question, but only while its screen is
     * up (see MainActivity's `links`), so listening with the phone in a pocket —
     * which is most listening — would be exactly the listening that went
     * uncredited.
     *
     * One request per track, kept for the life of the process, and the answer is
     * cached a second time by the repository itself.
     */
    private val extras = ConcurrentHashMap<String, Song>()

    /** Ids already sent for, so a track on repeat is asked about once. */
    private val asked = ConcurrentHashMap.newKeySet<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [song] with whatever the lookup has found so far.
     *
     * Returns the song unchanged while the request is in flight rather than
     * holding the sample back: a few seconds filed without an album is a few
     * seconds missing from the album chart, and blocking the sampler on a
     * network call would be a few seconds missing from everything.
     */
    private fun enriched(song: Song): Song {
        extras[song.videoId]?.let { extra ->
            return song.copy(
                artistId = song.artistId ?: extra.artistId,
                albumId = song.albumId ?: extra.albumId,
                albumName = song.albumName ?: extra.albumName,
            )
        }
        if (song.albumName != null && song.artistId != null) return song
        // Only tracks YouTube can answer for. A file on the device and a
        // source-module track both carry ids this endpoint has never heard of,
        // and asking would be a failed request per play, forever.
        if (song.localUri != null || song.videoId.length != YOUTUBE_ID_LENGTH) return song
        if (asked.add(song.videoId)) {
            scope.launch {
                YtMusicRepository.trackLinks(song.videoId).getOrNull()?.let {
                    extras[song.videoId] = it
                }
            }
        }
        return song
    }

    /**
     * The most a single tick may contribute.
     *
     * A shade over the sampler's interval, so an ordinary tick that arrived late
     * — a busy main thread, a device coming out of doze — is still counted in
     * full, while the unbounded gap across a pause is not.
     */
    private const val MAX_STEP_MS = 8_000L

    /** Under half a minute is not a listen, however it ended. */
    private const val PLAY_FLOOR_MS = 30_000L

    /** Past four minutes, half a track is more listening than anyone disputes. */
    private const val PLAY_CEILING_MS = 4 * 60 * 1000L

    /** Six ticks — about half a minute of listening between disk writes. */
    private const val FLUSH_EVERY = 6

    /** What a YouTube video id looks like, and nothing else this app plays does. */
    private const val YOUTUBE_ID_LENGTH = 11
}
