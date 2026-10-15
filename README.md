# Nube DJ 2 (v0.2.1)

App DJ Android con motor de audio propio en C++ (Oboe, baja latencia).

## Qué incluye
- 2 decks, carga de audio local, PLAY / CUE, jog con scratch, waveform general.
- **EQ de 3 bandas** (HIGH / MID / LOW) por deck con cruces de 24 dB/oct (250 Hz y 3 kHz).
  Centro = plano, +6 dB arriba, hasta anular la banda abajo. Doble toque en una perilla = centro.
- **Filtro DJ** por deck: izquierda = pasa-bajos, derecha = pasa-altos, centro = apagado.
- **RESET VOL**: deja el volumen de ambos decks al máximo. X-FADER: toca el título para centrarlo.
- **BPM automático** al cargar la pista, con el primer beat. Botones ÷2 / ×2 por si detecta la
  octava equivocada (por ejemplo 154 en vez de 77), TAP para fijarlo a mano y SET para marcar un beat.
- **SYNC**: iguala el tempo al otro deck y, si ambos suenan, alinea también los beats.
- v0.2.1: BPM mucho más preciso (ya no confunde 117 con 78 en reggaetón/dembow) y la pista suena al tiro: el BPM se calcula en segundo plano (muestra «…»).
- Rango de pitch seleccionable (±8 / ±16 / ±50 %).

## Compilar
El APK de debug se genera con GitHub Actions (`.github/workflows/android-debug.yml`)
y se descarga en Actions > Artifacts. Cada APK de debug tiene firma distinta: desinstala
la versión anterior antes de instalar.

## Pruebas del motor (en PC)
`dsp.h` (EQ y filtro) y `bpm.h` (detector de BPM) no dependen de Android, por eso se pueden
compilar y probar con `g++` usando audio sintético.
