package com.zapp.net.ui

/*
 * The line diff their edit rows use when the server sends no `metadata.files`.
 *
 * Source of truth, `route-BPCnB8DM.js` -> `ch(e)`:
 *
 *   edit : `Sd(oldString, newString)` = jsdiff `diffLines`, then
 *          `r = lines.filter(added).sum(count)`, `i = lines.filter(removed).sum(count)`
 *          and `r === 0 && i === 0` drops the row entirely.
 *   write: `additions = content.split("\n").length - Number(content.endsWith("\n"))`,
 *          `deletions = 0`, patch built from the whole content.
 *
 * jsdiff's `diffLines` is Myers over line tokens. This is the same shortest edit script,
 * unscaled, plus a unified patch so the row can expand into a real diff. The identical head and
 * tail are stripped before Myers runs, and a changed middle past 2 000 lines falls back to a
 * delete-all/add-all script - past that size a "minimal" script is not worth the memory.
 */

internal enum class LineOpKind { Equal, Add, Del }

internal data class LineOp(val kind: LineOpKind, val text: String)

/** JS `content.split("\n")` semantics, including the trailing-newline rule `ch(e)` uses. */
internal fun splitLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val parts = text.split("\n")
    return if (text.endsWith("\n")) parts.dropLast(1) else parts
}

/**
 * Line ops for an edit. The identical head and tail are stripped first - an edit almost always
 * differs in a small window, and Myers only needs that window. Without this a 2 000-line rewrite
 * would allocate a trace of 2 000 row snapshots just to report two changed lines.
 */
internal fun lineOps(a: List<String>, b: List<String>): List<LineOp> {
    var head = 0
    val lim = minOf(a.size, b.size)
    while (head < lim && a[head] == b[head]) head++
    var tail = 0
    while (tail < lim - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++

    val ops = ArrayList<LineOp>(a.size + b.size)
    for (i in 0 until head) ops.add(LineOp(LineOpKind.Equal, a[i]))
    ops.addAll(myers(a.subList(head, a.size - tail), b.subList(head, b.size - tail)))
    for (i in a.size - tail until a.size) ops.add(LineOp(LineOpKind.Equal, a[i]))
    return ops
}

private fun myers(a: List<String>, b: List<String>): List<LineOp> {
    val n = a.size
    val m = b.size
    if (n == 0) return b.map { LineOp(LineOpKind.Add, it) }
    if (m == 0) return a.map { LineOp(LineOpKind.Del, it) }
    // worst case here is ~8 MB of trace at 1 000+1 000 lines; past that, delete-all/add-all.
    if (n + m > 2000) {
        return a.map { LineOp(LineOpKind.Del, it) } + b.map { LineOp(LineOpKind.Add, it) }
    }

    val max = n + m
    val offset = max
    val v = IntArray(2 * max + 1)
    val trace = ArrayList<IntArray>(max + 1)
    var found = -1
    loop@ for (d in 0..max) {
        // A 401-deep edit script is not a line edit any more; bounding `d` bounds the trace to
        // ~6 MB no matter how large the two sides are.
        if (d > 400) break
        trace.add(v.copyOf())
        var k = -d
        while (k <= d) {
            val ki = k + offset
            var x = if (k == -d || (k != d && v[ki - 1] < v[ki + 1])) v[ki + 1] else v[ki - 1] + 1
            var y = x - k
            while (x < n && y < m && a[x] == b[y]) {
                x++
                y++
            }
            v[ki] = x
            if (x >= n && y >= m) {
                found = d
                break@loop
            }
            k += 2
        }
    }
    if (found < 0) {
        return a.map { LineOp(LineOpKind.Del, it) } + b.map { LineOp(LineOpKind.Add, it) }
    }

    val ops = ArrayList<LineOp>(n + m)
    var x = n
    var y = m
    for (d in found downTo 1) {
        val vd = trace[d]
        val k = x - y
        val ki = k + offset
        val prevK = if (k == -d || (k != d && vd[ki - 1] < vd[ki + 1])) k + 1 else k - 1
        val prevX = vd[prevK + offset]
        val prevY = prevX - prevK
        while (x > prevX && y > prevY) {
            ops.add(LineOp(LineOpKind.Equal, a[x - 1]))
            x--
            y--
        }
        if (x == prevX) {
            ops.add(LineOp(LineOpKind.Add, b[y - 1]))
            y--
        } else {
            ops.add(LineOp(LineOpKind.Del, a[x - 1]))
            x--
        }
    }
    while (x > 0 && y > 0) {
        ops.add(LineOp(LineOpKind.Equal, a[x - 1]))
        x--
        y--
    }
    while (x > 0) {
        ops.add(LineOp(LineOpKind.Del, a[x - 1]))
        x--
    }
    while (y > 0) {
        ops.add(LineOp(LineOpKind.Add, b[y - 1]))
        y--
    }
    ops.reverse()
    return ops
}

/** Unified diff with 3 lines of context, the shape `DiffView` already parses. */
internal fun unifiedPatch(path: String, ops: List<LineOp>, context: Int = 3): String {
    val clean = path.replace('\\', '/')
    val sb = StringBuilder()
    sb.append("--- a/").append(clean).append('\n')
    sb.append("+++ b/").append(clean).append('\n')
    if (ops.isEmpty()) return sb.toString()

    val oldNo = IntArray(ops.size + 1)
    val newNo = IntArray(ops.size + 1)
    var o = 1
    var n = 1
    for (i in ops.indices) {
        oldNo[i] = o
        newNo[i] = n
        when (ops[i].kind) {
            LineOpKind.Equal -> {
                o++
                n++
            }
            LineOpKind.Del -> o++
            LineOpKind.Add -> n++
        }
    }
    oldNo[ops.size] = o
    newNo[ops.size] = n

    val ranges = ArrayList<IntArray>()
    for (i in ops.indices) {
        if (ops[i].kind == LineOpKind.Equal) continue
        val s = maxOf(0, i - context)
        val e = minOf(ops.size, i + context + 1)
        val last = ranges.lastOrNull()
        if (last != null && s <= last[1]) {
            if (e > last[1]) last[1] = e
        } else {
            ranges.add(intArrayOf(s, e))
        }
    }

    for (r in ranges) {
        val s = r[0]
        val e = r[1]
        var oldCount = 0
        var newCount = 0
        for (i in s until e) {
            when (ops[i].kind) {
                LineOpKind.Equal -> {
                    oldCount++
                    newCount++
                }
                LineOpKind.Del -> oldCount++
                LineOpKind.Add -> newCount++
            }
        }
        sb.append("@@ -").append(oldNo[s]).append(',').append(oldCount)
        sb.append(" +").append(newNo[s]).append(',').append(newCount).append(" @@\n")
        for (i in s until e) {
            val op = ops[i]
            sb.append(
                when (op.kind) {
                    LineOpKind.Equal -> ' '
                    LineOpKind.Add -> '+'
                    LineOpKind.Del -> '-'
                },
            ).append(op.text).append('\n')
        }
    }
    return sb.toString()
}
