package com.zapp.net.ui

import java.text.Normalizer
import kotlin.math.E
import kotlin.math.pow

/**
 * PORT of `fuzzysort@3.1.0`, the exact library OpenCode 2.3.2 bundles.
 *
 * ## Why this file exists
 *
 * Their settings search does not do a substring test. `settings/search.ts` ranks every result with
 *
 *     let l = fuzzysort.single(normalizedQuery, normalizedTitle)?.score ?? 0
 *
 * and that score decides two things: whether a result survives the gate at all (`l < 0.6` drops
 * it when no word matches by substring), and whether it earns tier 1 when no other tier applies.
 * A substring matcher cannot reproduce those decisions, so the matcher itself had to be ported.
 *
 * ## Fidelity
 *
 * This is a line-by-line port of `node_modules/fuzzysort/fuzzysort.js` as shipped inside the
 * extracted OpenCode build, not a reimplementation from the README. Everything is reproduced:
 *
 * - `remove_accents` - NFD **inside Latin runs only**, then the combining block stripped. Their
 *   comment says the simple `NFD + strip` form "screws with japanese characters", and that
 *   distinction is preserved here because it changes which targets match.
 * - `prepareLowerInfo` - the 32-bit presence bloom that rejects a target before any scan.
 * - `prepareBeginningIndexes` / `prepareNextBeginningIndexes` - word-boundary discovery.
 * - `algorithm` - the simple scan, the strict backtracking scan with its 200-step cap, the
 *   substring promotion, and `calculateScore` exactly as written including its arbitrary-looking
 *   constants (`12`, `.2`, `1000`, `24`, the double `(targetLen - searchLen)/2`).
 * - `algorithmSpaces` - per-word scoring, the order penalty, the `_nextBeginningIndexes` mutation
 *   that lets `strawberry` match `straw berry`, and its restore.
 * - `normalizeScore` - the exponential squash that turns a raw score into 0..1.
 *
 * ## What is deliberately absent
 *
 * `go`, `highlight`, `all`, the prepared-target cache and the key/keys variants. Their search only
 * ever calls `single`, so the rest of the library has no consumer here and porting it would be
 * untested code pretending to be parity.
 */
object FuzzySort {

    /** `\p{Script=Latin}+` - Java spells the same script class `IsLatin`. */
    private val LATIN_RUN = Regex("\\p{IsLatin}+")

    /** `[\u0300-\u036f]` - the combining diacritical block, stripped after decomposition. */
    private val COMBINING = Regex("[\\u0300-\\u036f]")

    /**
     * `remove_accents`. Decompose Latin runs, then drop the marks.
     *
     * Their comment: *"we use this instead of just `.normalize('NFD').replace(...)` because that
     * screws with japanese characters"*. Decomposing only the Latin runs is what keeps kana and
     * hangul intact, and it changes which targets match, so it is reproduced exactly.
     */
    fun removeAccents(text: String): String =
        COMBINING.replace(
            LATIN_RUN.replace(text) { match -> Normalizer.normalize(match.value, Normalizer.Form.NFD) },
            "",
        )

    // ---------------------------------------------------------------------------------------
    // prepared forms
    // ---------------------------------------------------------------------------------------

    private class LowerInfo(
        val lower: String,
        val lowerCodes: IntArray,
        val bitflags: Int,
        val containsSpace: Boolean,
    )

    /**
     * `prepareLowerInfo`.
     *
     * One trap: JavaScript reads `strLen` **before** lowering and then walks `i < strLen` over the
     * lowered string, so a lowering that changes length reads a stale window (`charCodeAt` past the
     * end yields `NaN`, which matches none of the ranges below and lands in the `other` bucket).
     * That is reproduced rather than "fixed", because a fixed version would score differently.
     */
    private fun lowerInfo(input: String): LowerInfo {
        val text = removeAccents(input)
        val strLen = text.length
        val lower = text.lowercase()
        val codes = IntArray(strLen)
        var bitflags = 0
        var containsSpace = false

        for (i in 0 until strLen) {
            // NaN stands in as -1: no comparison in JavaScript is true for NaN, so it sets bit 31.
            val code = if (i < lower.length) lower[i].code else -1
            codes[i] = code

            if (code == 32) {
                containsSpace = true
                continue
            }

            val bit = when {
                code in 97..122 -> code - 97   // a-z
                code in 48..57 -> 26           // 0-9
                code in 0..127 -> 30           // other ascii
                else -> 31                     // other utf16
            }
            bitflags = bitflags or (1 shl bit)
        }

        return LowerInfo(lower, codes, bitflags, containsSpace)
    }

