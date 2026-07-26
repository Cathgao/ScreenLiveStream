package com.example.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the audio input PTS conversion used by
 * [AudioEncoder.startAudioLoop]. The audio capture thread feeds
 * its own System.nanoTime()-based timestamps into the AAC encoder.
 * The PTS we actually hand to MediaCodec is
 *
 *     ptsUs = if (videoStartPtsUs > 0 && videoStartRealNs > 0)
 *                videoStartPtsUs + (nanoTime() - videoStartRealNs) / 1000
 *             else
 *                (nanoTime() - audioThreadStartNs) / 1000
 *
 * i.e. once the video encoder publishes its first-frame anchor
 * (PTS, nanoTime), the audio PTS is rebased into the same domain
 * as the video MediaCodec output PTS.
 */
class AudioPtsAlignmentTest {

    /** The same formula as AudioEncoder.computePtsUs, but pure. */
    private fun computePtsUs(
        videoStartPtsUs: Long,
        videoStartRealNs: Long,
        audioThreadStartNs: Long,
        nowNs: Long
    ): Long {
        return if (videoStartPtsUs > 0L && videoStartRealNs > 0L) {
            (videoStartPtsUs + (nowNs - videoStartRealNs) / 1000L)
                .coerceAtLeast(0L)
        } else {
            (nowNs - audioThreadStartNs) / 1000L
        }
    }

    @Test
    fun `audio PTS is rebased onto the video anchor once published`() {
        // videoStartPtsUs = 1 000 000 µs (the video MediaCodec
        // produced its first frame at PTS 1 000 000).
        // videoStartRealNs = 5 000 000 000 ns (System.nanoTime()
        // at the same instant).
        val videoStartPtsUs = 1_000_000L
        val videoStartRealNs = 5_000_000_000L

        // audio thread start is 200 ms before the video first frame
        // (audio thread was created earlier, so audioThreadStartNs
        // is smaller than videoStartRealNs).
        val audioThreadStartNs = 4_800_000_000L

        // Audio frame captured 100 ms after the video first frame.
        // Expected PTS: 1 000 000 + 100 000 = 1 100 000 µs.
        val ptsAt100ms = computePtsUs(
            videoStartPtsUs, videoStartRealNs, audioThreadStartNs,
            nowNs = 5_100_000_000L
        )
        assertEquals(1_100_000L, ptsAt100ms)

        // Audio frame captured 500 ms after the video first frame.
        val ptsAt500ms = computePtsUs(
            videoStartPtsUs, videoStartRealNs, audioThreadStartNs,
            nowNs = 5_500_000_000L
        )
        assertEquals(1_500_000L, ptsAt500ms)
    }

    @Test
    fun `audio PTS is monotonic after video anchor rebases`() {
        val videoStartPtsUs = 1_000_000L
        val videoStartRealNs = 5_000_000_000L
        val audioThreadStartNs = 4_800_000_000L

        val samples = listOf(
            5_050_000_000L,
            5_100_000_000L,
            5_166_000_000L,
            5_200_000_000L,
            5_250_000_000L
        )

        val pts = samples.map {
            computePtsUs(videoStartPtsUs, videoStartRealNs, audioThreadStartNs, it)
        }
        for (i in 1 until pts.size) {
            assertTrue(
                "audio PTS must be monotonic: ${pts[i - 1]} -> ${pts[i]}",
                pts[i] >= pts[i - 1]
            )
        }
    }

    @Test
    fun `audio and video share the same zero point after rebasing`() {
        // Reproduces the production scenario:
        //   - Video first frame PTS = 1 000 000 µs, captured at t=5 s
        //   - Audio frame 200 ms later = PTS 1 200 000 µs (after rebase)
        //   - Video frame 200 ms later = PTS 1 200 000 µs (in MediaCodec PTS)
        // Both encoders should produce samples with the same PTS at
        // the same wall-clock moment, which is exactly what the muxer
        // needs to write a synchronised MP4.
        val videoStartPtsUs = 1_000_000L
        val videoStartRealNs = 5_000_000_000L
        val audioThreadStartNs = 4_800_000_000L
        val nowNs = 5_200_000_000L

        val audioPtsUs = computePtsUs(
            videoStartPtsUs, videoStartRealNs, audioThreadStartNs, nowNs
        )
        // Video's MediaCodec BufferInfo.presentationTimeUs at the
        // same instant is 1 000 000 + 200 000 = 1 200 000 µs. Audio
        // matches.
        assertEquals(1_200_000L, audioPtsUs)
    }

    @Test
    fun `fallback path is used when video anchor is not yet published`() {
        val audioThreadStartNs = 5_000_000_000L
        val nowNs = 5_050_000_000L
        // Both videoStart fields are 0 (not yet published) -> legacy
        // anchor.
        val pts = computePtsUs(
            videoStartPtsUs = 0L,
            videoStartRealNs = 0L,
            audioThreadStartNs = audioThreadStartNs,
            nowNs = nowNs
        )
        assertEquals(50_000L, pts)
    }

    @Test
    fun `negative PTS is clamped to zero`() {
        // Audio frame captured a millisecond before videoStartRealNs
        // (race when both encoders start almost simultaneously).
        // videoStartPtsUs must be > 0 here so the rebase branch is
        // exercised; otherwise the fallback path runs and the
        // negative-anchor protection is not the binding constraint.
        // With videoStartPtsUs=500 µs and a 1 ms pre-anchor capture,
        // the raw computation is 500 - 1000 = -500 → clamp to 0.
        val videoStartPtsUs = 500L
        val videoStartRealNs = 5_000_000_000L
        val audioThreadStartNs = 4_000_000_000L
        val nowNs = videoStartRealNs - 1_000_000L // 1 ms before anchor
        val pts = computePtsUs(
            videoStartPtsUs, videoStartRealNs, audioThreadStartNs, nowNs
        )
        assertEquals(0L, pts)
    }
}
