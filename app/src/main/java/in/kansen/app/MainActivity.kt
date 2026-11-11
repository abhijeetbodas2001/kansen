package `in`.kansen.app

import android.os.Bundle
import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
        cleanUpdateCache(this)
        player = TonePlayer(this) {
            Toast.makeText(this, R.string.audio_error, Toast.LENGTH_SHORT).show()
        }
        setContent {
            Kansen(player) { language ->
                getSharedPreferences("preferences", MODE_PRIVATE).edit().putString("language", language).apply()
                if (resources.configuration.locales[0].language != language) recreate()
            }
        }
    }
    override fun onPause() { player.stop(); super.onPause() }
    override fun onDestroy() { player.close(); super.onDestroy() }
}

@Composable
private fun Kansen(player: TonePlayer, onLanguageChange: (String) -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("preferences", Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(preferences.getString("theme", "system") ?: "system") }
    val dark = when (theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    SideEffect {
        WindowCompat.getInsetsController((context as ComponentActivity).window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFB8D6C4), onPrimary = Color(0xFF12372B),
        secondaryContainer = Color(0xFF263F59), onSecondaryContainer = Color(0xFFD6E8FF),
        tertiaryContainer = Color(0xFF554222), onTertiaryContainer = Color(0xFFFFE2AD), background = Color(0xFF17251F),
        surface = Color(0xFF21342B), onSurface = Color(0xFFE9EBDF),
        onSurfaceVariant = Color(0xFFB9C0B3), outlineVariant = Color(0xFF3C4C41),
    ) else lightColorScheme(
        primary = Green, onPrimary = Color.White,
        secondaryContainer = Color(0xFFDCE9F8), onSecondaryContainer = Color(0xFF18334D),
        tertiaryContainer = Color(0xFFF6E3BC), onTertiaryContainer = Color(0xFF48320F), background = Cream, surface = Color(0xFFFFFEF9),
        onSurface = Ink, onSurfaceVariant = Muted, outlineVariant = Line,
    )
    MaterialTheme(colorScheme = scheme) {
        var instrument by remember { mutableStateOf(player.instrument) }
        var selected by rememberSaveable { mutableIntStateOf(-1) }
        var saptak by rememberSaveable { mutableIntStateOf(0) }
        var page by rememberSaveable { mutableStateOf("home") }
        var playing by remember { mutableStateOf<Int?>(null) }
        var playCount by remember { mutableIntStateOf(0) }
        var holding by remember { mutableStateOf(false) }
        LaunchedEffect(playCount, holding) { if (!holding) { delay(3000); playing = null } }
        fun play(offset: Int) {
            holding = false
            player.play(Music.scales[selected].midi + saptak * 12 + offset)
            playing = offset
            playCount++
        }
        fun back() {
            player.stop(); playing = null; holding = false
            if (selected >= 0) selected = -1
            else if (page == "scale_identification" || page == "note_identification" || page == "saptak_identification") page = "training"
            else if (page == "manual") page = "settings"
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
                            page == "scale_identification" || page == "note_identification" || page == "saptak_identification" -> R.string.back_to_training
                            page == "manual" -> R.string.back_to_settings
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
                            painter = painterResource(R.drawable.kansen_logo),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    val pagePath = when (page) {
                        "scales" -> listOfNotNull(
                            stringResource(R.string.scales),
                            if (selected >= 0) scaleLabel(Music.scales[selected]) else null,
                        )
                        "training" -> listOf(stringResource(R.string.training))
                        "scale_identification", "note_identification", "saptak_identification" -> listOf(
                            stringResource(R.string.training),
                            stringResource(when (page) {
                                "scale_identification" -> R.string.scale_identification
                                "note_identification" -> R.string.note_identification
                                else -> R.string.saptak_identification
                            }),
                        )
                        "settings" -> listOf(stringResource(R.string.settings))
                        "manual" -> listOf(stringResource(R.string.settings), stringResource(R.string.manual))
                        else -> listOf(stringResource(R.string.home))
                    }
                    Text(
                        (listOf(stringResource(R.string.app_name)) + pagePath).joinToString(" / "),
                        modifier = Modifier.weight(1f),
                        fontFamily = FontFamily.Serif,
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Medium,
                    )

                }
                HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
                if (page == "home") {
                    CenteredActions {
                        Button(onClick = { page = "scales" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.all_scales), fontSize = 18.sp)
                        }
                        FilledTonalButton(onClick = { page = "training" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.training), fontSize = 18.sp)
                        }
                        FilledTonalButton(onClick = { page = "settings" }, colors = thirdButtonColors(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.settings), fontSize = 18.sp)
                        }
                    }
                } else if (page == "settings") {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        SettingsSection(stringResource(R.string.settings_sound)) {
                            PreferencePicker(
                                title = stringResource(R.string.instrument),
                                selected = instrument.name,
                                options = Instrument.entries.map { it.name to stringResource(it.label) },
                                onSelect = { value ->
                                    val choice = Instrument.valueOf(value)
                                    player.selectInstrument(choice)
                                    instrument = choice
                                },
                            )
                        }
                        SettingsSection(stringResource(R.string.settings_preferences)) {
                            PreferencePicker(
                                title = stringResource(R.string.language),
                                selected = LocalConfiguration.current.locales[0].language,
                                options = listOf("mr" to "मराठी", "en" to "English"),
                                onSelect = onLanguageChange,
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = scheme.outlineVariant)
                            PreferencePicker(
                                title = stringResource(R.string.theme),
                                selected = theme,
                                options = listOf(
                                    "light" to stringResource(R.string.theme_light),
                                    "dark" to stringResource(R.string.theme_dark),
                                    "system" to stringResource(R.string.theme_system),
                                ),
                                onSelect = {
                                    theme = it
                                    preferences.edit().putString("theme", it).apply()
                                },
                            )
                        }
                        SettingsSection(stringResource(R.string.settings_help)) {
                            SettingsRow(
                                title = stringResource(R.string.manual),
                                onClick = { page = "manual" },
                            )
                        }
                        SettingsSection(stringResource(R.string.settings_updates)) {
                            UpdateSettingsRow()
                        }
                        val appVersion = remember(context) {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
                        }
                        Text(
                            stringResource(R.string.app_version, appVersion),
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                } else if (page == "training") {
                    CenteredActions {
                        Button(onClick = { page = "saptak_identification" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.saptak_identification), fontSize = 18.sp)
                        }
                        FilledTonalButton(onClick = { page = "scale_identification" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.scale_identification), fontSize = 18.sp)
                        }
                        FilledTonalButton(onClick = { page = "note_identification" }, colors = thirdButtonColors(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.note_identification), fontSize = 18.sp)
                        }
                    }
                } else if (page == "scale_identification") {
                    ScaleIdentificationPage(player)
                } else if (page == "note_identification") {
                    NoteIdentificationPage(player)
                } else if (page == "saptak_identification") {
                    NoteIdentificationPage(player, saptakTraining = true)
                } else if (page == "manual") {
                    CenteredActions {
                        Text(stringResource(R.string.manual), fontFamily = FontFamily.Serif, fontSize = 28.sp)
                        SelectionContainer {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
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
                } else if (selected < 0) {
                    ScalesPage { selected = it }
                } else {
                    SwarasPage(Music.scales[selected].copy(midi = Music.scales[selected].midi + saptak * 12), playing, holding, ::play,
                        saptak = saptak, onSaptakChange = {
                            player.stop(); playing = null; holding = false; saptak = it
                        },
                        onHold = { offset ->
                            holding = true
                            playing = offset
                            player.play(Music.scales[selected].midi + saptak * 12 + offset, sustain = true)
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
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
internal fun SettingsRow(
    title: String,
    value: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = value?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
        trailingContent = {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

@Composable
private fun PreferencePicker(
    title: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsRow(
        title = title,
        value = options.firstOrNull { it.first == selected }?.second,
        onClick = { expanded = true },
        modifier = modifier,
    )
    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(title) },
            text = {
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    options.forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(selected = selected == value, role = Role.RadioButton,
                                    onClick = { expanded = false; onSelect(value) })
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == value, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label, fontSize = 18.sp)
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun ScaleIdentificationPage(player: TonePlayer) {
    var scaleIndex by rememberSaveable { mutableIntStateOf(kotlin.random.Random.nextInt(Music.scales.size)) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    var heardSa by rememberSaveable { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val scale = Music.scales[scaleIndex]
    DisposableEffect(player) { onDispose { player.stop() } }
    CenteredActions {
        HoldPlayButton(stringResource(R.string.play), scale.midi, held,
            onHold = {
                held = true
                player.playForTraining(scale.midi) { heardSa = true }
            },
            onRelease = { if (held) { held = false; player.stop() } },
            modifier = Modifier.fillMaxWidth())
        IdentificationActions(
            refreshLabel = stringResource(R.string.refresh),
            revealed = revealed,
            enabled = heardSa,
            onRefresh = {
                player.stop()
                held = false
                scaleIndex = (scaleIndex + kotlin.random.Random.nextInt(1, Music.scales.size)) % Music.scales.size
                revealed = false
                heardSa = false
            },
            onReveal = { revealed = !revealed },
        )
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
}

@Composable
private fun IdentificationActions(
    refreshLabel: String,
    revealed: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onReveal: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(
            onClick = onRefresh,
            enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(refreshLabel, fontSize = 18.sp)
        }
        FilledTonalButton(
            onClick = onReveal,
            enabled = enabled,
            colors = thirdButtonColors(),
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Icon(painterResource(if (revealed) R.drawable.ic_hide else R.drawable.ic_reveal),
                contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (revealed) R.string.hide else R.string.reveal), fontSize = 18.sp)
        }
    }
}

@Composable
private fun HoldPlayButton(
    label: String,
    midi: Int,
    held: Boolean,
    onHold: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
) {
    val holdCallback by rememberUpdatedState(onHold)
    val releaseCallback by rememberUpdatedState(onRelease)
    Button(
        // Accessibility activation toggles playback; touch plays only while held.
        onClick = { if (held) onRelease() else onHold() },
        colors = if (held) colors.copy(containerColor = colors.containerColor.copy(alpha = 0.75f)) else colors,
        modifier = modifier.heightIn(min = 56.dp).pointerInput(midi) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                down.consume()
                holdCallback()
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        event.changes.forEach { it.consume() }
                    } while (pointer != null && pointer.pressed)
                } finally {
                    releaseCallback()
                }
            }
        },
    ) {
        Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 18.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteIdentificationPage(player: TonePlayer, saptakTraining: Boolean = false) {
    var saptak by rememberSaveable { mutableIntStateOf(kotlin.random.Random.nextInt(-1, 2)) }
    var selectedScale by rememberSaveable { mutableIntStateOf(if (saptakTraining) 0 else -1) }
    var scaleIndex by rememberSaveable { mutableIntStateOf(if (saptakTraining) 0 else kotlin.random.Random.nextInt(Music.scales.size)) }
    var noteIndex by rememberSaveable { mutableIntStateOf(kotlin.random.Random.nextInt(12)) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    var heardSa by rememberSaveable { mutableStateOf(false) }
    var heardNote by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var heldPart by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(player) { onDispose { player.stop() } }
    val scale = Music.scales[scaleIndex]

    fun refresh(selection: Int = selectedScale) {
        player.stop()
        heldPart = null
        selectedScale = selection
        scaleIndex = if (selection >= 0) selection else
            (scaleIndex + kotlin.random.Random.nextInt(1, Music.scales.size)) % Music.scales.size
        noteIndex = (noteIndex + kotlin.random.Random.nextInt(1, 12)) % 12
        if (saptakTraining) saptak = kotlin.random.Random.nextInt(-1, 2)
        revealed = false
        heardSa = false
        heardNote = false
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
                if (!saptakTraining) DropdownMenuItem(text = { Text(stringResource(R.string.random_scale)) },
                    onClick = { expanded = false; refresh(-1) })
                Music.scales.forEachIndexed { index, option ->
                    DropdownMenuItem(text = { Text(scaleLabel(option)) },
                        leadingIcon = { ScaleIndicator(option) },
                        onClick = { expanded = false; refresh(index) })
                }
            }
        }
        if (saptakTraining) {
            val midi = scale.midi + saptak * 12 + noteIndex
            HoldPlayButton(
                stringResource(R.string.play), midi, heldPart == 0,
                onHold = {
                    heldPart = 0
                    player.playForTraining(midi) { heardNote = true }
                },
                onRelease = { if (heldPart == 0) { heldPart = null; player.stop() } },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (part in 0..1) {
                    val midi = scale.midi + if (part == 0) 0 else noteIndex
                    HoldPlayButton(
                        stringResource(if (part == 0) R.string.listen_sa else R.string.listen_swara),
                        midi, heldPart == part,
                        onHold = {
                            heldPart = part
                            player.playForTraining(midi) {
                                if (part == 0) heardSa = true else heardNote = true
                            }
                        },
                        onRelease = { if (heldPart == part) { heldPart = null; player.stop() } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        IdentificationActions(
            refreshLabel = stringResource(R.string.refresh_note),
            revealed = revealed,
            enabled = heardNote && (saptakTraining || heardSa),
            onRefresh = { refresh() },
            onReveal = {
                if (saptakTraining && (heldPart ?: -1) >= 2) {
                    heldPart = null
                    player.stop()
                }
                revealed = !revealed
            },
        )
        if (!saptakTraining) {
            val playKey: (Int) -> Unit = { index ->
                heldPart = index + 2
                player.play(scale.midi + index, sustain = true)
            }
            HarmoniumKeyboard(
                scale = scale,
                // Only the reference Sa and directly pressed keys reveal a highlight.
                playing = if (heldPart == 0) 0 else heldPart?.takeIf { it >= 2 }?.minus(2),
                onPlay = { index ->
                    if (heldPart == index + 2) { heldPart = null; player.stop() }
                    else playKey(index)
                },
                onHold = playKey,
                onRelease = { index ->
                    if (heldPart == index + 2) { heldPart = null; player.stop() }
                },
                noteCount = 12,
            )
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
                    if (saptakTraining) Text(saptakLabel(saptak), fontSize = 24.sp)
                    Text(scaleLabel(scale), fontSize = 20.sp)
                }
            }
        }
        if (saptakTraining && revealed) {
            (-1..1).filter { it != saptak }.forEachIndexed { index, otherSaptak ->
                val midi = scale.midi + otherSaptak * 12 + noteIndex
                val part = otherSaptak + 3
                HoldPlayButton(
                    stringResource(R.string.play_saptak, saptakLabel(otherSaptak)),
                    midi, heldPart == part,
                    onHold = { heldPart = part; player.play(midi, sustain = true) },
                    onRelease = { if (heldPart == part) { heldPart = null; player.stop() } },
                    colors = comparisonButtonColors(index),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
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
private fun SwarasPage(scale: Scale, playing: Int?, holding: Boolean, onPlay: (Int) -> Unit, saptak: Int, onSaptakChange: (Int) -> Unit, onHold: (Int) -> Unit, onRelease: (Int) -> Unit) {
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
            SaptakDropdown(saptak, onSaptakChange)
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
private fun HarmoniumKeyboard(
    scale: Scale, playing: Int?, onPlay: (Int) -> Unit,
    onHold: (Int) -> Unit, onRelease: (Int) -> Unit,
    noteCount: Int = Music.swaras.size,
) {
    val blackPitches = setOf(1, 3, 6, 8, 10)
    val white = (0 until noteCount).filter { (scale.midi + it) % 12 !in blackPitches }
    val black = (0 until noteCount).filter { it !in white }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val blackKeyRatio = 0.90f
        val raisedTop = 24.dp
        val edgeUnits = (if (0 in black) blackKeyRatio / 2 else 0f) +
            (if (noteCount - 1 in black) blackKeyRatio / 2 else 0f)
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

@Composable
private fun saptakLabel(saptak: Int): String = stringResource(when (saptak) {
    -1 -> R.string.saptak_mandra
    1 -> R.string.saptak_taar
    else -> R.string.saptak_madhya
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaptakDropdown(selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = saptakLabel(selected), onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(stringResource(R.string.saptak)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in -1..1) {
                DropdownMenuItem(text = { Text(saptakLabel(option)) },
                    onClick = { expanded = false; onSelect(option) })
            }
        }
    }
}

@Composable
private fun thirdButtonColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
)

@Composable
private fun comparisonButtonColors(index: Int): ButtonColors {
    val dark = isSystemInDarkTheme()
    return ButtonDefaults.filledTonalButtonColors(
        containerColor = if (index == 0) {
            if (dark) Color(0xFF493454) else Color(0xFFEDDDF6)
        } else {
            if (dark) Color(0xFF57333C) else Color(0xFFF8DEE5)
        },
        contentColor = if (index == 0) {
            if (dark) Color(0xFFF1DFFF) else Color(0xFF40234E)
        } else {
            if (dark) Color(0xFFFFDDE5) else Color(0xFF512632)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstrumentDropdown(selected: Instrument, onSelect: (Instrument) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource(selected.label), onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(stringResource(R.string.instrument)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Instrument.entries.forEach { instrument ->
                DropdownMenuItem(text = { Text(stringResource(instrument.label)) },
                    onClick = { expanded = false; onSelect(instrument) })
            }
        }
    }
}
