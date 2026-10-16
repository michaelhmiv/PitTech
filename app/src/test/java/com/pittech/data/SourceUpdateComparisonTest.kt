package com.pittech.data

import com.pittech.domain.RecipeSnapshotCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SourceUpdateComparisonTest {
    @Test fun sharedSourceReviewDetectsReorderingWithoutInventingAnIngredientChange() {
        val fixture=JSONObject(checkNotNull(javaClass.getResourceAsStream("/source-review-v1.json")).bufferedReader().use { it.readText() })
        val snapshot=RecipeSnapshotCodec.decode(fixture.getJSONObject("snapshot").toString());val shared=fixture.getJSONObject("shared");val sections=SourceUpdateComparison.sections(snapshot,shared)
        assertEquals((0 until fixture.getJSONArray("expectedChanged").length()).map { fixture.getJSONArray("expectedChanged").getBoolean(it) },sections.map { it.changed });assertEquals(listOf("Salt · 2 tsp"),sections[0].saved)
        assertTrue(sections[1].saved[0].contains("Wrap"));assertTrue(sections[1].shared[0].contains("Finish"))
        val malformed=JSONObject(shared.toString());malformed.getJSONArray("ingredients").getJSONObject(0).put("name","x".repeat(161))
        assertThrows(IllegalArgumentException::class.java) { SourceUpdateComparison.sections(snapshot,malformed) }
        val hugeOffset=JSONObject(shared.toString());hugeOffset.getJSONArray("instructions").getJSONObject(0).put("suggestedOffsetMillis",Long.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { SourceUpdateComparison.sections(snapshot,hugeOffset) }
        assertEquals("Wrap",snapshot.instructions.first().title)
    }
}
