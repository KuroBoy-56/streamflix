package com.streamflixreborn.streamflix.utils

import android.util.Log
import android.util.Xml
import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Guía de programación (EPG) que traen las listas M3U ("url-tvg" en la cabecera y "tvg-id" en
 * cada canal). El archivo XMLTV (a veces .gz y muy grande) se lee por partes, guardando solo las
 * próximas 24 h de los canales de la lista; queda en memoria y en el equipo por 6 h.
 */
object LiveEpg {

    data class Programme(val start: Long, val stop: Long, val title: String)

    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private const val SEP = "\u0001"
    private const val TAG = "LiveEpg"

    private val mutex = Mutex()
    @Volatile private var data: Map<String, List<Programme>> = emptyMap()
    @Volatile private var dataKey = ""
    @Volatile private var loadedAt = 0L

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    }

    /** Programas por tvg-id (solo de [ids]). Vacío si las listas no traen guía. */
    suspend fun programmes(sources: List<String>, ids: Set<String>): Map<String, List<Programme>> {
        if (sources.isEmpty() || ids.isEmpty()) return emptyMap()
        val key = sources.sorted().joinToString("\n")
        return mutex.withLock {
            val now = System.currentTimeMillis()
            if (key == dataKey && now - loadedAt < TTL_MS) return@withLock data
            withContext(Dispatchers.IO) { readDisk(key) }?.let { if (now - loadedAt < TTL_MS) return@withLock it }
            val result = HashMap<String, MutableList<Programme>>()
            withContext(Dispatchers.IO) {
                sources.forEach { url -> runCatching { download(url, ids, result) }.onFailure { Log.w(TAG, "$url: ${it.message}") } }
            }
            val sorted = result.mapValues { (_, list) -> list.sortedBy { it.start } }
            if (sorted.isNotEmpty() || dataKey != key) {
                data = sorted
                dataKey = key
                loadedAt = now
                withContext(Dispatchers.IO) { writeDisk() }
            }
            data
        }
    }

    /** Programa actual y siguiente de una lista ordenada. */
    fun nowAndNext(list: List<Programme>, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val index = list.indexOfFirst { now < it.stop }
        if (index < 0) return null to null
        val first = list[index]
        return if (first.start <= now) first to list.getOrNull(index + 1) else null to first
    }

    private fun download(url: String, ids: Set<String>, out: MutableMap<String, MutableList<Programme>>) {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 12)").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val raw = BufferedInputStream(response.body?.byteStream() ?: return)
            raw.mark(2)
            val gz = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            (if (gz) GZIPInputStream(raw) else raw).use { parse(it, ids, out) }
        }
    }

    private fun parse(input: InputStream, ids: Set<String>, out: MutableMap<String, MutableList<Programme>>) {
        val now = System.currentTimeMillis()
        val from = now - 60 * 60 * 1000L
        val until = now + 24 * 60 * 60 * 1000L
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        var channel: String? = null
        var start = 0L
        var stop = 0L
        var inTitle = false
        var title = ""
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> return
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        val id = parser.getAttributeValue(null, "channel")
                        start = time(parser.getAttributeValue(null, "start"))
                        stop = time(parser.getAttributeValue(null, "stop"))
                        channel = id?.takeIf { it in ids && stop > from && start < until }
                        title = ""
                    }
                    "title" -> inTitle = channel != null && title.isEmpty()
                }
                XmlPullParser.TEXT -> if (inTitle) title = parser.text.orEmpty().trim()
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title" -> inTitle = false
                    "programme" -> {
                        channel?.let { if (title.isNotEmpty()) out.getOrPut(it) { mutableListOf() } += Programme(start, stop, title) }
                        channel = null
                    }
                }
            }
        }
    }

    /** "20261017203000 +0000" (XMLTV). */
    private fun time(value: String?): Long {
        val v = value?.trim().orEmpty()
        if (v.length < 14) return 0L
        return runCatching {
            if (v.length > 14) SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(v)!!.time
            else SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(v)!!.time
        }.getOrDefault(0L)
    }

    private fun diskFile(): File? = runCatching { File(StreamFlixApp.instance.filesDir, "live_epg.txt") }.getOrNull()

    private fun writeDisk() = runCatching {
        val file = diskFile() ?: return@runCatching
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write("v1$SEP$loadedAt$SEP${dataKey.hashCode()}$SEP${dataKey.length}\n")
            data.forEach { (id, list) ->
                list.forEach { p -> w.write("$id$SEP${p.start}$SEP${p.stop}$SEP${p.title.replace('\n', ' ')}\n") }
            }
        }
        tmp.renameTo(file)
    }

    private fun readDisk(key: String): Map<String, List<Programme>>? = runCatching {
        val file = diskFile()?.takeIf { it.isFile } ?: return null
        val result = HashMap<String, MutableList<Programme>>()
        var time = 0L
        file.bufferedReader().useLines { lines ->
            lines.forEachIndexed { i, line ->
                val f = line.split(SEP)
                if (i == 0) {
                    if (f.getOrNull(0) != "v1" || f.getOrNull(2) != key.hashCode().toString() || f.getOrNull(3) != key.length.toString()) return null
                    time = f[1].toLongOrNull() ?: 0L
                    return@forEachIndexed
                }
                if (f.size < 4) return@forEachIndexed
                result.getOrPut(f[0]) { mutableListOf() } += Programme(f[1].toLongOrNull() ?: 0L, f[2].toLongOrNull() ?: 0L, f[3])
            }
        }
        data = result
        dataKey = key
        loadedAt = time
        result
    }.getOrNull()
}
