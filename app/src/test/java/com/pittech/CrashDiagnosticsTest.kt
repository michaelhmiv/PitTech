package com.pittech

import android.app.ApplicationExitInfo
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashDiagnosticsTest {
    @Test
    fun ignoresSystemExitReasonsThatAreNotAppProblems() {
        assertFalse(CrashDiagnostics.isUnexpectedExit(ApplicationExitInfo.REASON_OTHER, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertFalse(CrashDiagnostics.isUnexpectedExit(ApplicationExitInfo.REASON_USER_REQUESTED, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertFalse(CrashDiagnostics.isUnexpectedExit(ApplicationExitInfo.REASON_SIGNALED, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
    }

    @Test
    fun reportsActionableAppExitReasons() {
        assertTrue(CrashDiagnostics.isUnexpectedExit(ApplicationExitInfo.REASON_CRASH, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertTrue(CrashDiagnostics.isUnexpectedExit(ApplicationExitInfo.REASON_ANR, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
    }
}
