package com.pittech

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.hasWindowFocus
import androidx.test.espresso.matcher.ViewMatchers.isRoot

/** Editors and completion choices can own a dialog window above the Activity. */
internal fun closeFocusedSoftKeyboard() {
    onView(isRoot())
        .inRoot(withDecorView(hasWindowFocus()))
        .perform(closeSoftKeyboard())
}
