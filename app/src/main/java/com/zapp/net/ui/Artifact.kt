package com.zapp.net.ui

/**
 * PORT of the artifact table that ships in the desktop bundle
 * (`main-CLeW75FU.js.map`, the module that exports `artifactMime` / `artifactKind`).
 *
 * The table is theirs, key for key. `artifactExtension` takes the segment after the last `.` of
 * the **last** path segment and lowercases it; a name whose only dot is at index 0 (`.gitignore`)
 * has no extension, which is why the guard is `index <= 0` and not `index < 0`.
 */
object Artifact {

    /** Their `ArtifactKind`. */
    enum class Kind {
        Image, Svg, Audio, Video, Pdf, Html, Markdown, Mermaid, Table, Font,
        Document, Spreadsheet, Presentation, Text,
    }

    /** Their `mimes` map, verbatim. */
    private val MIMES: Map<String, String> = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "heic" to "image/heic",
        "svg" to "image/svg+xml",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "opus" to "audio/ogg",
        "weba" to "audio/webm",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "ogv" to "video/ogg",
        "mkv" to "video/x-matroska",
        "pdf" to "application/pdf",
        "html" to "text/html",
        "htm" to "text/html",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "mdx" to "text/markdown",
        "mmd" to "text/vnd.mermaid",
        "mermaid" to "text/vnd.mermaid",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    /** Their `officeKinds`. */
    private val OFFICE: Map<String, Kind> = mapOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to Kind.Document,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to Kind.Spreadsheet,
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to Kind.Presentation,
    )

    /** `artifactExtension(path)` */
    fun extensionOf(path: String): String {
        val name = path.split('/', '\\').lastOrNull() ?: ""
        val index = name.lastIndexOf('.')
        if (index <= 0) return ""
        return name.substring(index + 1).lowercase()
    }

    /** `artifactMime(path)` - `null` when the extension is unknown. */
    fun mimeOf(path: String): String? = MIMES[extensionOf(path)]

    /**
     * `artifactKind(path)` - their fallthrough order matters: an unknown extension is `Text`, and
     * a known MIME that is not one of the named groups is `Video` unless it is an office type.
     */
    fun kindOf(path: String): Kind {
        val mime = mimeOf(path) ?: return Kind.Text
        if (mime == "image/svg+xml") return Kind.Svg
        if (mime == "application/pdf") return Kind.Pdf
        if (mime == "text/html") return Kind.Html
        if (mime == "text/markdown") return Kind.Markdown
        if (mime == "text/vnd.mermaid") return Kind.Mermaid
        if (mime == "text/csv" || mime == "text/tab-separated-values") return Kind.Table
        if (mime.startsWith("image/")) return Kind.Image
        if (mime.startsWith("audio/")) return Kind.Audio
        if (mime.startsWith("font/")) return Kind.Font
        return OFFICE[mime] ?: Kind.Video
    }

    /** Whether the kind can be drawn from raw bytes. */
    fun isImage(kind: Kind): Boolean = kind == Kind.Image || kind == Kind.Svg

    /**
     * Extensions that are never text, even though `kindOf` calls them `Text`.
     *
     * Their fallthrough is "unknown extension -> Text", which is right on a desktop that has a
     * hex viewer behind it and wrong here: a `.apk` came back as a 113 MB String and killed the
     * process (`OutOfMemoryError ... at ResponseBody.string`). The viewer now refuses these
     * before a byte is requested.
     */
    private val NEVER_TEXT = setOf(
        "apk", "so", "dex", "jar", "zip", "7z", "rar", "tar", "gz", "tgz", "xz", "bz2",
        "exe", "dll", "msi", "bin", "iso", "img", "class", "o", "a", "lib", "db", "sqlite",
        "pak", "vdex", "odex", "oat", "elf", "woff", "woff2", "ttf", "otf", "pdf",
    )

    /** True when the file must not be read as text at all. */
    fun isBinaryFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in NEVER_TEXT) return true
        return when (kindOf(path)) {
            Kind.Audio, Kind.Video, Kind.Font, Kind.Pdf,
            Kind.Document, Kind.Spreadsheet, Kind.Presentation -> true
            else -> false
        }
    }
}
