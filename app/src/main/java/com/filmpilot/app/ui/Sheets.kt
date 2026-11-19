package com.filmpilot.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.filmpilot.app.film.CameraState
import com.filmpilot.app.film.FilmStock
import java.util.Locale
import kotlin.math.roundToInt

/** Pick a film. Changing film mid-roll means rewinding and loading a fresh roll. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilmPickerSheet(
    films: List<FilmStock>,
    current: FilmStock,
    camera: CameraState,
    onLoadRoll: (FilmStock) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirm by remember { mutableStateOf<FilmStock?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Film", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                "${current.name}: ${camera.shotsTaken} of ${CameraState.ROLL_SIZE} shot",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(films, key = { it.id }) { film ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                when {
                                    film.id == current.id -> onDismiss()
                                    camera.shotsTaken == 0 -> onLoadRoll(film)
                                    else -> confirm = film
                                }
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(film.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (film.id == current.id) Icon(Icons.Filled.Check, contentDescription = "Loaded")
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            OutlinedButton(
                onClick = { if (camera.shotsTaken == 0) onDismiss() else confirm = current },
                modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
            ) { Text("Load a new roll of ${current.name}") }
        }
    }

    confirm?.let { film ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Rewind this roll?") },
            text = {
                Text(
                    "You've shot ${camera.shotsTaken} of ${CameraState.ROLL_SIZE}. Photos already taken stay in " +
                        "your gallery. A new roll of ${film.name} starts at frame 1."
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; onLoadRoll(film) }) { Text("Load new roll") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** Exposure, grain and vignette sliders. Applied to each photo as it is developed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustSheet(
    camera: CameraState,
    onExposure: (Float) -> Unit,
    onGrain: (Float) -> Unit,
    onVignette: (Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Text("Adjust", style = MaterialTheme.typography.titleLarge)
            Text(
                "Applied when each photo is developed (the live preview stays unprocessed for now).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            LabeledSlider("Exposure", formatEv(camera.exposureEv)) {
                // 1/3-stop steps from -2 to +2 (13 positions).
                Slider(
                    value = camera.exposureEv,
                    onValueChange = { onExposure((it * 3).roundToInt() / 3f) },
                    valueRange = -2f..2f,
                    steps = 11,
                )
            }
            LabeledSlider("Grain", percent(camera.grain)) {
                Slider(value = camera.grain, onValueChange = onGrain)
            }
            LabeledSlider("Vignette", percent(camera.vignette)) {
                Slider(value = camera.vignette, onValueChange = onVignette)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onReset) { Text("Reset") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, slider: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        slider()
    }
}

fun formatEv(ev: Float): String {
    val thirds = (ev * 3).roundToInt()
    if (thirds == 0) return "±0 EV"
    val sign = if (thirds > 0) "+" else "−"
    val whole = kotlin.math.abs(thirds) / 3
    val frac = when (kotlin.math.abs(thirds) % 3) { 1 -> "⅓"; 2 -> "⅔"; else -> "" }
    val num = if (whole == 0) frac else "$whole$frac"
    return "$sign$num EV"
}

private fun percent(v: Float) = String.format(Locale.US, "%d%%", (v * 100).roundToInt())
