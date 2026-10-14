package com.pittech

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Root
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher

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
