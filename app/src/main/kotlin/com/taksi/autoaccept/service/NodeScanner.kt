package com.taksi.autoaccept.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale

/** Erisilebilirlik dugum agacini okuyup metin ve dugme bulmaya yarayan yardimcilar. */
object NodeScanner {

    private val TR: Locale = Locale.forLanguageTag("tr")
    private const val MAX_NODES = 800

    /** Agactaki gorunur metinleri, ekrandaki sirayla toplar. */
    fun collectTexts(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val out = ArrayList<String>()
        forEachNode(root) { node ->
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) out.add(text)
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty() && desc != text) out.add(desc)
        }
        return out
    }

    /**
     * Verilen etiketlerden birine uyan kabul dugmesini bulur.
     *
     * Once tam eslesmeyi dener ("Kabul Et"), sonra icerme eslesmesini
     * ("Kabul Et · 145 TL"). Metin tasiyan dugum tiklanabilir degilse
     * tiklanabilir bir ust dugum aranir; o da yoksa dokunma noktasi icin
     * dugumun ekran koordinati dondurulur.
     */
    fun findAcceptTarget(
        root: AccessibilityNodeInfo?,
        labels: List<String>
    ): AcceptTarget? {
        if (root == null || labels.isEmpty()) return null
        val normalized = labels.map { it.trim().lowercase(TR) }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return null

        var exactMatch: AcceptTarget? = null
        var partialMatch: AcceptTarget? = null

        forEachNode(root) { node ->
            if (exactMatch != null) return@forEachNode
            if (!node.isVisibleToUser) return@forEachNode
            val label = (node.text?.toString() ?: node.contentDescription?.toString())
                ?.trim()?.lowercase(TR)
                ?: return@forEachNode
            if (label.isEmpty()) return@forEachNode

            val isExact = normalized.any { it == label }
            val isPartial = !isExact && normalized.any { label.contains(it) }
            if (!isExact && !isPartial) return@forEachNode

            val target = toTarget(node, label) ?: return@forEachNode
            if (isExact) {
                exactMatch = target
            } else if (partialMatch == null) {
                partialMatch = target
            }
        }

        return exactMatch ?: partialMatch
    }

    private fun toTarget(node: AccessibilityNodeInfo, label: String): AcceptTarget? {
        val clickable = clickableSelfOrAncestor(node)
        val bounds = Rect().also { (clickable ?: node).getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        return AcceptTarget(node = clickable, bounds = bounds, label = label)
    }

    /** Dugum tiklanabilir degilse en fazla 5 kademe yukari cikip tiklanabilir ata arar. */
    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            depth++
        }
        return null
    }

    /** Agaci genislik oncelikli gezer; kotu bicimli agaclarda dugum sayisini sinirlar. */
    private inline fun forEachNode(root: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.poll() ?: continue
            visited++
            action(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    /**
     * @param node   tiklanabilir dugum; null ise sadece dokunma jesti kullanilabilir
     * @param bounds ekran uzerindeki alan, jest icin
     */
    data class AcceptTarget(
        val node: AccessibilityNodeInfo?,
        val bounds: Rect,
        val label: String
    )
}
