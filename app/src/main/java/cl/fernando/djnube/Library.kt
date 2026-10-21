package cl.fernando.djnube

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Una pista de la biblioteca: se identifica por su URI; guarda el BPM/primer beat para no recalcularlos. */
data class LibTrack(
    val uri: String,
    val name: String,
    val bpm: Float = 0f,
    val firstBeat: Double = 0.0,
    val duration: Double = 0.0,
    val lastPlayed: Long = 0L,   // 0 = nunca tocada (no aparece en el historial)
    val plays: Int = 0,
    val size: Long = 0L          // bytes (0 = desconocido); sirve para no duplicar el mismo archivo
)

/** Pista encontrada por un escaneo, aun fuera de la biblioteca. */
class Found(val uri: String, val name: String, val size: Long)

/** Biblioteca local persistente (library.json en el almacenamiento privado de la app). */
object Library {
    val tracks = mutableStateListOf<LibTrack>()
    val folders = mutableStateListOf<String>()      // carpetas elegidas con + CARPETA (se reescanean)
    private var file: File? = null
    private var folderFile: File? = null

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "library.json")
        file = f
        val ff = File(ctx.applicationContext.filesDir, "library_folders.txt")
        folderFile = ff
        try {
            if (ff.exists()) ff.readLines().filter { it.isNotBlank() }.forEach { folders.add(it) }
        } catch (e: Exception) {
            // sin carpetas guardadas
        }
        try {
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    tracks.add(
                        LibTrack(
                            uri = o.getString("uri"),
                            name = o.optString("name", "?"),
                            bpm = o.optDouble("bpm", 0.0).toFloat(),
                            firstBeat = o.optDouble("fb", 0.0),
                            duration = o.optDouble("dur", 0.0),
                            lastPlayed = o.optLong("last", 0L),
                            plays = o.optInt("plays", 0),
                            size = o.optLong("size", 0L)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // archivo danado: se empieza de cero
        }
    }

    // La escritura va en un hilo aparte: con miles de pistas el JSON ya no bloquea la pantalla.
    private val writer = Executors.newSingleThreadExecutor()

    private fun save() {
        val f = file ?: return
        val copy = tracks.toList()
        writer.execute {
            try {
                val a = JSONArray()
                for (t in copy) {
                    a.put(
                        JSONObject()
                            .put("uri", t.uri).put("name", t.name).put("bpm", t.bpm.toDouble())
                            .put("fb", t.firstBeat).put("dur", t.duration)
                            .put("last", t.lastPlayed).put("plays", t.plays).put("size", t.size)
                    )
                }
                f.writeText(a.toString())
            } catch (e: Exception) {
                // sin espacio o sin permisos: la biblioteca sigue funcionando en memoria
            }
        }
    }

    fun find(uri: String): LibTrack? = tracks.firstOrNull { it.uri == uri }

    private fun update(uri: String, name: String, f: (LibTrack) -> LibTrack) {
        val i = tracks.indexOfFirst { it.uri == uri }
        if (i < 0) tracks.add(f(LibTrack(uri, name))) else tracks[i] = f(tracks[i])
        save()
    }

    /**
     * Agrega las pistas que aun no estan (misma URI, o mismo nombre y tamano). Devuelve cuantas eran nuevas.
     * El filtrado de duplicados va en segundo plano con tablas hash (antes era O(n^2) en el hilo de la UI y la congelaba).
     */
    suspend fun addAll(items: List<Found>): Int {
        val existing = tracks.toList()
        val fresh = withContext(Dispatchers.Default) {
            val uris = HashSet<String>(existing.size * 2 + 16)
            val keys = HashSet<String>(existing.size * 2 + 16)
            for (t in existing) {
                uris.add(t.uri)
                if (t.size > 0L) keys.add(t.name.lowercase() + "|" + t.size)
            }
            val out = ArrayList<LibTrack>()
            for (f in items) {
                if (!uris.add(f.uri)) continue
                if (f.size > 0L && !keys.add(f.name.lowercase() + "|" + f.size)) continue
                out.add(LibTrack(f.uri, f.name, size = f.size))
            }
            out
        }
        if (fresh.isNotEmpty()) {
            tracks.addAll(fresh)
            save()
        }
        return fresh.size
    }

    fun addFolder(uri: String) {
        if (uri in folders) return
        folders.add(uri)
        try {
            folderFile?.writeText(folders.joinToString("\n"))
        } catch (e: Exception) {
            // se pierde solo el recordatorio de la carpeta
        }
    }

    /** Se cargo en un deck: entra al historial. */
    fun played(uri: String, name: String, duration: Double) =
        update(uri, name) { it.copy(duration = duration, lastPlayed = System.currentTimeMillis(), plays = it.plays + 1) }

    fun saveBpm(uri: String, name: String, bpm: Float, firstBeat: Double, duration: Double) {
        if (uri.isEmpty() || bpm <= 0f) return
        update(uri, name) { it.copy(bpm = bpm, firstBeat = firstBeat, duration = if (duration > 0.0) duration else it.duration) }
    }

    fun remove(uri: String) {
        tracks.removeAll { it.uri == uri }
        save()
    }
}
