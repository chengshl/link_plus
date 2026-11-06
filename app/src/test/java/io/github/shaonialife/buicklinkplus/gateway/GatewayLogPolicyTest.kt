package io.github.shaonialife.buicklinkplus.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayLogPolicyTest {
    @Test fun stripsTrackTitlesButKeepsPlaybackState() {
        val line = "APP MEDIA: 问爱 - 歌手示例 playing=true pkg=com.tencent.qqmusic（会话回读）"
        val prepared = GatewayLogPolicy.prepare(line)!!
        assertEquals("APP MEDIA: playing=true (track metadata omitted)", prepared)
        assertFalse(prepared.contains("问爱"))
        assertFalse(prepared.contains("歌手"))
    }

    @Test fun hidesPacketHeaderButRetainsHandshakeMetadata() {
        val input = "APP AIRPLAY: /pair-setup req=6B -> [RTSP/1.0 200 OK] resp=409B head=[06 01 02 02 10 2f c5 5d 44 e9]"
        val output = GatewayLogPolicy.prepare(input)!!
        assertTrue(output.contains("/pair-setup req=6B"))
        assertTrue(output.contains("200 OK"))
        assertTrue(output.contains("[packet preview omitted]"))
        assertFalse(output.contains("06 01 02"))
    }

    @Test fun hidesRawAudioPreviewAndPublicKeyPrefix() {
        val audio = GatewayLogPolicy.prepare("APP AUDIO: 首帧 512B head=21 11 45 FF F1")!!
        assertFalse(audio.contains("21 11 45"))
        val pairing = GatewayLogPolicy.prepare("APP pair-setup: verified pub=88e586cefd08e48e")!!
        assertFalse(pairing.contains("88e586ce"))
    }

    @Test fun lyricsExposeOnlyNetworkStatusAndCanBeDropped() {
        assertEquals("APP LYRICS: request HTTP=404",
            GatewayLogPolicy.prepare("APP LYRICS: 歌名=私人歌曲 阶段=精确查询 HTTP=404 耗时=2244ms"))
        assertEquals(null, GatewayLogPolicy.prepare("APP LYRICS: 歌名=私人歌曲 歌词=秘密"))
    }

    @Test fun structuredProtocolEventsArePreserved() {
        val line = "TRACE +166099164ms #2532 [EVENT-RX-ID] local=42 remoteCseq=absent bytes=91"
        assertEquals(line, GatewayLogPolicy.prepare(line))
    }
}
