package `in`.sukarna.app

import android.os.Bundle
import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import kotlinx.coroutines.awaitCancellation
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Cream = Color(0xFFFAF8F2)
private val Green = Color(0xFF315D50)
private val Ink = Color(0xFF253C33)
private val Muted = Color(0xFF737A70)
private val Line = Color(0xFFE5E5DA)
private val Gold = Color(0xFFE6C98D)

class MainActivity : ComponentActivity() {
    private lateinit var player: TonePlayer
    override fun attachBaseContext(newBase: Context) {
        val saved = newBase.getSharedPreferences("preferences", MODE_PRIVATE).getString("language", null)
        val language = saved ?: "mr"
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        player = TonePlayer(this) {
            Toast.makeText(this, R.string.audio_error, Toast.LENGTH_SHORT).show()
        }
        setContent {
            PatuKarna(player) {
                val language = if (resources.configuration.locales[0].language == "mr") "en" else "mr"
                getSharedPreferences("preferences", MODE_PRIVATE).edit().putString("language", language).apply()
                recreate()
            }
        }
    }
    override fun onPause() { player.stop(); super.onPause() }
    override fun onDestroy() { player.close(); super.onDestroy() }
}

@Composable
private fun PatuKarna(player: TonePlayer, onLanguageChange: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFB8D6C4), background = Color(0xFF17251F),
        surface = Color(0xFF21342B), onSurface = Color(0xFFE9EBDF),
        onSurfaceVariant = Color(0xFFB9C0B3), outlineVariant = Color(0xFF3C4C41),
    ) else lightColorScheme(
        primary = Green, background = Cream, surface = Color(0xFFFFFEF9),
        onSurface = Ink, onSurfaceVariant = Muted, outlineVariant = Line,
    )
    MaterialTheme(colorScheme = scheme) {
        var selected by rememberSaveable { mutableIntStateOf(-1) }
        var page by rememberSaveable { mutableStateOf("home") }
        var playing by remember { mutableStateOf<Int?>(null) }
        var playCount by remember { mutableIntStateOf(0) }
        var holding by remember { mutableStateOf(false) }
        LaunchedEffect(playCount, holding) { if (!holding) { delay(3000); playing = null } }
        fun play(offset: Int) {
            holding = false
            player.play(Music.scales[selected].midi + offset)
            playing = offset
            playCount++
        }
        fun back() {
            player.stop(); playing = null; holding = false
            if (selected >= 0) selected = -1
            else if (page == "scale_identification" || page == "note_identification") page = "training"
            else page = "home"
        }
        BackHandler(page != "home") { back() }
        Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (page != "home") {
                        val backLabel = stringResource(when {
                            selected >= 0 -> R.string.back_to_scales
                            page == "scale_identification" || page == "note_identification" -> R.string.back_to_training
                            else -> R.string.back_to_home
                        })
                        IconButton(onClick = { back() }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = backLabel,
                                tint = scheme.onSurface,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    } else {
                        Image(
                            painter = painterResource(R.drawable.patu_karna_logo),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(stringResource(R.string.app_name), fontFamily = FontFamily.Serif, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    val switchLabel = stringResource(R.string.switch_language_description)
                    OutlinedButton(onClick = onLanguageChange, contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.semantics { contentDescription = switchLabel }) {
                        Text(stringResource(R.string.language_switch), fontSize = 13.sp)
                    }
                }
                HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
                if (page == "home") {
                    CenteredActions {
                        Button(onClick = { page = "scales" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.scales), fontSize = 18.sp)
                        }
                        FilledTonalButton(onClick = { page = "training" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.training), fontSize = 18.sp)
                        }
                        OutlinedButton(onClick = { page = "manual" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.manual), fontSize = 18.sp)
                        }
                    }
                } else if (page == "training") {
                    CenteredActions {
                        Button(onClick = { page = "scale_identification" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.scale_identification), fontSize = 18.sp)
                        }
                        FilledTonalButton(onClick = { page = "note_identification" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.note_identification), fontSize = 18.sp)
                        }
                    }
                } else if (page == "scale_identification") {
                    ScaleIdentificationPage(player)
                } else if (page == "note_identification") {
                    NoteIdentificationPage(player)
                } else if (page == "manual") {
                    CenteredActions {
                        Text(stringResource(R.string.manual), fontFamily = FontFamily.Serif, fontSize = 28.sp)
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            SelectionContainer {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                    stringResource(R.string.manual_body).split("\n\n").forEachIndexed { index, section ->
                                        if (index > 0) HorizontalDivider(color = scheme.outlineVariant)
                                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text(section.substringBefore('\n'),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = scheme.primary,
                                                modifier = Modifier.semantics { heading() })
                                            section.substringAfter('\n').split('\n').forEach { paragraph ->
                                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    if (paragraph.startsWith("• ")) {
                                                        Text("•", color = scheme.primary, lineHeight = 26.sp)
                                                    }
                                                    Text(paragraph.removePrefix("• "),
                                                        modifier = Modifier.weight(1f),
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        lineHeight = 26.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (selected < 0) {
                    ScalesPage { selected = it }
                } else {
                    SwarasPage(Music.scales[selected], playing, holding, ::play,
                        onHold = { offset ->
                            holding = true
                            playing = offset
                            player.play(Music.scales[selected].midi + offset, sustain = true)
                        },
                        onRelease = { offset ->
                            if (holding && playing == offset) {
                                player.stop(); holding = false; playing = null
                            }
                        })
                }
            }
        }
    }
}

@Composable
private fun ScaleIdentificationPage(player: TonePlayer) {
    var scaleIndex by rememberSaveable { mutableIntStateOf(kotlin.random.Random.nextInt(Music.scales.size)) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    var playRequest by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    val remaining = remember { Animatable(1f) }
    val scale = Music.scales[scaleIndex]
    LaunchedEffect(scaleIndex, playRequest) {
        if (playRequest > 0) {
            try {
                remaining.snapTo(1f)
                isPlaying = true
                player.play(scale.midi, sustain = true)
                withContext(object : MotionDurationScale { override val scaleFactor = 1f }) {
                    remaining.animateTo(0f, animationSpec = tween(3000, easing = LinearEasing))
                }
            } finally {
                player.stop()
                isPlaying = false
            }
        }
    }
    CenteredActions {
        FilledTonalButton(onClick = {
            player.stop()
            playRequest = 0
            scaleIndex = (scaleIndex + kotlin.random.Random.nextInt(1, Music.scales.size)) % Music.scales.size
            revealed = false
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.refresh), fontSize = 18.sp)
        }
        Button(onClick = { playRequest++ }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.play), fontSize = 18.sp)
        }
        FilledTonalButton(onClick = { revealed = !revealed }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(if (revealed) R.string.hide else R.string.reveal), fontSize = 18.sp)
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 104.dp), contentAlignment = Alignment.Center) {
            if (revealed) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(scaleLabel(scale), fontFamily = FontFamily.Serif, fontSize = 32.sp)
                    Text(stringResource(R.string.tonic_pitch, Music.pitchLabel(scale.midi)), fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (isPlaying) {
        PlaybackCountdown(remaining.value, onDismiss = {
            player.stop()
            playRequest = 0
            isPlaying = false
        })
    }
}

@Composable
private fun PlaybackCountdown(remaining: Float, label: String? = null, onDismiss: () -> Unit) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
                Column(Modifier.width(200.dp).padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (label != null) Text(label, fontSize = 18.sp)
                    Box(Modifier.size(136.dp), contentAlignment = Alignment.Center) {
                    val countdownDescription = stringResource(R.string.listen)
                    CircularProgressIndicator(
                        progress = { remaining },
                        modifier = Modifier.size(112.dp).semantics {
                            contentDescription = countdownDescription
                        },
                        strokeWidth = 8.dp,
                    )
                    Text(
                        text = kotlin.math.ceil(remaining * 3).toInt().coerceIn(1, 3).toString(),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    }
                }
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteIdentificationPage(player: TonePlayer) {
    var selectedScale by rememberSaveable { mutableIntStateOf(-1) }
    var scaleIndex by rememberSaveable { mutableIntStateOf(kotlin.random.Random.nextInt(Music.scales.size)) }
    var noteIndex by rememberSaveable { mutableIntStateOf(kotlin.random.Random.nextInt(12)) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var playRequest by remember { mutableIntStateOf(0) }
    var stage by remember { mutableIntStateOf(0) }
    val remaining = remember { Animatable(1f) }
    val scale = Music.scales[scaleIndex]

    fun refresh(selection: Int = selectedScale) {
        player.stop()
        playRequest = 0
        stage = 0
        selectedScale = selection
        scaleIndex = if (selection >= 0) selection else
            (scaleIndex + kotlin.random.Random.nextInt(1, Music.scales.size)) % Music.scales.size
        noteIndex = (noteIndex + kotlin.random.Random.nextInt(1, 12)) % 12
        revealed = false
    }

    LaunchedEffect(scaleIndex, noteIndex, playRequest) {
        if (playRequest > 0) {
            try {
                withContext(object : MotionDurationScale { override val scaleFactor = 1f }) {
                    for (part in 1..2) {
                        remaining.snapTo(1f)
                        stage = part
                        player.play(scale.midi + if (part == 1) 0 else noteIndex, sustain = true)
                        remaining.animateTo(0f, animationSpec = tween(3000, easing = LinearEasing))
                        player.stop()
                    }
                }
            } finally {
                player.stop()
                stage = 0
            }
        }
    }

    CenteredActions {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = if (selectedScale < 0) stringResource(R.string.random_scale) else scaleLabel(Music.scales[selectedScale]),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(R.string.select_scale)) },
                leadingIcon = if (selectedScale >= 0) {
                    { ScaleIndicator(Music.scales[selectedScale]) }
                } else null,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.random_scale)) },
                    onClick = { expanded = false; refresh(-1) })
                Music.scales.forEachIndexed { index, option ->
                    DropdownMenuItem(text = { Text(scaleLabel(option)) },
                        leadingIcon = { ScaleIndicator(option) },
                        onClick = { expanded = false; refresh(index) })
                }
            }
        }
        FilledTonalButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.refresh_note), fontSize = 18.sp)
        }
        Button(onClick = { playRequest++ }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.play), fontSize = 18.sp)
        }
        FilledTonalButton(onClick = { revealed = !revealed }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(if (revealed) R.string.hide else R.string.reveal), fontSize = 18.sp)
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 104.dp), contentAlignment = Alignment.Center) {
            if (revealed) {
                val swara = Music.swaras[noteIndex]
                val name = stringResource(when (swara.name) {
                    "Sa" -> R.string.swara_sa; "Re" -> R.string.swara_re
                    "Ga" -> R.string.swara_ga; "Ma" -> R.string.swara_ma
                    "Pa" -> R.string.swara_pa; "Dha" -> R.string.swara_dha
                    else -> R.string.swara_ni
                })
                val kind = when (swara.kind) {
                    "Komal" -> stringResource(R.string.kind_komal)
                    "Tivra" -> stringResource(R.string.kind_tivra)
                    "Shuddha" -> stringResource(R.string.kind_shuddha)
                    else -> ""
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(listOf(kind, name).filter { it.isNotEmpty() }.joinToString(" "),
                        fontSize = 32.sp, color = MaterialTheme.colorScheme.primary)
                    Text(scaleLabel(scale), fontSize = 20.sp)
                }
            }
        }
    }
    if (stage != 0) {
        PlaybackCountdown(remaining.value,
            label = stringResource(if (stage == 1) R.string.swara_sa else R.string.practice_swara),
            onDismiss = {
                player.stop()
                playRequest = 0
                stage = 0
            })
    }
}

