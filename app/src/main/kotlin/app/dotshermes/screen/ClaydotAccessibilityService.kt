package app.dotshermes.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.dotshermes.data.direct.PhoneActions
import app.dotshermes.data.direct.ScreenDriver
import app.dotshermes.data.direct.ScreenElement
import app.dotshermes.data.direct.ScreenSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Service d'accessibilité de Claydot : il ne fait rien de lui-même. Il ne réagit à aucun événement,
 * ne garde rien ; il sert seulement à lire l'écran et à toucher quand une tâche a été acceptée
 * (voir ScreenTools). L'utilisateur l'active lui-même dans les réglages d'accessibilité d'Android.
 */
class ClaydotAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this; _running.value = true }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean { stopped(); return super.onUnbind(intent) }
    override fun onDestroy() { stopped(); super.onDestroy() }
    private fun stopped() { if (instance === this) instance = null; _running.value = false }

    companion object {
        @Volatile internal var instance: ClaydotAccessibilityService? = null
        private val _running = MutableStateFlow(false)
        /** Activé dans les réglages Android (et branché). */
        val running: StateFlow<Boolean> = _running.asStateFlow()
    }
}

/** Le pilote réel : l'arbre d'accessibilité de la fenêtre active, réduit aux éléments utiles. */
class AccessibilityScreenDriver(private val context: Context, private val phone: PhoneActions) : ScreenDriver {
    /** Les nœuds de la dernière lecture, par numéro. */
    @Volatile private var nodes: Map<Int, AccessibilityNodeInfo> = emptyMap()

    private val service get() = ClaydotAccessibilityService.instance

    override fun connected() = service != null

    override fun snapshot(): ScreenSnapshot? {
        val root = service?.rootInActiveWindow ?: return null
        val found = LinkedHashMap<Int, AccessibilityNodeInfo>()
        val elements = ArrayList<ScreenElement>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || elements.size >= 200 || !node.isVisibleToUser) return
            val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString(), node.hintText?.toString()?.let { "hint: $it" })
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" · ")
            val role = when {
                node.isEditable -> "edit"
                node.isClickable || node.isCheckable -> if (node.isCheckable) (if (node.isChecked) "checked" else "unchecked") else "button"
                node.isScrollable -> "scroll"
                else -> "text"
            }
            if (text.isNotEmpty() || role != "text") {
                val id = elements.size + 1
                val r = Rect().also(node::getBoundsInScreen)
                elements += ScreenElement(id, if (node.isPassword) "" else text, role, "${r.left},${r.top},${r.right},${r.bottom}", node.isPassword)
                found[id] = node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        nodes = found
        return ScreenSnapshot(root.packageName?.toString().orEmpty(), elements)
    }

    override fun tap(id: Int): Boolean {
        val node = nodes[id] ?: return false
        // Le texte n'est souvent pas lui-même cliquable : on remonte au parent qui l'est.
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        val r = Rect().also(node::getBoundsInScreen)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return service?.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null) == true
    }

    override fun type(id: Int, text: String): Boolean {
        val node = nodes[id]?.takeIf { it.isEditable && !it.isPassword } ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun scroll(down: Boolean): Boolean {
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (nodes.values.firstOrNull { it.isScrollable }?.performAction(action) == true) return true
        // Rien de déclaré défilable : un glissement au milieu de l'écran.
        val metrics = context.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val (from, to) = if (down) metrics.heightPixels * 0.7f to metrics.heightPixels * 0.3f else metrics.heightPixels * 0.3f to metrics.heightPixels * 0.7f
        val path = Path().apply { moveTo(x, from); lineTo(x, to) }
        return service?.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null) == true
    }

    override fun press(key: String): Boolean {
        val action = when (key) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> return false
        }
        return service?.performGlobalAction(action) == true
    }

    override fun launch(name: String): String? {
        if (name.isEmpty()) return null
        val (label, intent) = phone.findApp(name) ?: return null
        return try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); label } catch (_: Exception) { null }
    }
}
