package com.taksi.autoaccept.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.taksi.autoaccept.core.rules.AcceptLabelMatcher
import com.taksi.autoaccept.core.rules.LabelMatch
import java.util.ArrayDeque
import java.util.Locale

/** Erisilebilirlik dugum agacini okuyup metin ve dugme bulmaya yarayan yardimcilar. */
object NodeScanner {

    private val TR: Locale = Locale.forLanguageTag("tr")
    private const val MAX_NODES = 1500
    private const val MAX_DEPTH = 60
    private const val DESCENDANT_LOOKUP = 24

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
     * Iki yoldan birden aranir:
     *  1. Cercevenin kendi metin aramasi ([AccessibilityNodeInfo.findAccessibilityNodeInfosByText]).
     *     Agac ne kadar buyuk olursa olsun dugumu bulur; bizim gezintimizin
     *     dugum siniri kalabalik ekranlarda dugmeyi kacirabiliyordu.
     *  2. Agaci kendimiz gezeriz: icerik aciklamasindan gelen ya da cerceve
     *     aramasinin harf esitligi yuzunden atladigi dugumler icin.
     *
     * Adaylar arasindan once ekranda gorunen, sonra birebir eslesen secilir.
     * Metin tasiyan dugum tiklanabilir degilse tiklanabilir bir ust dugum
     * aranir; o da yoksa dokunma noktasi icin dugumun ekran koordinati kullanilir.
     */
    fun findAcceptTarget(
        root: AccessibilityNodeInfo?,
        labels: List<String>
    ): AcceptTarget? {
        if (root == null || labels.isEmpty()) return null

        val candidates = ArrayList<Scored>()

        fun consider(node: AccessibilityNodeInfo) {
            val label = (node.text?.toString() ?: node.contentDescription?.toString())
                ?.trim()?.lowercase(TR)
                ?: return
            if (label.isEmpty()) return
            val kind = AcceptLabelMatcher.match(label, labels)
            if (kind == LabelMatch.NONE) return
            val target = toTarget(node, label) ?: return
            candidates.add(Scored(target, kind, node.isVisibleToUser))
        }

        for (label in labels) {
            val text = label.trim()
            if (text.isEmpty()) continue
            runCatching { root.findAccessibilityNodeInfosByText(text) }
                .getOrNull()
                .orEmpty()
                .forEach { node -> node?.let { consider(it) } }
        }

        forEachNode(root) { consider(it) }

        return candidates.minWithOrNull(
            compareBy<Scored>(
                { if (it.visible) 0 else 1 },
                { if (it.kind == LabelMatch.EXACT) 0 else 1 }
            )
        )?.target
    }

    private data class Scored(
        val target: AcceptTarget,
        val kind: LabelMatch,
        val visible: Boolean
    )

    /**
     * Ekrandaki tiklanabilir ogelerin yazilari.
     *
     * "Kabul dugmesi bulunamadi" kaydinda ise yarayan tek bilgi budur:
     * dugmede gercekte ne yazdigini gosterir, kullanici da ayara onu girer.
     */
    fun clickableLabels(root: AccessibilityNodeInfo?, limit: Int = 8): List<String> {
        if (root == null) return emptyList()
        val out = LinkedHashSet<String>()
        forEachNode(root) { node ->
            if (out.size >= limit) return@forEachNode
            if (!node.isClickable || !node.isEnabled) return@forEachNode
            val label = ownText(node) ?: firstDescendantText(node)
            if (!label.isNullOrBlank()) out.add(label.trim().take(40))
        }
        return out.toList()
    }

    private fun ownText(node: AccessibilityNodeInfo): String? =
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    /** Tiklanabilir dugumun yazisi cocuklarindaysa onu getirir. */
    private fun firstDescendantText(node: AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < DESCENDANT_LOOKUP) {
            val current = queue.poll() ?: continue
            visited++
            if (current !== node) ownText(current)?.let { return it }
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * Dokunulacak hedefi hazirlar.
     *
     * Dokunma noktasi icin once yazinin kendi alani kullanilir: tiklanabilir
     * ust dugum bazen kartin tamami oluyor ve onun ortasina dokunmak "Kabul
     * et" yerine kartin bambaska bir yerine denk gelebilir. Yazinin uzeri her
     * zaman dugmenin icindedir.
     *
     * Sinirlari bos olan bir dugum tiklanabilirse yine ise yarar: o zaman
     * dokunma yerine dugum eyleminden basariz. Eskiden bos sinir hedefi
     * tumden eliyordu.
     */
    private fun toTarget(node: AccessibilityNodeInfo, label: String): AcceptTarget? {
        val clickable = clickableSelfOrAncestor(node)
        val own = Rect().also { node.getBoundsInScreen(it) }
        val bounds = if (own.width() > 0 && own.height() > 0) {
            own
        } else {
            Rect().also { (clickable ?: node).getBoundsInScreen(it) }
        }
        val tappable = bounds.width() > 0 && bounds.height() > 0
        if (clickable == null && !tappable) return null
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

    /** Verilen dugumden yukari cikarak icinde bulundugu pencerenin kokunu bulur. */
    fun rootOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        var depth = 0
        while (depth < MAX_DEPTH) {
            val parent = current.parent ?: return current
            current = parent
            depth++
        }
        return current
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
