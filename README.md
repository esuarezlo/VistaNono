# FallGuard — Monitoreo de caídas y alertas SOS

**FallGuard** es un prototipo IoT de monitoreo para personas adultas. Consta de un dispositivo portátil con formato de collar, equipado con acelerómetro, giroscopio y botón de pánico, y de una aplicación móvil Android utilizada por una tercera persona para recibir alertas.

Cuando el dispositivo detecta una posible caída, publica un mensaje numérico mediante MQTT. Si la persona presiona el botón de pánico, publica el mensaje `sos`. La aplicación móvil recibe los mensajes y presenta la alerta correspondiente.

> **Importante:** Este proyecto es un prototipo educativo y experimental. La detección de caídas no está validada como sistema médico o de emergencias y no debe utilizarse como único mecanismo de protección.

## Componentes del sistema

- **Dispositivo IoT:** dispositivo portátil tipo collar con acelerómetro, giroscopio, botón de pánico y conectividad para publicar mensajes MQTT.
- **Broker MQTT:** intermediario que recibe las publicaciones del dispositivo y las distribuye a los clientes suscritos.
- **Aplicación Android:** interfaz desarrollada con Kotlin y Jetpack Compose para mostrar el estado de conexión, las alertas y un historial de eventos durante la sesión.

### Flujo de comunicación

```text
Persona adulta
     │
     ▼
Dispositivo IoT (collar)
  ├─ Sensor → posible caída → número entero
  └─ Botón de pánico → "sos"
     │
     ▼
Broker MQTT (broker.hivemq.com:1883)
     │
     ▼
Aplicación Android (FallGuard)
     │
     ├─ Alerta de caída
     ├─ Alerta SOS
     └─ Historial de eventos
```

## Funcionalidades de la aplicación

- Conectar manualmente al broker MQTT e indicar el tópico de suscripción.
- Mostrar el estado de conexión: conectando, conectado o desconectado.
- Interpretar los mensajes entrantes y mostrar alertas de **caída** o **SOS**.
- Registrar las alertas recibidas en un historial en memoria, con fecha y hora.
- Marcar una alerta activa como atendida y limpiar el historial.

**Nota:** La aplicación no determina por sí misma si ocurrió una caída; interpreta el valor publicado por el dispositivo. Marcar una alerta como atendida solo la descarta de la pantalla de la aplicación, no confirma que la persona recibió asistencia.

## Configuración MQTT

| Parámetro | Valor |
|---|---|
| Protocolo | MQTT sobre TCP |
| Host | `broker.hivemq.com` |
| Puerto | `1883` |
| Seguridad de transporte | Sin TLS (conexión pública de prueba) |
| Tópico | Configurable desde la pantalla de la aplicación |

El **dispositivo IoT y la aplicación Android deben utilizar exactamente el mismo tópico** para intercambiar mensajes. El código de ejemplo de la interfaz utiliza inicialmente `casa2026oct15/sala/led`; puedes modificarlo por un tópico propio en ambos extremos. Se recomienda un tópico único para evitar mensajes de otros usuarios del broker público.

### Formato de los mensajes

| Mensaje recibido | Interpretación en la aplicación |
|---|---|
| `sos` (sin distinguir mayúsculas/minúsculas) | Botón de pánico presionado: alerta SOS |
| Número entero mayor que `0`, por ejemplo `1` o `25` | Posible caída: alerta de caída |
| `0`, números negativos o texto no reconocido | No generan alerta |

El contenido del mensaje se procesa como texto UTF-8. En la implementación actual, cualquier entero positivo genera una alerta de caída; no se aplica un umbral adicional en Android.

## Tecnologías

- **Android:** Kotlin y Jetpack Compose.
- **Arquitectura de la app:** `AndroidViewModel` y `StateFlow` para gestionar el estado observable de la interfaz.
- **Comunicación:** cliente asíncrono HiveMQ MQTT 3.
- **Concurrencia:** corrutinas de Kotlin; resolución DNS fuera del hilo principal mediante `Dispatchers.IO`.

## Ejecución y pruebas

1. Abre el proyecto Android en **Android Studio** y compílalo con las dependencias configuradas en el proyecto.
2. Verifica que `AndroidManifest.xml` incluya el permiso `android.permission.INTERNET`.
3. Ejecuta la aplicación en un dispositivo Android o emulador con acceso a Internet.
4. Introduce el tópico que utilizará el dispositivo IoT y pulsa **Conectar**.
5. Comprueba que la interfaz indique **Conectado**.
6. Publica mensajes de prueba en el mismo tópico, desde el dispositivo IoT u otro cliente MQTT:
   - `sos` para probar la alerta de pánico.
   - `1` para probar la alerta de caída.
   - `0` para comprobar que no se genera una alerta.
7. Verifica que las alertas reconocidas aparezcan en la pantalla y el historial.

### Consideración de conectividad IPv4

En determinadas redes, el nombre del broker puede resolverse primero a una dirección IPv6 y la conexión al puerto 1883 puede fallar. En las pruebas de este proyecto se solucionó seleccionando una dirección **IPv4** del host antes de crear el cliente MQTT.

La resolución DNS debe realizarse en un hilo de entrada/salida (`Dispatchers.IO`) para evitar `NetworkOnMainThreadException`. La conexión MQTT continúa realizándose de forma asíncrona. La disponibilidad del broker y de IPv4 depende de la red utilizada.

## Limitaciones y trabajo futuro

- El broker público y el puerto 1883 no proporcionan cifrado TLS ni un canal privado; **no enviar datos personales o sensibles** bajo esta configuración.
- El historial mostrado por la implementación actual se almacena en memoria y no constituye un registro persistente.
- Las alertas se muestran mientras la aplicación está activa; el prototipo no implementa notificaciones push ni garantiza alertas cuando la aplicación está cerrada.
- La detección real de caídas depende de la lógica del dispositivo y requiere validación para medir falsas alarmas y caídas no detectadas.
- Como mejoras futuras pueden añadirse identificación del dispositivo, confirmación de atención, almacenamiento local, reconexión y supervisión de conexión, TLS/autenticación y notificaciones en segundo plano.

## Finalidad

El proyecto demuestra la integración de sensores inerciales, un dispositivo IoT, mensajería MQTT y una aplicación Android para supervisión remota y visualización de alertas en tiempo real.
