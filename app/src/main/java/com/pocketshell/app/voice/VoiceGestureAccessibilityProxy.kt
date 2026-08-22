package com.pocketshell.app.voice

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Native accessibility surface for the combined composer launcher gesture.
 *
 * Compose's semantics tree remains the source used by Compose tests and by
 * screen-reader integrations when their bridge is active. The platform
 * accessibility provider on the Android test/runtime path can, however,
 * expose a virtual Compose node with the label but omit its actions when no
 * spoken-feedback service owns the connection. A real Android child gives the
 * platform an unambiguous clickable node and keeps the contract operable in
 * that configuration too.
 *
 * This view deliberately does not handle touch input. The sibling Compose
 * pointer detector remains the sole owner of the physical tap/swipe sequence
 * (#585); the proxy exists only for the accessibility node/action contract.
 */
@Composable
internal fun VoiceGestureAccessibilityProxy(
    enabled: Boolean,
    onClick: () -> Unit,
    onDictation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnClick = rememberUpdatedState(onClick)
    val currentOnDictation = rememberUpdatedState(onDictation)
    AndroidView(
        factory = { context ->
            VoiceGestureAccessibilityProxyView(context)
        },
        modifier = modifier,
        update = { view ->
            view.setActions(
                enabled = enabled,
                onClick = { currentOnClick.value() },
                onDictation = { currentOnDictation.value() },
            )
        },
    )
}

private class VoiceGestureAccessibilityProxyView(
    context: Context,
) : Button(context) {

    private var actionsEnabled = false
    private var onCompose: (() -> Unit)? = null
    private var onDictation: (() -> Unit)? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        // The proxy has no visual content. Its bounds deliberately match the
        // real launcher, so accessibility focus lands on the visible control.
        setWillNotDraw(true)
        background = null
        text = null
        setPadding(0, 0, 0, 0)
        minWidth = 0
        minHeight = 0
        contentDescription = SESSION_COMPOSER_LAUNCHER_CONTENT_DESCRIPTION
        setOnClickListener { onCompose?.invoke() }
    }

    fun setActions(
        enabled: Boolean,
        onClick: () -> Unit,
        onDictation: () -> Unit,
    ) {
        val changed = actionsEnabled != enabled
        actionsEnabled = enabled
        isEnabled = enabled
        isClickable = enabled
        isFocusable = enabled
        onCompose = onClick
        this.onDictation = onDictation
        if (changed) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = false

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
        info.contentDescription = SESSION_COMPOSER_LAUNCHER_CONTENT_DESCRIPTION
        info.isEnabled = actionsEnabled
        info.isClickable = actionsEnabled
        if (actionsEnabled) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    VOICE_GESTURE_NATIVE_DICTATION_ACTION_ID,
                    VOICE_GESTURE_DICTATION_ACTION_LABEL,
                ),
            )
        }
    }

    override fun performAccessibilityAction(
        action: Int,
        arguments: android.os.Bundle?,
    ): Boolean {
        if (!actionsEnabled) return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> {
                onCompose?.invoke()
                true
            }
            VOICE_GESTURE_NATIVE_DICTATION_ACTION_ID -> {
                onDictation?.invoke()
                true
            }
            else -> super.performAccessibilityAction(action, arguments)
        }
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.className = android.widget.Button::class.java.name
        event.isEnabled = actionsEnabled
    }
}

/** Android's custom action range is the high-byte custom-action type. */
internal const val VOICE_GESTURE_NATIVE_DICTATION_ACTION_ID: Int = 0x01000000 + 1753
