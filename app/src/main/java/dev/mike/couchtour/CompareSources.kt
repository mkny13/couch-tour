package dev.mike.couchtour

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.delay

const val SNIPPET_MS = 15_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareSourcesSheet(
    detail: ShowDetail,
    backendId: String,
    artistId: String,
    date: String,
    vm: PlayerViewModel,
    nav: NavHostController,
    onDismiss: () -> Unit
) {
    val compareState by vm.compareState.collectAsState()

    LaunchedEffect(Unit) {
        vm.enterCompareSourcesMode(detail)
    }

    // Snippet looping
    val playerState by vm.state.collectAsState()
    LaunchedEffect(playerState.isPlaying, compareState?.activeRecordingId) {
        while (playerState.isPlaying && compareState != null) {
            delay(500)
            val st = compareState ?: break
            val snippetStartMs = st.originalPositionMs
            val elapsed = playerState.positionMs - snippetStartMs
            if (elapsed >= SNIPPET_MS || playerState.positionMs < snippetStartMs) {
                vm.seekTo(snippetStartMs)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            vm.exitCompareSourcesMode()
            onDismiss()
        }
    ) {
        val st = compareState
        if (st == null || st.comparisonItems.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@ModalBottomSheet
        }

        val sources = listOfNotNull(detail.recording) + detail.alternates

        LazyColumn {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Comparing sources",
                        modifier = Modifier.weight(1f),
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                    )
                    Button(
                        onClick = {
                            val activeId = st.activeRecordingId
                            vm.exitCompareSourcesMode()
                            onDismiss()
                            
                            val playing = vm.state.value
                            val resume = "&resumeIndex=${st.originalTrackIndex}&resumeMs=${st.originalPositionMs}"
                            nav.navigate("recording/$backendId/$artistId/$date?src=$activeId$resume")
                        }
                    ) {
                        Text("Use this source")
                    }
                }
            }
            items(sources, key = { it.id }) { source ->
                val isActive = source.id == st.activeRecordingId
                val isReady = st.comparisonItems.containsKey(source.id)
                
                Box {
                    SourceRow(source, isActive) {
                        if (isReady) {
                            vm.switchComparisonSource(source.id)
                        }
                    }
                    if (!isReady) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp)
                        )
                    }
                }
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }
}
