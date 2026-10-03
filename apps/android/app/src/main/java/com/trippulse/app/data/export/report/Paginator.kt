package com.trippulse.app.data.export.report

/** A finished report, ready to be paged and drawn. */
class Report(
    /** File name without extension. */
    val fileLabel: String,
    /** Shown small at the top of every page after the first. */
    val runningTitle: String,
    /** Footer, left: "Journey TP-7235 1576 · Prepared 3 Oct, 1:42 AM". */
    val footer: String,
    val blocks: List<Block>,
    /** The document's title, for share sheets. */
    val title: String
)

/**
 * Places blocks on A4 pages: keeps headings with what follows, repeats a
 * ledger's header on the page it continues onto, never starts a page with
 * blank space, and numbers pages "2 of 3".
 */
object Paginator {
    const val PAGE_W = 595f
    const val PAGE_H = 842f
    const val MARGIN = 40f
    /** Where content starts on pages after the first (below the running header). */
    private const val CONT_TOP = 70f
    /** Where content must end (above the footer). */
    private const val BOTTOM = PAGE_H - 50f

    data class Placement(val block: Block, val y: Float)

    fun paginate(s: Surface, report: Report): List<List<Placement>> {
        val w = PAGE_W - MARGIN * 2
        fun h(b: Block) = b.height(s, if (b.fullBleed) PAGE_W else w)
        val pages = ArrayList<MutableList<Placement>>()
        var cur = ArrayList<Placement>()
        var y = 0f
        var top = 0f
        fun newPage() { pages += cur; cur = ArrayList(); y = CONT_TOP; top = CONT_TOP }

        val blocks = report.blocks
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            if (b.newPage && cur.isNotEmpty()) newPage()
            if (b.isSpace && y == top && pages.isNotEmpty()) { i++; continue }
            var need = h(b)
            // Keep a heading with the first thing under it (and that with its own follower).
            var j = i
            while (blocks[j].keepWithNext && j + 1 < blocks.size) { j++; need += h(blocks[j]) }
            if (y + need > BOTTOM && y > top) {
                newPage()
                b.repeatHeader?.let { hd -> cur += Placement(hd, y); y += h(hd) }
            }
            cur += Placement(b, y)
            y += h(b)
            i++
        }
        if (cur.isNotEmpty()) pages += cur
        return pages
    }

    /** Draws page [index] of [pages]. The caller gives a fresh, white page surface. */
    fun drawPage(s: Surface, report: Report, pages: List<List<Placement>>, index: Int) {
        val w = PAGE_W - MARGIN * 2
        s.rect(0f, 0f, PAGE_W, PAGE_H, Ink.WHITE)
        if (index > 0) runningHeader(s, report)
        for (p in pages[index]) {
            if (p.block.fullBleed) p.block.draw(s, 0f, p.y, PAGE_W) else p.block.draw(s, MARGIN, p.y, w)
        }
        footer(s, report, index + 1, pages.size)
    }

    private fun runningHeader(s: Surface, report: Report) {
        s.mark(MARGIN, 24f, 15f)
        s.text("Koode", MARGIN + 20f, 35.5f, TextStyle(Face.HEAD, 10f, Ink.NIGHT, 700))
        val t = fit(s, report.runningTitle, Type.small, PAGE_W - MARGIN * 2 - 120f)
        s.text(t, PAGE_W - MARGIN - s.measure(t, Type.small), 35f, Type.small)
        s.line(MARGIN, 48f, PAGE_W - MARGIN, 48f, Ink.RULE, 0.8f)
    }

    private fun footer(s: Surface, report: Report, n: Int, total: Int) {
        val y = PAGE_H - 26f
        s.line(MARGIN, y - 14f, PAGE_W - MARGIN, y - 14f, Ink.RULE, 0.6f)
        s.mark(MARGIN, y - 8.5f, 11f, alpha = 0.9f)
        val left = fit(s, "Koode · ${report.footer}", Type.tiny, PAGE_W - MARGIN * 2 - 90f)
        s.text(left, MARGIN + 16f, y, Type.tiny)
        val right = "Page $n of $total"
        s.text(right, PAGE_W - MARGIN - s.measure(right, Type.tiny), y, Type.tiny)
    }
}