    /** A prepared target. Carries the scratch fields `algorithm` writes, exactly like theirs. */
    internal class Prepared(val target: String) {
        val lower: String
        val lowerCodes: IntArray
        val bitflags: Int
        var nextBeginningIndexes: IntArray? = null
        var score: Double = Double.NEGATIVE_INFINITY
        var indexes: IntArray = IntArray(0)

        init {
            val info = lowerInfo(target)
            lower = info.lower
            lowerCodes = info.lowerCodes
            bitflags = info.bitflags
        }
    }

    private class PreparedSearch(
        val lower: String,
        val lowerCodes: IntArray,
        val containsSpace: Boolean,
        val bitflags: Int,
        val spaceSearches: List<PreparedSearch>,
    )

    /** `prepareSearch`. */
    private fun prepareSearch(input: String): PreparedSearch {
        val search = input.trim()
        val info = lowerInfo(search)

        val spaceSearches = ArrayList<PreparedSearch>()
        if (info.containsSpace) {
            // `search.split(/\s+/g)` then `new Set(...)` - distinct, order preserved.
            val parts = search.split(WHITESPACE).filter { it.isNotEmpty() }.distinct()
            for (part in parts) {
                val partInfo = lowerInfo(part)
                spaceSearches.add(
                    PreparedSearch(
                        // Their pushed `_lower` is `part.toLowerCase()` - the *unaccented* form is
                        // only used for the codes. Reproduced as written.
                        lower = part.lowercase(),
                        lowerCodes = partInfo.lowerCodes,
                        containsSpace = false,
                        bitflags = partInfo.bitflags,
                        spaceSearches = emptyList(),
                    ),
                )
            }
        }

        return PreparedSearch(
            lower = info.lower,
            lowerCodes = info.lowerCodes,
            containsSpace = info.containsSpace,
            bitflags = info.bitflags,
            spaceSearches = spaceSearches,
        )
    }

    /** JavaScript `\s`, which is wider than Java's default. */
    private val WHITESPACE = Regex(
        "[\\t\\n\\u000B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+",
    )

    /** `prepareBeginningIndexes`. */
    private fun prepareBeginningIndexes(target: String): IntArray {
        val targetLen = target.length
        val out = IntArray(targetLen)
        var len = 0
        var wasUpper = false
        var wasAlphanum = false

        for (i in 0 until targetLen) {
            val code = target[i].code
            val isUpper = code in 65..90
            val isAlphanum = isUpper || code in 97..122 || code in 48..57
            val isBeginning = (isUpper && !wasUpper) || !wasAlphanum || !isAlphanum
            wasUpper = isUpper
            wasAlphanum = isAlphanum
            if (isBeginning) out[len++] = i
        }

        return out.copyOf(len)
    }

    /**
     * `prepareNextBeginningIndexes`: for each position, the next beginning index strictly after it,
     * or `targetLen` as the sentinel. JavaScript uses `undefined` for "no more"; `targetLen` is the
     * same value it substitutes, so the sentinel is used directly.
     */
    private fun prepareNextBeginningIndexes(target: String): IntArray {
        val clean = removeAccents(target)
        val targetLen = clean.length
        val beginnings = prepareBeginningIndexes(clean)
        val next = IntArray(targetLen)

        var lastIsBeginning = if (beginnings.isNotEmpty()) beginnings[0] else -1
        var lastIndex = 0

        for (i in 0 until targetLen) {
            if (lastIsBeginning > i) {
                next[i] = lastIsBeginning
            } else {
                lastIndex += 1
                lastIsBeginning = if (lastIndex < beginnings.size) beginnings[lastIndex] else -1
                next[i] = if (lastIsBeginning < 0) targetLen else lastIsBeginning
            }
        }

        return next
    }

    // ---------------------------------------------------------------------------------------
    // the public entry point their search calls
    // ---------------------------------------------------------------------------------------

    /**
     * `fuzzysort.single(search, target).score`, or `null` when they return `NULL`.
     *
     * The score is normalised to `0..1`. Their call site only ever compares it against `0.6`, but
     * the raw value is returned so a caller can order by it if it ever needs to.
     */
    fun single(search: String, target: String): Double? {
        if (search.isEmpty() || target.isEmpty()) return null

        val preparedSearch = prepareSearch(search)
        if (preparedSearch.lowerCodes.isEmpty()) return null

        val prepared = Prepared(target)

        // The bloom filter: if any character class in the search is absent from the target, the
        // target cannot match and is rejected without a scan.
        if ((preparedSearch.bitflags and prepared.bitflags) != preparedSearch.bitflags) return null

        val result = algorithm(preparedSearch, prepared) ?: return null
        return normalizeScore(result.score)
    }

