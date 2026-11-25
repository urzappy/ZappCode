package com.zapp.net.ui

/**
 * PORT of `session-ui/src/components/markdown-inline-code-kind.ts` — `inlineCodeKind`
 * (`:45`), plus the `fileNames` set (`:7-33`).
 *
 * Their doc comment states the contract: "`url` for an http(s) URL, `path` for anything that
 * could name a file. A `path` only becomes a link once the host confirms the file exists, so
 * this stays a cheap syntax check."
 *
 * The phone can confirm existence — `MainViewModel.openFile` reads through
 * `GET /api/fs/read/<path>` and shows its own error row — so a `path` becomes a tappable span
 * and a missing file lands on that error instead of doing nothing.
 */
object InlineCodeKind {

    /** `:7-33`, in their order. Common files that carry no extension. */
    private val FILE_NAMES = setOf(
        "CHANGELOG",
        "CODEOWNERS",
        "Containerfile",
        "Dockerfile",
        "Gemfile",
        "Justfile",
        "LICENSE",
        "Makefile",
        "Procfile",
        "README",
        "Rakefile",
        "Vagrantfile",
    )

    /** `:47` — `/^https?:\/\//i`. */
    private val HTTP = Regex("""^https?://""", RegexOption.IGNORE_CASE)

    /** `:51` — any other scheme with `://` is not a path either. */
    private val SCHEME = Regex("""^[a-z][a-z0-9+.-]*://""", RegexOption.IGNORE_CASE)

    /** `:51` — a path has no whitespace. */
    private val SPACE = Regex("""\s""")

    /** `:57` — "Code, globs and markup, not paths." */
    private val CODE_CHARS = Regex("""[{}<>"'`;=|&*,]""")

    /** `:63` — a network share would make the server dial the host just to check it. */
    private val SHARE = Regex("""^[\\/]{2}""")

    /** `:75` — needs a separator and a letter, so `1.5` and `v2.0` stay code. */
    private val SEPARATOR = Regex("""[./\\]""")
    private val LETTER = Regex("""[a-z]""", RegexOption.IGNORE_CASE)

    fun kind(text: String): Kind {
        if (HTTP.containsMatchIn(text)) return Kind.Url
        if (text.isEmpty() || SPACE.containsMatchIn(text)) return Kind.None
        if (SCHEME.containsMatchIn(text)) return Kind.None
        if (CODE_CHARS.containsMatchIn(text)) return Kind.None
        if (SHARE.containsMatchIn(text)) return Kind.None
        val path = PathSuffix.parse(text).path
        if (FILE_NAMES.contains(path)) return Kind.Path
        if (SEPARATOR.containsMatchIn(path) && LETTER.containsMatchIn(path)) return Kind.Path
        return Kind.None
    }

    enum class Kind { None, Url, Path }
}
