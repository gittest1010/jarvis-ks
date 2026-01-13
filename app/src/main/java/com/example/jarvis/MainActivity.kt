package com.example.jarvis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var voiceManager: VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        voiceManager = VoiceManager(this)

        setContent {
            JarvisTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    JarvisApp(voiceManager)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::voiceManager.isInitialized) {
            voiceManager.release()
        }
    }
}

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    val darkColorScheme = darkColorScheme(
        primary = Color(0xFF00FFFF), // Cyan
        onPrimary = Color.Black,
        secondary = Color(0xFF008888),
        background = Color(0xFF050510), // Very dark blue/black
        surface = Color(0xFF0A0A1A),
        onBackground = Color.White,
        onSurface = Color.White
    )

    MaterialTheme(
        colorScheme = darkColorScheme,
        typography = Typography(),
        content = content
    )
}

@Composable
fun JarvisApp(voiceManager: VoiceManager) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted: Boolean ->
            hasPermission = isGranted
            if (!isGranted) {
                Toast.makeText(context, "Microphone permission needed", Toast.LENGTH_SHORT).show()
            }
        }
    )

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val isListening by voiceManager.isListening.collectAsState()
    val recognizedText by voiceManager.recognizedText.collectAsState()
    val audioLevel by voiceManager.audioLevel.collectAsState()

    // Sci-Fi UI Layout
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF000000), Color(0xFF001122))
                )
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        
        Text(
            text = "JARVIS",
            color = Color(0xFF00FFFF),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
            modifier = Modifier.padding(bottom = 60.dp)
        )

        // Pulsating Circular Visualizer
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(300.dp)
        ) {
            // Animating the scale based on audio level
            val scale by animateFloatAsState(
                targetValue = 1f + (audioLevel * 5f).coerceAtMost(1.0f), // Amplify level for visual
                animationSpec = spring(stiffness = Spring.StiffnessLow),
                label = "pulsating"
            )

            // Outer Glow rings
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension / 2
                
                // Static outer ring
                drawCircle(
                    color = Color(0xFF00FFFF).copy(alpha = 0.3f),
                    radius = radius,
                    style = Stroke(width = 2.dp.toPx())
                )
                
                // Pulsating inner glow
                drawCircle(
                    color = Color(0xFF00FFFF).copy(alpha = 0.1f + (audioLevel * 0.5f).coerceAtMost(0.4f)),
                    radius = radius * scale * 0.8f,
                )
            }

            // Central Core
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(scale)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF00FFFF), Color(0xFF004444))
                        ),
                        shape = CircleShape
                    )
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = if (recognizedText.isEmpty()) "Waiting for command..." else recognizedText,
            color = Color.White,
            fontSize = 18.sp,
            modifier = Modifier.padding(horizontal = 32.dp),
            minLines = 2
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = {
                if (hasPermission) {
                    if (isListening) {
                        voiceManager.stopListening()
                    } else {
                        voiceManager.startListening()
                    }
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00FFFF).copy(alpha = 0.2f),
                contentColor = Color(0xFF00FFFF)
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00FFFF)),
            modifier = Modifier.padding(16.dp)
        ) {
            Text(text = if (isListening) "STOP LISTENING" else "ACTIVATE")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = {
                voiceManager.speak("System initialized. Ready for instructions.")
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color(0xFF00FFFF)
            ),
             modifier = Modifier.padding(16.dp)
        ) {
            Text("TEST TTS")
        }
    }
}
