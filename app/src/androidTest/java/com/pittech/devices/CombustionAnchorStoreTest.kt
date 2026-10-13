package com.pittech.devices

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CombustionAnchorStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun anchorPersistsAcrossStoreInstancesAndContainsNoRawProbeIdentity() {
        val key = TelemetryNormalizer.localKey("combustion", "CCABC1", "41")
        val anchor = CombustionTimeAnchor(41, 100, 1_760_000_000_000L, 5000)
        FileCombustionAnchorStore(context).save(key, anchor)
        assertEquals(anchor, FileCombustionAnchorStore(context).get(key))
        val content = File(context.noBackupFilesDir, "combustion-time-anchors.json").readText()
        assertFalse(content.contains("CCABC1"))
        assertTrue(content.contains(key))
    }

    @Test fun corruptTimingMetadataIsDiscardedInsteadOfCreatingHistory() {
        val file = File(context.noBackupFilesDir, "combustion-time-anchors.json")
        file.writeText("[{\"key\":\"not-a-hash\",\"session\":41,\"sequence\":100,\"time\":1,\"period\":0}]")
        assertNull(FileCombustionAnchorStore(context).get("not-a-hash"))
        assertFalse(file.exists())
    }
}