    /** `normalizeScore` - the exponential squash. */
    private fun normalizeScore(score: Double): Double = when {
        score == Double.NEGATIVE_INFINITY -> 0.0
        score > 1 -> score
        else -> E.pow((((-score + 1).pow(0.04307)) - 1) * -2)
    }

    // ---------------------------------------------------------------------------------------
    // algorithm
    // ---------------------------------------------------------------------------------------

    private fun algorithm(
        preparedSearch: PreparedSearch,
        prepared: Prepared,
        allowSpaces: Boolean = false,
        allowPartialMatch: Boolean = false,
    ): Prepared? {
        if (!allowSpaces && preparedSearch.containsSpace) {
            return algorithmSpaces(preparedSearch, prepared, allowPartialMatch)
        }

        val searchLowerCodes = preparedSearch.lowerCodes
        var searchLowerCode = searchLowerCodes[0]
        val targetLowerCodes = prepared.lowerCodes
        val searchLen = searchLowerCodes.size
        val targetLen = targetLowerCodes.size

        // --- the cheap scan: every search char in order, gaps allowed ---
        var searchI = 0
        var targetI = 0
        val matchesSimple = IntArray(searchLen)
        var matchesSimpleLen = 0

        while (true) {
            val isMatch = targetI < targetLen && searchLowerCode == targetLowerCodes[targetI]
            if (isMatch) {
                matchesSimple[matchesSimpleLen++] = targetI
                searchI += 1
                if (searchI == searchLen) break
                searchLowerCode = searchLowerCodes[searchI]
            }
            targetI += 1
            if (targetI >= targetLen) return null
        }

        // --- the strict scan: only consecutive runs or word beginnings count ---
        val nextBeginningIndexes = prepared.nextBeginningIndexes
            ?: prepareNextBeginningIndexes(prepared.target).also { prepared.nextBeginningIndexes = it }

        var searchIStrict = 0
        var successStrict = false
        val matchesStrict = IntArray(searchLen)
        var matchesStrictLen = 0

        targetI = if (matchesSimple[0] == 0) 0 else nextBeginningIndexes[matchesSimple[0] - 1]

        var backtrackCount = 0
        if (targetI != targetLen) {
            while (true) {
                if (targetI >= targetLen) {
                    if (searchIStrict <= 0) break
                    backtrackCount += 1
                    if (backtrackCount > 200) break

                    searchIStrict -= 1
                    matchesStrictLen -= 1
                    targetI = nextBeginningIndexes[matchesStrict[matchesStrictLen]]
                } else {
                    val isMatch = searchLowerCodes[searchIStrict] == targetLowerCodes[targetI]
                    if (isMatch) {
                        matchesStrict[matchesStrictLen++] = targetI
                        searchIStrict += 1
                        if (searchIStrict == searchLen) {
                            successStrict = true
                            break
                        }
                        targetI += 1
                    } else {
                        targetI = nextBeginningIndexes[targetI]
                    }
                }
            }
        }

        // --- substring promotion ---
        val substringIndexRaw =
            if (searchLen <= 1) -1 else prepared.lower.indexOf(preparedSearch.lower, matchesSimple[0])

        var isSubstring = substringIndexRaw >= 0
        var substringIndex = substringIndexRaw
        var isSubstringBeginning = if (!isSubstring) {
            false
        } else {
            substringIndex == 0 || nextBeginningIndexes[substringIndex - 1] == substringIndex
        }

        // A substring that does not start on a word boundary is retried from the next boundary.
        if (isSubstring && !isSubstringBeginning) {
            var i = 0
            while (i < nextBeginningIndexes.size) {
                if (i > substringIndex) {
                    var s = 0
                    while (s < searchLen) {
                        if (i + s >= targetLen || searchLowerCodes[s] != targetLowerCodes[i + s]) break
                        s += 1
                    }
                    if (s == searchLen) {
                        substringIndex = i
                        isSubstringBeginning = true
                        break
                    }
                }
                i = nextBeginningIndexes[i]
            }
        }

        fun calculateScore(matches: IntArray): Double {
            var score = 0.0

            var extraMatchGroupCount = 0
            for (i in 1 until searchLen) {
                if (matches[i] - matches[i - 1] != 1) {
                    score -= matches[i]
                    extraMatchGroupCount += 1
                }
            }

            val unmatchedDistance = matches[searchLen - 1] - matches[0] - (searchLen - 1)
            score -= (12 + unmatchedDistance) * extraMatchGroupCount

            if (matches[0] != 0) score -= matches[0] * matches[0] * 0.2

            if (!successStrict) {
                score *= 1000
            } else {
                // A target with many word beginnings is a bad target and loses points for it.
                var uniqueBeginningIndexes = 1
                var i = nextBeginningIndexes[0]
                while (i < targetLen) {
                    uniqueBeginningIndexes += 1
                    i = nextBeginningIndexes[i]
                }
                if (uniqueBeginningIndexes > 24) score *= (uniqueBeginningIndexes - 24) * 10
            }

            score -= (targetLen - searchLen) / 2.0

            if (isSubstring) score /= 1.0 + searchLen * searchLen
            if (isSubstringBeginning) score /= 1.0 + searchLen * searchLen

            score -= (targetLen - searchLen) / 2.0

            return score
        }

        val matchesBest: IntArray
        val score: Double

        if (!successStrict) {
            if (isSubstring) for (i in 0 until searchLen) matchesSimple[i] = substringIndex + i
            matchesBest = matchesSimple
            score = calculateScore(matchesBest)
        } else {
            if (isSubstringBeginning) {
                for (i in 0 until searchLen) matchesSimple[i] = substringIndex + i
                matchesBest = matchesSimple
                score = calculateScore(matchesSimple)
            } else {
                matchesBest = matchesStrict
                score = calculateScore(matchesStrict)
            }
        }

        prepared.score = score
        prepared.indexes = IntArray(searchLen) { matchesBest[it] }
        return prepared
    }

