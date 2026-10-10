package com.example.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BrightGold
import com.example.ui.theme.CoralRed
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DeepNavyBg
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.ReactorCoreCyan
import com.example.ui.theme.ReactorCoreLight
import com.example.ui.theme.ReactorOuterRing
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisScreen(
    viewModel: JarvisViewModel,
    modifier: Modifier = Modifier,
    onRequestPermissions: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    var showLogsSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Permission Launchers
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.onMicrophonePermissionGranted()
        } else {
            viewModel.onMicrophonePermissionDenied()
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.addLog("PERM", "READ_CONTACTS permission granted=$isGranted")
    }

    androidx.activity.compose.BackHandler(enabled = state.isMicActive || showLogsSheet) {
        if (showLogsSheet) {
            showLogsSheet = false
        } else if (state.isMicActive) {
            viewModel.stopJarvis()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DeepNavyBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar HUD
            JarvisTopBar(
                state = state,
                onSpeakerTestClick = { viewModel.testSpeaker() },
                onLogsClick = { showLogsSheet = true }
            )

            // Permission Request Banner if mic is not granted
            if (!state.hasMicPermission) {
                Surface(
                    color = CoralRed.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CoralRed.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            onRequestPermissions()
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.MicOff, contentDescription = null, tint = CoralRed, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Microphone access required. Tap to grant permission.",
                                color = CoralRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Text(
                            text = "GRANT",
                            color = CoralRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Central Arc Reactor Core
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                ArcReactorDisplay(
                    state = state,
                    onPowerToggle = {
                        if (!viewModel.hasMicPermission()) {
                            onRequestPermissions()
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            viewModel.toggleJarvis()
                        }
                    }
                )
            }

            // Status & Live Transcript HUD
            JarvisTranscriptSection(state = state)

            Spacer(modifier = Modifier.height(12.dp))

            // Action Pills & Quick Prompts
            JarvisCommandHints(
                state = state,
                onHintSelected = { prompt ->
                    if (prompt.contains("Mom", ignoreCase = true) || prompt.contains("Rahul", ignoreCase = true)) {
                        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }
                    viewModel.sendTextCommand(prompt)
                }
            )
        }
    }

    // Diagnostic Logs Bottom Sheet
    if (showLogsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLogsSheet = false },
            sheetState = sheetState,
            containerColor = DarkSurface
        ) {
            DiagnosticLogsSheet(
                logs = state.logs,
                onClear = { viewModel.clearLogs() },
                onSpeakerTest = { viewModel.testSpeaker() }
            )
        }
    }
}

