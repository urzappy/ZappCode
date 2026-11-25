package com.zapp.net.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.buffer
import okio.sink

/**
 * The body their `Import session` sends, as a pure function.
 *
 * Source: `index-BWsziAR3.js` @ 83 741 -
 *
 * ```js
 * let n = await Yn(ir(fr(G, sr)))(await t.text());
 * ...
 * return i.sdk.api.experimental.session.import({...n, location:{directory:e.worktree}});
 * ```
 *
 * So the document the picker produced is spread **first** and `location` is written **last**. That
 * ordering is the whole contract: a document that already carries a `location` (an export from a
 * machine with a different working directory) is overridden by the directory the import targets,
 * never the other way round. Spreading the other way would import the session into the directory
 * recorded in the file, which may not exist here.
 *
 * The document is parsed rather than spliced as text: an export is `JSON.stringify(data, null, 2)`
 * and may carry a BOM or trailing whitespace from whatever wrote it to disk.
 */
object SessionImport {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The parsed document, or null when the text is not a JSON object. */
    fun parse(document: String): JsonObject? = runCatching {
        json.parseToJsonElement(document.trimStart('\uFEFF').trim()) as? JsonObject
    }.getOrNull()

    /**
     * `{...n, location:{directory}}`.
     *
     * A blank directory omits `location` entirely, which is their `location` being `undefined` -
     * the server then uses its own default. Anything else writes it last, replacing whatever the
     * document carried.
     */
    fun body(document: String, directory: String?): String {
        val doc = parse(document) ?: throw IllegalArgumentException("not a session document")
        val merged = buildJsonObject {
            doc.forEach { (k, v) -> if (k != "location") put(k, v) }
            if (!directory.isNullOrBlank()) {
                put("location", buildJsonObject { put("directory", JsonPrimitive(directory)) })
            }
        }
        return json.encodeToString(JsonObject.serializer(), merged)
    }

    // ---- streaming -------------------------------------------------------------------------
    //
    // the owner, 22 Nov: "WHY NOW ITS 162MB ONLY I CAN TAKE 1GB".
    //
    // He is right, and the ceiling was ours, not his. `body()` needs the whole document as a
    // String and then builds a JSON tree from it: a 162 MB export is ~60 MB as a Java String
    // (UTF-16) plus a few hundred MB of tree nodes, on top of a second 162 MB copy when the
    // body is encoded again. An Android heap does not survive that, so an export of a real
    // conversation could never be imported - only my hand-trimmed tails could.
    //
    // None of that work is necessary. The transformation is two edits at the edges:
    //
    //   1. drop a leading BOM and, if present, the `{"data":` wrapper the export endpoint adds
    //      (the app spreads top-level keys, so `{data:{info,messages}}` is not the shape it wants)
    //   2. drop the document's final `}` and append `,"location":{...}}` before it
    //
    // Everything between those edges is copied byte for byte. So the file is streamed from the
    // picker straight into the request body and memory stays flat no matter how big it is.
    //
    // Writing `location` after an existing one is still correct: JSON objects with a repeated key
    // resolve to the last one, which is exactly what `{...n, location}` does in their JavaScript.

    private const val HOLD = 64 * 1024

    /** Bytes that make up the export endpoint's `{"data":` wrapper. */
    private val WRAPPER = "{\"data\":".toByteArray(Charsets.UTF_8)

    /**
     * A request body that streams [input] with the two edge edits applied.
     *
     * `contentLength` is -1 on purpose: the exact output length depends on how much trailing
     * whitespace the document carries, and reading the file twice to find that out is the thing
     * this exists to avoid. OkHttp sends it chunked, which the server accepts.
     */
    fun streamBody(input: java.io.InputStream, directory: String?): RequestBody =
        object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = -1L

