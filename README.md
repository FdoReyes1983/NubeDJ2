# Nube DJ 2 (v0.5.0)

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
- v0.2.2: waveform ampliado (graves/medios/agudos, cuadricula de beats; arrastrar = mover, doble toque = zoom 3/6/12 s), SYNC pendiente si el BPM aun se calcula, boton RESET del pitch, tiempo de carga visible.
- v0.3.0: pestañas JOG / LOOP / FX en cada deck. Loops de 1/16 a 32 beats (empiezan en el beat más cercano, se ven en el waveform). Efectos ECO, REVERB, FLANGER, PHASER y GATE con pad XY (X = tiempo/velocidad, Y = cantidad; LOCK lo deja encendido).
- v0.4.0: carga progresiva (la pista se puede tocar a los ~2.5 s, sigue cargando detrás), los dos waveforms ampliados juntos arriba y a la misma escala de tiempo real (se ve la sincronía), pestaña global DECK / LOOP / FX que cambia el panel de cada plato, mezclador compacto con faders de volumen verticales y fader de pitch vertical.
- v0.5.0: la app gira con el teléfono. En vertical se elige el plato 1, el 2 o MIX (ambos), con pestañas DECK / LOOP / FX / EQ; en horizontal queda la vista de dos platos. Faders (volumen, pitch y crossfader) y perillas con aspecto de mesa de mezcla.
- Rango de pitch seleccionable (±8 / ±16 / ±50 %).

## Compilar
El APK de debug se genera con GitHub Actions (`.github/workflows/android-debug.yml`)
y se descarga en Actions > Artifacts. Cada APK de debug tiene firma distinta: desinstala
la versión anterior antes de instalar.

## Pruebas del motor (en PC)
`dsp.h` (EQ y filtro) y `bpm.h` (detector de BPM) no dependen de Android, por eso se pueden
compilar y probar con `g++` usando audio sintético.
