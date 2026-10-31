package com.screenshare.send

import java.io.ByteArrayOutputStream

/**
 * Minimal MPEG-TS muxer for one H.264 stream (no audio), modeled on the reference
 * implementation found in the vendor sender app: 188-byte packets, PAT+PMT pair,
 * PES with PTS, adaptation-field stuffing, CRC32/MPEG-2 for PSI.
 *
 * PAT  : PMT program map on PID 0x1000
 * PMT  : one elementary stream, H.264 (0x1b) on PID 0x100 (256)
 * PES  : video PID 0x100 with PTS, AUD + SPS/PPS prepended on keyframes
 */
class TsMuxer {
    private var patContinuity = 0
    private var pmtContinuity = 0
    private var videoContinuity = 0
    private var audioContinuity = 0
    private var spsPps: ByteArray? = null
    private var hasAudio = true
    private val psiLock = Any()

    /** Drop the AAC elementary stream when capture failed, so players do not wait on it. */
    fun setHasAudio(enabled: Boolean) { synchronized(psiLock) { hasAudio = enabled } }

    companion object {
        const val PAT_PID = 0
        const val PMT_PID = 4096          // 0x1000
        const val VIDEO_PID = 256         // 0x100
        const val TS_PACKET = 188
        const val STREAM_H264 = 27        // 0x1b
        const val STREAM_TYPE_H264 = 0x1b
        private val CRC_TABLE = IntArray(256)

        init {
            for (i in 0 until 256) {
                var c = i shl 24
                for (j in 0 until 8) {
                    val msb = c and Int.MIN_VALUE
                    c = c shl 1
                    if (msb != 0) c = c xor 0x04C11DB7
                }
                CRC_TABLE[i] = c
            }
        }

        private fun crc32Mpeg(buf: ByteArray, from: Int, len: Int): Int {
            var crc = -1
            for (i in from until from + len) {
                crc = CRC_TABLE[((crc ushr 24) xor (buf[i].toInt() and 0xFF)) and 0xFF] xor (crc shl 8)
            }
            return crc
        }
    }

