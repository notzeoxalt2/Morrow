package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_audio
import nuvio.composeapp.generated.resources.compose_player_fetch_subtitles
import nuvio.composeapp.generated.resources.compose_player_no_audio_tracks_available
import nuvio.composeapp.generated.resources.compose_player_none
import nuvio.composeapp.generated.resources.compose_player_style
import nuvio.composeapp.generated.resources.compose_player_subtitles
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun QuickTracksModal(
    visible: Boolean,
    audioTracks: List<AudioTrack>,
    selectedAudioIndex: Int,
    subtitleTracks: List<SubtitleTrack>,
    selectedSubtitleIndex: Int,
    addonSubtitles: List<AddonSubtitle>,
    selectedAddonSubtitleId: String?,
    isLoadingAddonSubtitles: Boolean,
    onAudioSelected: (Int) -> Unit,
    onSubtitleSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (AddonSubtitle) -> Unit,
    onFetchAddonSubtitles: () -> Unit,
    onOpenSubtitleSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        contentPadding = PaddingValues(28.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(680.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF181818))
                .padding(horizontal = 28.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Text(
                    text = stringResource(Res.string.compose_player_audio),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(Res.string.compose_player_subtitles),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(audioTracks, key = { "${it.index}:${it.id}" }) { track ->
                        QuickTrackChoice(
                            label = audioTrackDisplayName(track),
                            selected = track.index == selectedAudioIndex,
                            onClick = { onAudioSelected(track.index) },
                        )
                    }
                    if (audioTracks.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(Res.string.compose_player_no_audio_tracks_available),
                                color = Color.White.copy(alpha = 0.66f),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item {
                        QuickTrackChoice(
                            label = stringResource(Res.string.compose_player_none),
                            selected = selectedSubtitleIndex < 0 && selectedAddonSubtitleId == null,
                            onClick = { onSubtitleSelected(-1) },
                        )
                    }
                    items(subtitleTracks, key = { "builtin:${it.index}:${it.id}" }) { track ->
                        QuickTrackChoice(
                            label = localizedTrackDisplayName(track.label, track.language, track.index),
                            selected = selectedAddonSubtitleId == null && track.index == selectedSubtitleIndex,
                            onClick = { onSubtitleSelected(track.index) },
                        )
                    }
                    items(addonSubtitles.withQuickTrackKeys(), key = { it.first }) { (_, subtitle) ->
                        QuickTrackChoice(
                            label = subtitle.display.ifBlank { languageLabelForCode(subtitle.language) },
                            selected = subtitle.matchesSelection(selectedAddonSubtitleId),
                            onClick = { onAddonSubtitleSelected(subtitle) },
                        )
                    }
                    if (isLoadingAddonSubtitles) {
                        item {
                            Text(
                                text = stringResource(Res.string.compose_player_fetch_subtitles) + "…",
                                color = Color.White.copy(alpha = 0.66f),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    } else if (addonSubtitles.isEmpty()) {
                        item {
                            TextButton(onClick = onFetchAddonSubtitles) {
                                Text(stringResource(Res.string.compose_player_fetch_subtitles))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            TextButton(
                onClick = onOpenSubtitleSettings,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(Res.string.compose_player_style))
            }
        }
    }
}

@Composable
private fun QuickTrackChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.CenterStart) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = tokens.colors.onAccent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text = label,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.78f),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun List<AddonSubtitle>.withQuickTrackKeys(): List<Pair<String, AddonSubtitle>> =
    mapIndexed { index, subtitle -> "${subtitle.selectionKey}:$index" to subtitle }
