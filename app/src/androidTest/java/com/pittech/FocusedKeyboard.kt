package com.pittech

import android.app.Activity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Root
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import java.util.concurrent.TimeUnit

/** Editors and completion choices can own a dialog window above the Activity. */
internal fun closeFocusedSoftKeyboard() {
    onView(isRoot())
        .inRoot(object : TypeSafeMatcher<Root>() {
            override fun describeTo(description: Description) {
                description.appendText("a window with focus")
            }
            override fun matchesSafely(root: Root) = root.decorView.hasWindowFocus()
        })
        .perform(closeSoftKeyboard())
}

/**
 * [closeFocusedSoftKeyboard] only *requests* the hide: on a slow emulator
 * the IME is still animating out for a long while afterwards, and a back
 * press injected in that window is routed to the IME instead of the app —
 * the app never sees it. That is exactly how
 * CookNoteScreenTest.photoFirstCancel… lost its sheet-dismiss back press
 * (issue #316: the composer stayed open past the 15 s wait on both API 36
 * lanes, including on a freshly restarted emulator). Poll the real
 * condition — the activity window's IME inset no longer visible — so a
 * following back press deterministically reaches the app. Throws if the
 * keyboard is still visible when the budget runs out, so the failure
 * names the actual precondition instead of surfacing downstream.
 */
internal fun waitForSoftKeyboardHidden(activity: Activity, timeoutMillis: Long = 15_000) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (true) {
        var hidden = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
            hidden = insets == null || !insets.isVisible(WindowInsetsCompat.Type.ime())
        }
        if (hidden) return
        if (System.nanoTime() >= deadline) {
            throw AssertionError("The soft keyboard was still visible after ${timeoutMillis} ms; a back press now would be routed to the IME instead of the app.")
        }
        Thread.sleep(50)
    }
}
