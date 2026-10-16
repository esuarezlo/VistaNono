package com.erns.vistanono

//package com.example.fallguard

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.net.Inet4Address
import java.net.InetAddress

// ─────────────────────────────────────────────────────────────
//  CONFIGURACIÓN MQTT
// ─────────────────────────────────────────────────────────────
private const val MQTT_HOST = "broker.hivemq.com"
private const val MQTT_PORT = 1883
private const val TAG = "FallGuardMQTT"

// IMPORTANTE: el broker es público, usa nombres de tópico únicos y
// que coincidan EXACTAMENTE con los que publica tu collar.
// Si el collar publica caída y SOS en el mismo tópico, pon el mismo
// valor en ambas constantes (se suscribe una sola vez).
//private const val TOPIC_CAIDA = "mi_collar_iot_demo/caida"
//private const val TOPIC_SOS = "mi_collar_iot_demo/sos"

// ─────────────────────────────────────────────────────────────
//  MODELO
// ─────────────────────────────────────────────────────────────
enum class AlertType { FALL, SOS }
enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

data class AlertEvent(
    val type: AlertType,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class UiState(
    //val connection: ConnectionState = ConnectionState.CONNECTING,
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val activeAlert: AlertEvent? = null,
    val history: List<AlertEvent> = emptyList(),
    val error: String? = null
)

// ─────────────────────────────────────────────────────────────
//  VIEWMODEL + MQTT
// ─────────────────────────────────────────────────────────────
class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private lateinit var client: Mqtt3AsyncClient

    fun openConnection(topic: String) {
        val selectedTopic = topic.trim()

        if (selectedTopic.isEmpty()) {
            _state.update {
                it.copy(error = "El tópico no puede estar vacío")
            }
            return
        }

        _state.update {
            it.copy(
                connection = ConnectionState.CONNECTING,
                error = null
            )
        }

        viewModelScope.launch {
            try {
                // Resolver DNS fuera del hilo principal
                val ipv4 = withContext(Dispatchers.IO) {
                    InetAddress.getAllByName(MQTT_HOST)
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull()
                        ?: throw IllegalStateException(
                            "No se encontró una dirección IPv4"
                        )
                }

                Log.d(TAG, "Dirección IPv4: ${ipv4.hostAddress}")

                // Crear el cliente MQTT
                client = Mqtt3Client.builder()
                    .identifier("fallguard-" + UUID.randomUUID())
                    .serverHost(ipv4)
                    .serverPort(MQTT_PORT)
                    .automaticReconnectWithDefaultConfig()
                    .addConnectedListener {
                        Log.d(TAG, "Conectado al broker MQTT")

                        _state.update {
                            it.copy(
                                connection = ConnectionState.CONNECTED,
                                error = null
                            )
                        }

                        subscribeAll(selectedTopic)
                    }
                    .addDisconnectedListener { ctx ->
                        val msg = ctx.cause.message
                            ?: ctx.cause.javaClass.simpleName

                        Log.e(TAG, "Desconectado: $msg", ctx.cause)

                        _state.update {
                            it.copy(
                                connection = ConnectionState.DISCONNECTED,
                                error = msg
                            )
                        }
                    }
                    .buildAsync()

                // Recibir mensajes MQTT
                client.publishes(MqttGlobalPublishFilter.ALL) { publish ->
                    val message = String(
                        publish.payloadAsBytes,
                        Charsets.UTF_8
                    )

                    Log.d(TAG, "Mensaje recibido: $message")
                    handleMessage(message)
                }

                // Conectar de manera asíncrona
                client.connectWith()
                    .keepAlive(30)
                    .cleanSession(true)
                    .send()
                    .whenComplete { _, throwable ->
                        if (throwable != null) {
                            Log.e(TAG, "Error al conectar MQTT", throwable)

                            _state.update {
                                it.copy(
                                    connection = ConnectionState.DISCONNECTED,
                                    error = throwable.message
                                )
                            }
                        }
                    }

            } catch (e: Exception) {
                Log.e(TAG, "Error al preparar conexión MQTT", e)

                _state.update {
                    it.copy(
                        connection = ConnectionState.DISCONNECTED,
                        error = e.message
                    )
                }
            }
        }
    }

    private fun subscribeAll(topic: String) {
        listOf(topic).distinct().forEach { topic ->
            client.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .send()
        }
//        listOf(TOPIC_CAIDA, TOPIC_SOS).distinct().forEach { topic ->
//            client.subscribeWith()
//                .topicFilter(topic)
//                .qos(MqttQos.AT_LEAST_ONCE)
//                .send()
//        }
    }

    /**
     * Interpreta el contenido:
     *  - "sos"          -> botón de pánico
     *  - entero > 0     -> caída
     */
    private fun handleMessage(raw: String) {
        val text = raw.trim()
        val event = when {
            text.equals("sos", ignoreCase = true) ->
                AlertEvent(AlertType.SOS, "Botón de pánico presionado")

            (text.toIntOrNull() ?: 0) > 0 ->
                AlertEvent(AlertType.FALL, "Caída detectada (valor recibido: $text)")

            else -> null
        } ?: return

        _state.update {
            it.copy(activeAlert = event, history = listOf(event) + it.history)
        }
        //vibrate()
    }

    fun dismissAlert() = _state.update { it.copy(activeAlert = null) }

    fun clearHistory() = _state.update { it.copy(history = emptyList()) }

//    private fun vibrate() {
//        val ctx = getApplication<Application>()
//        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
//                .defaultVibrator
//        } else {
//            @Suppress("DEPRECATION")
//            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
//        }
////        vibrator?.vibrate(
////            VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1)
////        )
//    }

    override fun onCleared() {
        runCatching { client.disconnect() }
        super.onCleared()
    }
}