    // ---------------------------------------------------------------------------------------
    // algorithmSpaces
    // ---------------------------------------------------------------------------------------

    /**
     * `algorithmSpaces` - one pass per word, averaged, with an order penalty so `c man` prefers
     * `CheatManager.h` over `Manifest.cpp`, plus the `straw berry` trick: when a word matched as a
     * consecutive run, the position just past it is promoted to a word beginning for the rest of
     * the search. All of it is undone before returning.
     */
    private fun algorithmSpaces(
        preparedSearch: PreparedSearch,
        prepared: Prepared,
        allowPartialMatch: Boolean,
    ): Prepared? {
        val seen = LinkedHashSet<Int>()
        var score = 0.0
        var result: Prepared? = null
        var firstSeenIndexLastSearch = 0
        val searches = preparedSearch.spaceSearches
        val searchesLen = searches.size
        val changes = ArrayList<IntArray>()

        fun resetNextBeginningIndexes() {
            val nbi = prepared.nextBeginningIndexes ?: return
            for (k in changes.indices.reversed()) nbi[changes[k][0]] = changes[k][1]
        }

        var hasAtLeast1Match = false
        val allowPartialMatchScores = DoubleArray(searchesLen)

        for (i in 0 until searchesLen) {
            val search = searches[i]
            allowPartialMatchScores[i] = Double.NEGATIVE_INFINITY

            val wordResult = algorithm(search, prepared)
            result = wordResult

            if (allowPartialMatch) {
                if (wordResult == null) continue
                hasAtLeast1Match = true
            } else {
                if (wordResult == null) {
                    resetNextBeginningIndexes()
                    return null
                }
            }

            val isTheLastSearch = i == searchesLen - 1
            if (!isTheLastSearch) {
                val indexes = wordResult!!.indexes
                var consecutive = true
                for (k in 0 until indexes.size - 1) {
                    if (indexes[k + 1] - indexes[k] != 1) {
                        consecutive = false
                        break
                    }
                }

                if (consecutive) {
                    val nbi = prepared.nextBeginningIndexes!!
                    val newBeginningIndex = indexes[indexes.size - 1] + 1
                    val toReplace = nbi[newBeginningIndex - 1]
                    var k = newBeginningIndex - 1
                    while (k >= 0) {
                        if (toReplace != nbi[k]) break
                        nbi[k] = newBeginningIndex
                        changes.add(intArrayOf(k, toReplace))
                        k -= 1
                    }
                }
            }

            score += wordResult!!.score / searchesLen
            allowPartialMatchScores[i] = wordResult.score / searchesLen

            // Dock points when this word matched earlier in the target than the previous one.
            if (wordResult.indexes[0] < firstSeenIndexLastSearch) {
                score -= (firstSeenIndexLastSearch - wordResult.indexes[0]) * 2
            }
            firstSeenIndexLastSearch = wordResult.indexes[0]

            for (index in wordResult.indexes) seen.add(index)
        }

        if (allowPartialMatch && !hasAtLeast1Match) return null

        resetNextBeginningIndexes()

        // A query with spaces that is also an exact substring may score better as one run.
        val allowSpacesResult = algorithm(preparedSearch, prepared, allowSpaces = true)
        if (allowSpacesResult != null && allowSpacesResult.score > score) return allowSpacesResult

        val finalResult = if (allowPartialMatch) prepared else result!!
        finalResult.score = score
        finalResult.indexes = seen.toIntArray()
        return finalResult
    }
}
