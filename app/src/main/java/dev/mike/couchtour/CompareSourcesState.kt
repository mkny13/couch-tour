package dev.mike.couchtour

import androidx.media3.common.MediaItem

data class CompareSourcesState(
    val detail: ShowDetail,
    val activeRecordingId: String,
    val isComparing: Boolean,
    val originalQueue: List<MediaItem>,
    val originalTrackIndex: Int,
    val originalPositionMs: Long,
    val originalPlayWhenReady: Boolean,
    val comparisonItems: Map<String, MediaItem>
)