// ─────────────────────────────────────────────────────────────
//  ACTIVITY
// ─────────────────────────────────────────────────────────────
//class MainActivity : ComponentActivity() {
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
//        setContent {
//            FallGuardTheme {
//                MonitorScreen()
//            }
//        }
//    }
//}

// ─────────────────────────────────────────────────────────────
//  TEMA
// ─────────────────────────────────────────────────────────────
private val BgTop = Color(0xFF0F172A)
private val BgBottom = Color(0xFF1E293B)
private val CardSurface = Color(0xFF273449)
private val OkGreen = Color(0xFF22C55E)
private val FallRed = Color(0xFFEF4444)
private val SosMagenta = Color(0xFFE11D74)
private val Amber = Color(0xFFF59E0B)

@Composable
fun FallGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = OkGreen,
            background = BgTop,
            surface = CardSurface,
            onSurface = Color(0xFFE2E8F0)
        ),
        content = content
    )
}

// ─────────────────────────────────────────────────────────────
//  PANTALLA ÚNICA
// ─────────────────────────────────────────────────────────────
@Composable
fun MonitorScreen(vm: MonitorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val onClickOpenConnection: (String) -> Unit = { topic ->
        vm.openConnection(topic)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            SettingConnection(onClickOpenConnection)
            Spacer(Modifier.height(24.dp))
            Header(state.connection)
            Spacer(Modifier.height(24.dp))
            StatusCard(
                alert = state.activeAlert,
                onDismiss = vm::dismissAlert
            )
            Spacer(Modifier.height(24.dp))
            HistorySection(
                history = state.history,
                onClear = vm::clearHistory,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SettingConnection(onClickOpenConnection: (String) -> Unit) {
    val topic = remember { mutableStateOf("ciis2026/codigo/cui123456") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            label = { Text("Topico") },
            value = topic.value,
            onValueChange = { topic.value = it }

        )
        Button(onClick = { onClickOpenConnection(topic.value) }) {
            Text("Conectar")
        }

    }
}

@Composable
private fun Header(connection: ConnectionState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                "FallGuard",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                "Monitoreo en tiempo real",
                fontSize = 14.sp,
                color = Color(0xFF94A3B8)
            )
        }
        ConnectionChip(connection)
    }
}

@Composable
private fun ConnectionChip(connection: ConnectionState) {
    val (color, label) = when (connection) {
        ConnectionState.CONNECTED -> OkGreen to "Conectado"
        ConnectionState.CONNECTING -> Amber to "Conectando…"
        ConnectionState.DISCONNECTED -> FallRed to "Sin conexión"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusCard(alert: AlertEvent?, onDismiss: () -> Unit) {
    val (colors, icon, title, subtitle) = when (alert?.type) {
        AlertType.FALL -> StatusStyle(
            listOf(Color(0xFFB91C1C), FallRed),
            Icons.Default.Warning,
            "¡Caída detectada!",
            alert.detail
        )

        AlertType.SOS -> StatusStyle(
            listOf(Color(0xFF9D174D), SosMagenta),
            Icons.Default.Notifications,
            "¡SOS!",
            alert.detail
        )

        null -> StatusStyle(
            listOf(Color(0xFF15803D), OkGreen),
            Icons.Default.CheckCircle,
            "Todo en orden",
            "Esperando eventos del collar…"
        )
    }

    // Latido solo cuando hay alerta activa
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (alert != null) 1.12f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "scale"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(colors))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(56.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )
            if (alert != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    formatTime(alert.timestamp),
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.75f)
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF111827)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text("Marcar como atendido", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private data class StatusStyle(
    val colors: List<Color>,
    val icon: ImageVector,
    val title: String,
    val subtitle: String
)

@Composable
private fun HistorySection(
    history: List<AlertEvent>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Historial",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            if (history.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color(0xFF94A3B8)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Limpiar", color = Color(0xFF94A3B8))
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Aún no hay eventos registrados",
                    color = Color(0xFF64748B),
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(history) { event -> HistoryItem(event) }
            }
        }
    }
}

@Composable
private fun HistoryItem(event: AlertEvent) {
    val (color, icon, label) = when (event.type) {
        AlertType.FALL -> Triple(FallRed, Icons.Default.Warning, "Caída")
        AlertType.SOS -> Triple(SosMagenta, Icons.Default.Notifications, "SOS")
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(event.detail, color = Color(0xFF94A3B8), fontSize = 13.sp)
            }
            Text(
                formatTime(event.timestamp),
                color = Color(0xFF64748B),
                fontSize = 12.sp
            )
        }
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss · dd/MM", Locale.getDefault()).format(Date(millis))