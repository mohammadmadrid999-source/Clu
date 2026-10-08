package com.clu.motion.input

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the accessibility-service declaration. isAccessibilityTool="true" is load-bearing on
 * Android 16: without it, injected gestures are dropped at views marked accessibility-data
 * sensitive (including every view using filterTouchesWhenObscured), the service can't be
 * enabled during calls with unknown numbers, and PermissionController nags users to remove it.
 */
class AccessibilityConfigTest {
    private val attrs = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/main/res/xml/input_dispatcher_service.xml"))
        .documentElement
        .let { root -> { name: String -> root.getAttributeNS(ANDROID_NS, name) } }

    @Test
    fun declaresAnAccessibilityToolThatCanInjectAndFilterKeys() {
        assertEquals("true", attrs("isAccessibilityTool"))
        assertEquals("true", attrs("canPerformGestures"))
        assertEquals("true", attrs("canRequestFilterKeyEvents"))
    }

    @Test
    fun neverReadsScreenContent() {
        assertEquals("false", attrs("canRetrieveWindowContent"))
        assertEquals("typeWindowStateChanged", attrs("accessibilityEventTypes"))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
