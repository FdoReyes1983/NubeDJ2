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
- v0.11.0: **separacion de voz con IA** (modelo MDX-Net Kim_Vocal_2 en ONNX Runtime). Boton IA en cada pista de la biblioteca: descarga el modelo la primera vez (64 MB, requiere internet), procesa la pista completa y guarda voz + instrumental (~85 MB por pista de 4 min). Despues SIN VOZ / SOLO VOZ del deck usan esos stems (etiqueta "IA"); sin stems siguen con el efecto rapido. Dejar la app abierta mientras procesa. Maximo 10 min por pista.
- v0.10.1: aviso en pantalla y etiqueta en el deck al activar SIN VOZ / SOLO VOZ.
- v0.10.0: SIN VOZ / SOLO VOZ por deck (pestana FX): version rapida sin IA que quita o deja el canal central (150 Hz-7 kHz). Mide ~-26 dB en una voz centrada (prueba sintetica); bajo y bombo se conservan.
- v0.9.1: corregido el cuelgue (ANR) al escanear la biblioteca: el filtrado de duplicados ya no corre en el hilo de la UI y la biblioteca se guarda en segundo plano.
- v0.9.0: escaner de biblioteca: ESCANEAR busca toda la musica del telefono (MediaStore, con permiso de audio) y las carpetas guardadas; se ejecuta solo al abrir LIB. Analisis de BPM masivo (todas las pistas sin BPM, con avance y PARAR) o por pista (tocar su BPM): decodifica ~80 s del centro, 1-3 s por pista.
- v0.8.0: biblioteca local (boton LIB): agrega archivos o carpetas, el BPM se guarda por pista (la segunda vez carga sin analizar), orden A-Z / por BPM e historial; carga a deck A o B.
- v0.7.0: boton AUTO (Crossfader FX): mezcla automatica de un deck al otro en 1-32 barras, con estilos FUNDIDO / FILTER / EQ y mezcla de tempo (el entrante se sincroniza y vuelve a su tempo).
- v0.6.0: platos estilo tornamesa/Pioneer (anillo de LEDs, plato metalico, vinilo con surcos y etiqueta de color) y fader de volumen vertical (con VU) en el panel DECK vertical, ademas de en el mezclador.
- v0.5.2: corregido el error de compilacion (maxHeight/maxWidth dentro de Row).
- v0.5.1: diagnostico de compilacion + el workflow muestra el resumen de errores al fallar.
- Rango de pitch seleccionable (±8 / ±16 / ±50 %).

## Compilar
El APK de debug se genera con GitHub Actions (`.github/workflows/android-debug.yml`)
y se descarga en Actions > Artifacts. Cada APK de debug tiene firma distinta: desinstala
la versión anterior antes de instalar.

## Pruebas del motor (en PC)
`dsp.h` (EQ y filtro) y `bpm.h` (detector de BPM) no dependen de Android, por eso se pueden
compilar y probar con `g++` usando audio sintético.
