package com.nuvio.tv.core.torrent

import androidx.compose.runtime.Immutable

@Immutable
sealed class TorrentState {
    data object Idle : TorrentState()
    data object Connecting : TorrentState()

    data class Buffering(
        val progress: Float,
        val downloadSpeed: Long,
        val peers: Int,
        val seeds: Int,
        val bufferedBytes: Long = 0L,
        val totalBufferBytes: Long = 0L
    ) : TorrentState()

    data class Streaming(
        val localUrl: String,
        val downloadSpeed: Long,
        val uploadSpeed: Long,
        val peers: Int,
        val seeds: Int,
        val bufferProgress: Float,
        val totalProgress: Float,
        val bufferedAheadBytes: Long = 0L
    ) : TorrentState()

    data class Error(val message: String) : TorrentState()
}