@Composable
private fun CenteredActions(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            content = content,
        )
    }
}

@Composable
private fun ScalesPage(onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(stringResource(R.string.scales), fontFamily = FontFamily.Serif,
                fontSize = 26.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        itemsIndexed(Music.scales, key = { _, scale -> scale.midi }) { index, scale ->
            val label = scaleLabel(scale)
            val description = stringResource(R.string.open_scale, label, scale.note)
            Surface(onClick = { onSelect(index) }, shape = RoundedCornerShape(14.dp),
                color = colors.surface, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.semantics { contentDescription = description }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(24.dp, 34.dp),
                            contentAlignment = Alignment.Center) {
                            ScaleIndicator(scale)
                        }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.scale_summary, scale.note), color = colors.onSurfaceVariant,
                            fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScaleIndicator(scale: Scale) {
    Box(Modifier.size(20.dp)
        .clip(CircleShape)
        .background(if (scale.white) Color.White else Color.Black)
        .border(1.dp, if (scale.white) Color(0xFFCFC5AF) else Color.Black, CircleShape))
}

@Composable
private fun SwarasPage(scale: Scale, playing: Int?, holding: Boolean, onPlay: (Int) -> Unit, onHold: (Int) -> Unit, onRelease: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(scaleLabel(scale), fontFamily = FontFamily.Serif, fontSize = 32.sp)
                    Text(stringResource(R.string.tonic_pitch, Music.pitchLabel(scale.midi)),
                        color = colors.onSurfaceVariant, fontSize = 14.sp)
                }
                FilledTonalButton(onClick = { onPlay(0) }) {
                    Text(stringResource(if (playing == 0) R.string.replay_sa else R.string.play_sa))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            HarmoniumKeyboard(scale, playing, onPlay, onHold, onRelease)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            // Reserve the space so the keyboard never shifts under a held finger.
            Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                if (holding && playing != null) {
                    val swara = Music.swaras[playing]
                    val kind = stringResource(when (swara.kind) {
                        "Upper" -> R.string.upper_octave
                        "Komal" -> R.string.kind_komal
                        "Tivra" -> R.string.kind_tivra
                        "Achal" -> R.string.kind_achal
                        else -> R.string.kind_shuddha
                    })
                    val name = stringResource(when (swara.name) {
                        "Sa" -> R.string.swara_sa
                        "Re" -> R.string.swara_re
                        "Ga" -> R.string.swara_ga
                        "Ma" -> R.string.swara_ma
                        "Pa" -> R.string.swara_pa
                        "Dha" -> R.string.swara_dha
                        else -> R.string.swara_ni
                    })
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$kind $name", color = colors.primary, fontSize = 26.sp,
                            fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.frequency_hz, Music.frequency(scale.midi + playing)),
                            color = colors.onSurfaceVariant, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HarmoniumKeyboard(scale: Scale, playing: Int?, onPlay: (Int) -> Unit, onHold: (Int) -> Unit, onRelease: (Int) -> Unit) {
    val blackPitches = setOf(1, 3, 6, 8, 10)
    val white = Music.swaras.indices.filter { (scale.midi + it) % 12 !in blackPitches }
    val black = Music.swaras.indices.filter { it !in white }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val blackKeyRatio = 0.90f
        val raisedTop = 24.dp
        val edgeUnits = (if (0 in black) blackKeyRatio / 2 else 0f) +
            (if (Music.swaras.lastIndex in black) blackKeyRatio / 2 else 0f)
        val whiteWidth = (maxWidth - 12.dp) / (white.size + edgeUnits)
        val blackWidth = whiteWidth * blackKeyRatio
        val left = if (0 in black) blackWidth / 2 else 0.dp
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF654A35)).padding(6.dp)) {
            Box(Modifier.fillMaxWidth().height(250.dp + raisedTop)) {
                (white + black).forEach { index ->
                    val isBlack = index in black
                    val x = if (isBlack) left + whiteWidth * white.count { it < index } - blackWidth / 2
                        else left + whiteWidth * white.indexOf(index)
                    HarmoniumKey(scale, index, isBlack, playing == index, onPlay, onHold, onRelease,
                        Modifier.offset(x = x, y = if (isBlack) 0.dp else raisedTop)
                            .width(if (isBlack) blackWidth else whiteWidth - 1.dp)
                            .height(if (isBlack) 150.dp + raisedTop else 250.dp))
                }
            }
        }
    }
}