            override fun writeTo(sink: okio.BufferedSink) {
                copy(input, directory, sink)
            }
        }

    /**
     * The same transform, written to a file instead of a socket, returning the byte count.
     *
     * Measured 2026-11-22 13:45: `curl --data-binary @file` imported the 339 409-byte tail with
     * `HTTP 200` and produced a fresh session id, while the app's own Import failed. The one
     * difference on the wire was the body's length: curl sends `Content-Length`, and
     * [streamBody] cannot (it is `-1`, so OkHttp sends `Transfer-Encoding: chunked`). Staging
     * to a file keeps memory flat - the file is written in 64 KB slices - and lets the request
     * carry a real `Content-Length`, which is the shape the server is proven to accept.
     */
    fun stage(input: java.io.InputStream, directory: String?, file: java.io.File): Long {
        file.outputStream().sink().buffer().use { copy(input, directory, it) }
        return file.length()
    }

    /** The two edge edits, streaming. Shared by [streamBody] and [stage]. */
    private fun copy(input: java.io.InputStream, directory: String?, sink: okio.BufferedSink) {
                // --- head: BOM, then the optional wrapper ---
                val probe = ByteArray(16)
                var probeLen = 0
                while (probeLen < probe.size) {
                    val n = input.read(probe, probeLen, probe.size - probeLen)
                    if (n <= 0) break
                    probeLen += n
                }
                var start = 0
                if (probeLen >= 3 &&
                    probe[0] == 0xEF.toByte() &&
                    probe[1] == 0xBB.toByte() &&
                    probe[2] == 0xBF.toByte()
                ) {
                    start = 3
                }
                // The export endpoint answers `{"data":{...}}` - one wrapper key, which adds a
                // whole extra level of braces. Dropping the wrapper therefore has to drop TWO
                // closing braces at the tail, not one; measured on the real export, the tail is
                // `...]\n}}\n`. Stripping only one produced `...},"location":{...}}`, which the
                // server rejects as trailing garbage - that was the first failure.
                var droppedWrapper = false
                if (probeLen - start >= WRAPPER.size) {
                    var same = true
                    for (i in WRAPPER.indices) {
                        if (probe[start + i] != WRAPPER[i]) { same = false; break }
                    }
                    if (same) {
                        start += WRAPPER.size
                        droppedWrapper = true
                    }
                }
                if (probeLen > start) sink.write(probe, start, probeLen - start)

                // --- body: copy everything, holding back a small tail ---
                //
                // Only the last non-whitespace byte matters, and it is a `}`. Holding a slice
                // back is enough for any trailing newline run and keeps memory flat.
                //
                // Measured 2026-11-22 13:59, the first real tap on `Import session`:
                //
                //     Import failed: size=8192 offset=0 byteCount=57344
                //
                // That is `BufferedSink.write(byte[], offset, byteCount)` refusing a write of
                // 57 344 bytes out of the 8 192-byte hold buffer. The old loop assumed one read
                // could never be larger than the hold, so `flush = holdLen + n - HOLD` was
                // allowed to exceed `holdLen` and it wrote past the end of the array. Import had
                // therefore never worked from the app - the file was never the problem.
                // The loop below moves the hold buffer forward in slices that always fit, so
                // the invariant `flush <= holdLen <= HOLD` holds for any read size.
                val hold = ByteArray(HOLD)
                var holdLen = 0
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    if (holdLen + n <= HOLD) {
                        System.arraycopy(buf, 0, hold, holdLen, n)
                        holdLen += n
                        continue
                    }
                    var off = 0
                    while (holdLen + (n - off) > HOLD) {
                        val room = HOLD - holdLen
                        if (room > 0) {
                            System.arraycopy(buf, off, hold, holdLen, room)
                            off += room
                            holdLen = HOLD
                        }
                        sink.write(hold, 0, holdLen)
                        holdLen = 0
                    }
                    System.arraycopy(buf, off, hold, 0, n - off)
                    holdLen = n - off
                }

                // --- tail: drop trailing whitespace and the closing brace(s) ---
                var end = holdLen
                while (end > 0) {
                    val c = hold[end - 1].toInt() and 0xFF
                    if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) end-- else break
                }
                if (end > 0 && hold[end - 1] == '}'.code.toByte()) end--
                // A stripped `{"data":` leaves one more `}` behind.
                if (droppedWrapper && end > 0 && hold[end - 1] == '}'.code.toByte()) end--
                sink.write(hold, 0, end)

                if (directory.isNullOrBlank()) {
                    sink.writeUtf8("}")
                } else {
                    // `JsonPrimitive.toString()` is the JSON form of the string: quoted and
                    // escaped, so a Windows path with backslashes cannot break the body.
                    sink.writeUtf8(",\"location\":{\"directory\":")
                    sink.writeUtf8(JsonPrimitive(directory).toString())
                    sink.writeUtf8("}}")
                }
    }
}