    /** Remember SPS/PPS so every keyframe can carry them (join mid-stream support). */
    fun setSpsPps(sps: ByteArray, pps: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 1)); out.write(sps)
        out.write(byteArrayOf(0, 0, 0, 1)); out.write(pps)
        spsPps = out.toByteArray()
    }

    /** PAT, one 188-byte packet. Program 1 maps to PMT PID 0x1000. */
    fun buildPat(): ByteArray = synchronized(psiLock) {
        val p = ByteArray(TS_PACKET)
        p[0] = 0x47
        p[1] = 0x40          // PUSI set, PAT_PID=0
        p[2] = 0x00
        p[3] = ((patContinuity and 0xF) or 0x10).toByte()
        patContinuity = (patContinuity + 1) and 0xF
        p[4] = 0x00                                   // pointer_field
        p[5] = 0x00                                   // table_id
        p[6] = 0xB0.toByte()
        p[7] = 0x0D                                   // section_length = 13
        p[8] = 0x00; p[9] = 0x01                      // transport_stream_id
        p[10] = 0xC1.toByte()                         // version 0, current_next = 1
        p[11] = 0x00; p[12] = 0x00                    // section_number, last_section_number
        p[13] = 0x00; p[14] = 0x01                    // program_number 1
        p[15] = 0xF0.toByte(); p[16] = 0x00           // reserved + PMT PID 0x1000
        val crc = crc32Mpeg(p, 5, 12)
        p[17] = (crc ushr 24).toByte(); p[18] = (crc ushr 16).toByte()
        p[19] = (crc ushr 8).toByte(); p[20] = crc.toByte()
        for (i in 21 until TS_PACKET) p[i] = 0xFF.toByte()
        return p
    }

    fun buildPmt(): ByteArray = synchronized(psiLock) {
        val p = ByteArray(TS_PACKET)
        p[0] = 0x47
        p[1] = ((PMT_PID ushr 8) and 0x1F or 0x40).toByte()
        p[2] = (PMT_PID and 0xFF).toByte()
        p[3] = ((pmtContinuity and 0xF) or 0x10).toByte()
        pmtContinuity = (pmtContinuity + 1) and 0xF
        p[4] = 0x00
        // section_length = 0x012 (no PCR field variant used here) -> body from p[8]
        p[5] = 0x02
        val secLen: Int
        if (hasAudio) {
            secLen = 23
        } else {
            secLen = 18
        }
        p[6] = (((secLen ushr 8) and 0xF) or 0xB0).toByte()
        p[7] = (secLen and 0xFF).toByte()
        p[8] = 0x00; p[9] = 0x01
        p[10] = 0xC1.toByte()
        p[11] = 0x00; p[12] = 0x00
        p[13] = 0xE1.toByte(); p[14] = 0x00           // PCR PID = video
        p[15] = 0xF0.toByte(); p[16] = 0x00           // program_info_length 0
        p[17] = STREAM_TYPE_H264.toByte()
        p[18] = 0xE1.toByte(); p[19] = 0x00           // video ES PID 0x100
        p[20] = 0xF0.toByte(); p[21] = 0x00
        var end = 22
        if (hasAudio) {
            // AAC ADTS on PID 0x101 (257), stream_type 0x0F
            p[22] = 0x0F
            p[23] = 0xE1.toByte(); p[24] = 0x01
            p[25] = 0xF0.toByte(); p[26] = 0x00
            end = 27
        }
        val crc = crc32Mpeg(p, 5, end - 5)
        p[end] = (crc ushr 24).toByte()
        p[end + 1] = (crc ushr 16).toByte()
        p[end + 2] = (crc ushr 8).toByte()
        p[end + 3] = crc.toByte()
        for (i in end + 4 until TS_PACKET) p[i] = 0xFF.toByte()
        return p
    }

    /** Fresh PAT+PMT. One lock so the two continuity counters stay a pair. */
    fun buildPatPmt(): ByteArray = synchronized(psiLock) { buildPat() + buildPmt() }

    /**
     * Wrap one H.264 access unit into PES+TS packets.
     * @param keyframe prepend AUD + SPS/PPS and set random-access flag
     * @param ptsUs presentation timestamp in microseconds
     */
    fun packetizeVideo(nal: ByteArray, keyframe: Boolean, ptsUs: Long): ByteArray {
        val pts = 90000L * ptsUs / 1_000_000L
        val accessUnit = ByteArrayOutputStream(nal.size + 64)
        if (keyframe) {
            accessUnit.write(byteArrayOf(0, 0, 0, 1, 9, 0xF0.toByte()))
            spsPps?.let { accessUnit.write(it) }
            if (!hasStartCode(nal)) accessUnit.write(byteArrayOf(0, 0, 0, 1))
        } else if (!hasStartCode(nal)) {
            accessUnit.write(byteArrayOf(0, 0, 0, 1))
        }
        accessUnit.write(nal)
        val payload = accessUnit.toByteArray()

        val pes = ByteArrayOutputStream(payload.size + 32)
        pes.write(0); pes.write(0); pes.write(1)
        pes.write(0xE0)
        // PES_packet_length covers the optional header (8 bytes) plus the access unit.
        val packetLen = payload.size + 8
        if (packetLen <= 0xFFFF) {
            pes.write((packetLen ushr 8) and 0xFF)
            pes.write(packetLen and 0xFF)
        } else {
            pes.write(0); pes.write(0)
        }
        pes.write(0x80 or (if (keyframe) 0x04 else 0x00))   // '10' + data_alignment on keyframes
        pes.write(0x80)                                     // PTS only
        pes.write(0x05)
        writePts(pes, pts)
        pes.write(payload)

        val data = pes.toByteArray()
        val out = ByteArrayOutputStream(data.size + TS_PACKET)
        var offset = 0
        var first = true
        while (offset < data.size) {
            val pkt = ByteArray(TS_PACKET)
            pkt[0] = 0x47
            pkt[1] = if (first) (((VIDEO_PID ushr 8) and 0x1F) or 0x40).toByte()
                     else ((VIDEO_PID ushr 8) and 0x1F).toByte()
            pkt[2] = (VIDEO_PID and 0xFF).toByte()
            pkt[3] = ((videoContinuity and 0xF) or 0x10).toByte()
            videoContinuity = (videoContinuity + 1) and 0xF

            var idx = 4
            val remaining = data.size - offset
            val needStuffing = remaining < 184
            if (first && keyframe) {
                // adaptation with PCR + random-access flag
                pkt[3] = ((videoContinuity - 1 and 0xF) or 0x30).toByte()
                pkt[4] = 0x07
                pkt[5] = 0x50.toByte()
                // PCR base is the 90 kHz PTS. Multiplying by 300 stored the 27 MHz
                // clock in the 33-bit base and made the timeline ~300x too fast.
                val pcrBase = pts
                pkt[6] = (pcrBase ushr 25).toByte()
                pkt[7] = (pcrBase ushr 17).toByte()
                pkt[8] = (pcrBase ushr 9).toByte()
                pkt[9] = (pcrBase ushr 1).toByte()
                pkt[10] = (((pcrBase and 1).toInt() shl 7) or 0x7E).toByte()
                pkt[11] = 0
                idx = 12
            } else if (needStuffing) {
                var stuff = 184 - remaining
                if (stuff == 0) {
                    // nothing
                } else if (stuff == 1) {
                    pkt[3] = (((videoContinuity - 1) and 0xF) or 0x30).toByte()
                    pkt[4] = 0x00
                    idx = 5
                } else {
                    pkt[3] = (((videoContinuity - 1) and 0xF) or 0x30).toByte()
                    pkt[4] = (stuff - 1).toByte()
                    pkt[5] = 0x00
                    for (i in 6 until 5 + stuff) pkt[i] = 0xFF.toByte()
                    idx = 5 + stuff - 1
                }
            }
            val capacity = TS_PACKET - idx
            val n = minOf(capacity, remaining)
            if (n > 0) System.arraycopy(data, offset, pkt, idx, n)
            offset += n
            out.write(pkt)
            first = false
        }
        return out.toByteArray()
    }

    /**
     * Wrap one AAC frame (already in ADTS format) into PES+TS on PID 0x101.
     * @param adtsFrame complete ADTS frame including 7-byte header
     * @param ptsUs presentation timestamp in microseconds
     */
    fun packetizeAudio(adtsFrame: ByteArray, ptsUs: Long): ByteArray {
        val pes = ByteArrayOutputStream(adtsFrame.size + 40)
        val pts = 90000L * ptsUs / 1_000_000L
        pes.write(0); pes.write(0); pes.write(1)
        pes.write(0xC0)                                   // audio stream id
        val packetLen = adtsFrame.size + 8
        if (packetLen <= 0xFFFF) {
            pes.write((packetLen ushr 8) and 0xFF)
            pes.write(packetLen and 0xFF)
        } else {
            pes.write(0); pes.write(0)
        }
        pes.write(0x80)                                   // '10', no scrambling
        pes.write(0x80)                                   // PTS only
        pes.write(0x05)
        writePts(pes, pts)
        pes.write(adtsFrame)

        val data = pes.toByteArray()
        val out = ByteArrayOutputStream(data.size + TS_PACKET)
        var offset = 0
        var first = true
        while (offset < data.size) {
            val pkt = ByteArray(TS_PACKET)
            pkt[0] = 0x47
            pkt[1] = if (first) (((0x101 ushr 8) and 0x1F) or 0x40).toByte()
                     else ((0x101 ushr 8) and 0x1F).toByte()
            pkt[2] = (0x101 and 0xFF).toByte()
            pkt[3] = ((audioContinuity and 0xF) or 0x10).toByte()
            audioContinuity = (audioContinuity + 1) and 0xF
            var idx = 4
            val remaining = data.size - offset
            if (remaining < 184) {
                val stuff = 184 - remaining
                if (stuff == 1) {
                    pkt[3] = (((audioContinuity - 1) and 0xF) or 0x30).toByte()
                    pkt[4] = 0x00
                    idx = 5
                } else if (stuff > 1) {
                    pkt[3] = (((audioContinuity - 1) and 0xF) or 0x30).toByte()
                    pkt[4] = (stuff - 1).toByte()
                    pkt[5] = 0x00
                    for (i in 6 until 5 + stuff) pkt[i] = 0xFF.toByte()
                    idx = 5 + stuff - 1
                }
            }
            val capacity = TS_PACKET - idx
            val n = minOf(capacity, remaining)
            if (n > 0) System.arraycopy(data, offset, pkt, idx, n)
            offset += n
            out.write(pkt)
            first = false
        }
        return out.toByteArray()
    }

    private fun hasStartCode(nal: ByteArray): Boolean {
        if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) return true
        return nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()
    }

    /** 5-byte PTS with the ISO/IEC 13818-1 marker bits ('0010' for PTS-only). */
    private fun writePts(pes: ByteArrayOutputStream, pts: Long) {
        pes.write((0x21 or ((pts ushr 29).toInt() and 0x0E)))
        pes.write(((pts ushr 22).toInt() and 0xFF))
        pes.write((0x01 or ((pts ushr 14).toInt() and 0xFE)))
        pes.write(((pts ushr 7).toInt() and 0xFF))
        pes.write((0x01 or ((pts shl 1).toInt() and 0xFE)))
    }
}