@Composable
private fun HarmoniumKey(scale: Scale, index: Int, isBlack: Boolean, active: Boolean,
    onPlay: (Int) -> Unit, onHold: (Int) -> Unit, onRelease: (Int) -> Unit, modifier: Modifier) {
    val holdCallback by rememberUpdatedState(onHold)
    val releaseCallback by rememberUpdatedState(onRelease)
    val swara = Music.swaras[index]
            val kind = stringResource(when (swara.kind) {
                "Upper" -> R.string.upper_octave
                "Komal" -> R.string.kind_komal
                "Tivra" -> R.string.kind_tivra
                "Achal" -> R.string.kind_achal
                else -> R.string.kind_shuddha
            })
            val devanagari = when (swara.name) {
                "Sa" -> "सा"; "Re" -> "रे"; "Ga" -> "ग"; "Ma" -> "म"
                "Pa" -> "प"; "Dha" -> "ध"; else -> "नि"
            }
            val name = stringResource(when (swara.name) {
                "Sa" -> R.string.swara_sa; "Re" -> R.string.swara_re
                "Ga" -> R.string.swara_ga; "Ma" -> R.string.swara_ma
                "Pa" -> R.string.swara_pa; "Dha" -> R.string.swara_dha
                else -> R.string.swara_ni
            })
            val description = stringResource(R.string.play_swara, kind, name, Music.pitchLabel(scale.midi + index))

    val background by animateColorAsState(
        if (active) Green else if (isBlack) Color(0xFF242725) else Color(0xFFFFF9E9), label = "keyColor")
    val foreground = if (active || isBlack) Color(0xFFFFF4DD) else Ink
    Surface(modifier = modifier
        .semantics {
            contentDescription = description
            role = Role.Button
            onClick { onPlay(index); true }
        }
        .pointerInput(scale.midi, index) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                down.consume()
                holdCallback(index)
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        // Own this press until release: small pointer movements must not
                        // hand the gesture to either of the parent scroll containers.
                        event.changes.forEach { it.consume() }
                    } while (pointer != null && pointer.pressed)
                } finally {
                    releaseCallback(index)
                }
            }
        },
        color = background, shape = RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp),
        shadowElevation = if (isBlack) 5.dp else 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isBlack) Color.Black else Color(0xFFD8CCB2))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(kind, color = if (isBlack || active) Gold else Green, fontSize = if (isBlack) 7.sp else 8.sp, maxLines = 1)
            Box(Modifier.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Text(devanagari, fontSize = if (isBlack) 20.sp else 23.sp, color = foreground, maxLines = 1)
                if (swara.kind == "Upper") {
                    Canvas(Modifier.align(Alignment.TopCenter).offset(y = (-1).dp).size(3.dp)) {
                        drawCircle(foreground)
                    }
                }
            }
            if (name != devanagari) Text(name, fontSize = 9.sp, color = foreground, maxLines = 1)
            Text(Music.pitchLabel(scale.midi + index), fontSize = 8.sp, color = foreground, maxLines = 1,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun scaleLabel(scale: Scale): String = stringResource(
    if (scale.white) R.string.white_scale else R.string.black_scale,
    scale.label.substringAfterLast(' ').toInt(),
)

@Composable
private fun SoundMark(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val heights = listOf(0.2f, 0.55f, 0.9f, 0.55f, 0.2f)
        heights.forEachIndexed { i, h ->
            val x = size.width * (0.1f + i * 0.2f)
            drawLine(color, Offset(x, size.height * (1 - h) / 2), Offset(x, size.height * (1 + h) / 2),
                strokeWidth = size.width * 0.065f, cap = StrokeCap.Round)
        }
    }
}