@Composable
fun JarvisTopBar(
    state: JarvisUiState,
    onSpeakerTestClick: () -> Unit,
    onLogsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "JARVIS",
                    color = CyanPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusChip(assistantState = state.assistantState)
            }
            Text(
                text = "Autonomous Voice Intelligence",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Speaker Diagnostic button
            IconButton(
                onClick = onSpeakerTestClick,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceElevated)
                    .testTag("speaker_test_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Test Speaker",
                    tint = if (state.isTestingSpeaker) BrightGold else CyanGlow,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Diagnostic Logs Terminal button
            IconButton(
                onClick = onLogsClick,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceElevated)
                    .testTag("diagnostic_logs_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "Diagnostic Logs",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun StatusChip(assistantState: AssistantState) {
    val (bgColor, textColor, label) = when (assistantState) {
        AssistantState.IDLE -> Triple(DarkSurfaceElevated, TextMuted, "STANDBY")
        AssistantState.CONNECTING -> Triple(ElectricBlue.copy(alpha = 0.2f), CyanGlow, "CONNECTING")
        AssistantState.LISTENING -> Triple(CyanPrimary.copy(alpha = 0.2f), CyanPrimary, "LISTENING")
        AssistantState.SPEAKING -> Triple(NeonPurple.copy(alpha = 0.2f), NeonPurple, "SPEAKING")
        AssistantState.EXECUTING_ACTION -> Triple(BrightGold.copy(alpha = 0.2f), BrightGold, "ACTION")
        AssistantState.ERROR -> Triple(CoralRed.copy(alpha = 0.2f), CoralRed, "ERROR")
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, textColor.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(textColor)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun ArcReactorDisplay(
    state: JarvisUiState,
    onPowerToggle: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ReactorAnimation")

    val outerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerRotation"
    )

    val innerRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "innerRotation"
    )

    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseGlow"
    )

    // Dynamic scale responding to live audio amplitude
    val audioMultiplier = (1.0f + state.amplitude * 0.45f).coerceIn(1.0f, 1.5f)
    val dynamicPulse = if (state.assistantState == AssistantState.LISTENING || state.assistantState == AssistantState.SPEAKING) {
        audioMultiplier
    } else {
        pulseGlow
    }

    val glowColor = when (state.assistantState) {
        AssistantState.SPEAKING -> NeonPurple
        AssistantState.LISTENING -> CyanPrimary
        AssistantState.CONNECTING -> ElectricBlue
        AssistantState.EXECUTING_ACTION -> BrightGold
        AssistantState.ERROR -> CoralRed
        AssistantState.IDLE -> ReactorOuterRing
    }

    Box(
        modifier = Modifier
            .size(280.dp)
            .testTag("power_button")
            .clickable(onClick = onPowerToggle),
        contentAlignment = Alignment.Center
    ) {
        // High-tech Canvas with rotating segmented rings, energy arcs and reactor nodes
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val maxRadius = size.width / 2 - 10.dp.toPx()

            // 1. Ambient Glow Bloom
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowColor.copy(alpha = 0.35f * dynamicPulse), Color.Transparent),
                    center = center,
                    radius = maxRadius * 1.15f
                ),
                radius = maxRadius * 1.15f,
                center = center
            )

            // 2. Outermost Segmented Ring (Rotating)
            rotate(outerRotation, pivot = center) {
                drawCircle(
                    color = glowColor.copy(alpha = 0.3f),
                    radius = maxRadius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
                // 12 Outer Reactor Notches
                for (i in 0 until 12) {
                    val angle = (i * 30.0 * Math.PI / 180.0)
                    val x1 = center.x + (maxRadius - 8.dp.toPx()) * cos(angle).toFloat()
                    val y1 = center.y + (maxRadius - 8.dp.toPx()) * sin(angle).toFloat()
                    val x2 = center.x + maxRadius * cos(angle).toFloat()
                    val y2 = center.y + maxRadius * sin(angle).toFloat()
                    drawLine(
                        color = glowColor,
                        start = Offset(x1, y1),
                        end = Offset(x2, y2),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // 3. Middle Concentric Ring (Counter-Rotating)
            val midRadius = maxRadius * 0.72f
            rotate(innerRotation, pivot = center) {
                drawCircle(
                    color = glowColor.copy(alpha = 0.5f),
                    radius = midRadius,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )
                // Segment arcs
                for (i in 0 until 6) {
                    val startAngle = i * 60f + 10f
                    drawArc(
                        color = glowColor,
                        startAngle = startAngle,
                        sweepAngle = 40f,
                        useCenter = false,
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                        topLeft = Offset(center.x - midRadius, center.y - midRadius),
                        size = androidx.compose.ui.geometry.Size(midRadius * 2, midRadius * 2)
                    )
                }
            }

            // 4. Inner Reactor Core
            val innerRadius = maxRadius * 0.44f * dynamicPulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, ReactorCoreCyan, glowColor),
                    center = center,
                    radius = innerRadius
                ),
                radius = innerRadius,
                center = center
            )

            // Center Point Light
            drawCircle(
                color = Color.White,
                radius = 8.dp.toPx(),
                center = center
            )
        }

        // Center Action Icon & Overlay
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (state.isMicActive) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = if (state.isMicActive) "Jarvis Active" else "Jarvis Idle",
                tint = DeepNavyBg,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = if (state.isMicActive) "ACTIVE" else "START",
                color = DeepNavyBg,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun JarvisTranscriptSection(state: JarvisUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Executed Action Banner if present
            AnimatedVisibility(
                visible = state.lastAction != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                state.lastAction?.let { action ->
                    Surface(
                        color = BrightGold.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "⚡", fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = action,
                                color = BrightGold,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Error banner if present
            AnimatedVisibility(
                visible = state.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                state.errorMessage?.let { err ->
                    Surface(
                        color = CoralRed.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = CoralRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = err,
                                color = CoralRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // User Speech Bubble (if any)
            if (state.userTranscript.isNotBlank()) {
                Text(
                    text = "YOU",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = state.userTranscript,
                    color = TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Jarvis Response Bubble
            Text(
                text = "JARVIS",
                color = CyanPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = state.jarvisTranscript,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
fun JarvisCommandHints(
    state: JarvisUiState,
    onHintSelected: (String) -> Unit
) {
    val hints = listOf(
        "WhatsApp kholo",
        "Open YouTube",
        "Call Mom",
        "Open Instagram",
        "Hinglish mein baat karo",
        "Tell me a joke"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "TRY SAYING OR TAP TO TEST",
            color = TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            hints.forEach { hint ->
                Surface(
                    color = DarkSurfaceElevated,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .clickable { onHintSelected(hint) }
                        .testTag("hint_${hint.replace(" ", "_")}")
                ) {
                    Text(
                        text = hint,
                        color = CyanGlow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DiagnosticLogsSheet(
    logs: List<DiagnosticLog>,
    onClear: () -> Unit,
    onSpeakerTest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(480.dp)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "REAL-TIME DIAGNOSTIC HUD",
                    color = CyanPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Audio pipeline, WebSockets & Action execution",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
            Row {
                Button(
                    onClick = onSpeakerTest,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("440Hz Test", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onClear,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Clear", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp)),
            color = DeepNavyBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceElevated)
        ) {
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No logs yet. Start Jarvis to inspect events.", color = TextMuted, fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    items(logs) { log ->
                        val tagColor = when (log.tag) {
                            "AUDIO_IN", "SPEAKER" -> EmeraldGreen
                            "LIVE_OK", "SETUP" -> CyanPrimary
                            "TOOL_CALL", "TOOL_RESULT" -> BrightGold
                            "ERROR", "MIC_ERR", "AUDIO_ERR" -> CoralRed
                            "INTERRUPT" -> NeonPurple
                            else -> TextSecondary
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = log.timestamp,
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(76.dp)
                            )
                            Text(
                                text = "[${log.tag}]",
                                color = tagColor,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(88.dp)
                            )
                            Text(
                                text = log.message,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
