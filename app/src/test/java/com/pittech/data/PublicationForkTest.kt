package com.pittech.data

import com.pittech.domain.RecipeSnapshot
import com.pittech.domain.RecipeSnapshotCodec
import com.pittech.domain.SharingDraftCodec
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Writer-faithful seam test for the Slice 5 fork (element 4.2): the
 * fixture's detail is the server's real response shape and its
 * packBase64 is the server's real pack builder output for that same
 * detail. Forking the detail on device must land in the pack's end
 * state — decoded snapshots equal field for field (fresh identities
 * aside), the frozen story semantically equal, the companion record
 * shaped so the guide pin finds every entry.
 */
class PublicationForkTest {
    private fun fixture(): JSONObject =
        JSONObject(checkNotNull(javaClass.getResourceAsStream("/public-native-v1.json")).bufferedReader().use { it.readText() })

    private fun packData(fixture: JSONObject): JSONObject {
        val zip = ZipInputStream(ByteArrayInputStream(Base64.getDecoder().decode(fixture.getString("packBase64"))))
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name == "data/pittech.json") return JSONObject(zip.readBytes().toString(Charsets.UTF_8))
            zip.closeEntry()
        }
        fail("The fixture pack carries no data document.")
        error("unreachable")
    }

    private fun normalized(snapshot: RecipeSnapshot): RecipeSnapshot = snapshot.copy(
        instructions = snapshot.instructions.map { it.copy(id = "x") },
        observations = snapshot.observations.map { it.copy(sourceEventId = "x") },
    )

    /** Strip the identities each side mints fresh, and drop null-valued
     * keys (the pack's serializer omits undefined the same way), so two
     * story documents can be compared on their recorded facts. */
    private fun normalizeStory(raw: Any?): Any? = when (raw) {
        is JSONObject -> {
            val out = JSONObject()
            val keys = raw.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key in setOf("sourceEventId", "probeId") || raw.isNull(key)) continue
                if (key == "id" && raw.optString("measurementType").isNotEmpty()) continue
                out.put(key, normalizeStory(raw.get(key)))
            }
            out
        }
        is JSONArray -> JSONArray((0 until raw.length()).map { normalizeStory(raw.get(it)) })
        else -> raw
    }

    @Test fun forkLandsInThePackEndState() {
        val fixture = fixture()
        val detail = PublicDetail.parse(fixture.getJSONObject("detail"))
        val pack = packData(fixture)
        val prepared = PublicationFork.prepare(detail, PublicationFork.untweaked(detail), now = 1_700_000_000_000L)
        val fork = prepared.snapshot

        // Saved recipes + revisions: same names, notes, and snapshots.
        val packRecipes = pack.getJSONArray("recipes")
        val packRevisions = pack.getJSONArray("recipeRevisions")
        assertEquals(packRecipes.length(), fork.recipes.size)
        assertEquals(packRevisions.length(), fork.recipeRevisions.size)
        for (i in 0 until packRecipes.length()) {
            assertEquals(packRecipes.getJSONObject(i).getString("name"), fork.recipes[i].name)
            assertEquals(packRecipes.getJSONObject(i).getString("description"), fork.recipes[i].description)
            assertEquals(packRecipes.getJSONObject(i).getString("tagsJson"), fork.recipes[i].tagsJson)
            assertEquals(false, fork.recipes[i].favorite)
            val packRevision = packRevisions.getJSONObject(i)
            assertEquals(1, fork.recipeRevisions[i].revisionNumber)
            assertEquals(1, fork.recipeRevisions[i].snapshotSchemaVersion)
            assertEquals(packRevision.getString("changeNotes"), fork.recipeRevisions[i].changeNotes)
            val packSnapshot = RecipeSnapshotCodec.decode(packRevision.getString("snapshotJson"))
            val forkSnapshot = RecipeSnapshotCodec.decode(fork.recipeRevisions[i].snapshotJson)
            assertEquals("recipe $i snapshot", normalized(packSnapshot), normalized(forkSnapshot))
            assertEquals(fork.recipes[i].id, fork.recipeRevisions[i].recipeId)
            assertEquals(fork.recipeRevisions[i].id, fork.recipes[i].currentRevisionId)
        }

        // Photos: one recipe asset + one companion photo per payload asset.
        val packAssets = pack.getJSONArray("recipeAssets")
        val packPhotos = pack.getJSONArray("companionPhotos")
        assertEquals(packAssets.length(), fork.recipeAssets.size)
        assertEquals(packPhotos.length(), fork.companionPhotos.size)
        fork.recipeAssets.forEach { asset ->
            assertEquals("image/jpeg", asset.mimeType)
            assertTrue(asset.relativeFilePath.matches(Regex("photos/${Regex.escape(asset.id)}\\.[a-z0-9]+")))
            assertTrue(prepared.photoMedia.containsKey(asset.id))
        }
        fork.companionPhotos.forEach { photo ->
            assertTrue(prepared.photoMedia.containsKey(photo.id))
            assertEquals(fork.companionRecords.single().id, photo.recordId)
        }

        // The companion record: same kind/title, entries pin-able, and
        // each entry's frozen story semantically the pack's own.
        val packRecord = pack.getJSONArray("companionRecords").getJSONObject(0)
        val forkRecord = fork.companionRecords.single()
        assertEquals(packRecord.getString("kind"), forkRecord.kind)
        assertEquals(packRecord.getString("title"), forkRecord.title)
        val packPayload = packRecord.getJSONObject("payload")
        val forkPayload = JSONObject(forkRecord.payload)
        val decoded = SharingDraftCodec.decode(forkRecord.payload) // must not throw
        assertEquals(packPayload.getJSONArray("entries").length(), decoded.entries.size)
        val packEntries = packPayload.getJSONArray("entries")
        val forkEntries = forkPayload.getJSONArray("entries")
        for (i in 0 until packEntries.length()) {
            val packEntry = packEntries.getJSONObject(i)
            val forkEntry = forkEntries.getJSONObject(i)
            assertEquals(packEntry.getBoolean("includeStory"), forkEntry.getBoolean("includeStory"))
            assertEquals(fork.recipeRevisions[i].id, forkEntry.getString("sourceRevisionId"))
            assertEquals(forkEntry.getString("sourceRevisionId"), decoded.entries[i].sourceRevisionId)
            assertTrue(
                "entry $i story",
                SharingDraftCodec.sameStory(
                    normalizeStory(packEntry.getJSONObject("story")).toString(),
                    normalizeStory(forkEntry.getJSONObject("story")).toString(),
                ),
            )
            // The story the record carries must seed the plan lane the
            // pack's story seeds: identical totals from the frozen facts.
            val forkSnapshot = RecipeSnapshotCodec.decode(forkEntry.getString("snapshotJson"))
            val seeded = com.pittech.domain.PlanSeeding.fromSnapshotStory(forkSnapshot)
            if (i == 0) {
                assertTrue(seeded != null)
                assertEquals(460L, seeded!!.schedule.cookMaxMinutes) // 27,600,000 ms food-on → food-off
            } else {
                assertTrue(seeded == null)
            }
        }
        // The pack's hero photo mapping survives: the payload names one.
        assertEquals(packPayload.getString("heroPhotoId").isNotEmpty(), forkPayload.has("heroPhotoId"))
        if (forkPayload.has("heroPhotoId")) {
            assertTrue(fork.companionPhotos.any { it.id == forkPayload.getString("heroPhotoId") })
        }
    }

    @Test fun tweaksLandOnTheSnapshotAndTheChangeNote() {
        val detail = PublicDetail.parse(fixture().getJSONObject("detail"))
        val tweaks = PublicationFork.untweaked(detail).toMutableList()
        tweaks[0] = PublicationFork.Tweak(
            name = "Oak-smoked pork shoulder — my version",
            rub = "House rub",
            wrap = PublicationFork.WrapChoice.FOIL,
            targetF = 165.0,
        )
        val prepared = PublicationFork.prepare(detail, tweaks, now = 1_700_000_000_000L)
        val snapshot = RecipeSnapshotCodec.decode(prepared.snapshot.recipeRevisions[0].snapshotJson)
        assertEquals("Oak-smoked pork shoulder — my version", snapshot.name)
        assertEquals("Oak-smoked pork shoulder", snapshot.dish.name) // the dish keeps its name
        assertEquals("House rub", snapshot.dish.rub)
        val wrap = snapshot.instructions.first { it.action == "wrap" }
        assertEquals("Foil", wrap.method)
        assertEquals(1, snapshot.targets.size)
        assertEquals("personal_finish", snapshot.targets[0].type)
        assertEquals(165.0, snapshot.targets[0].value, 0.0)
        val notes = prepared.snapshot.recipeRevisions[0].changeNotes
        assertTrue(notes.startsWith("Fork of Weekend smoke"))
        assertTrue(notes.contains("Rub: House rub"))
        assertTrue(notes.contains("Wrap: Foil"))
        assertTrue(notes.contains("Target: 165°F"))
        // The untouched second recipe keeps the pack-parity note.
        assertEquals("Saved public recipe", prepared.snapshot.recipeRevisions[1].changeNotes)
        // Frozen facts are not tweaked: the story still pins the source.
        assertEquals(detail.id, snapshot.publicSource!!.itemId)
        assertEquals(3, snapshot.observations.size)
    }

    @Test fun prefillTargetComesFromTheLastRecordedFoodReading() {
        val detail = PublicDetail.parse(fixture().getJSONObject("detail"))
        val first = PublicationFork.prefill(detail, 0)
        assertEquals("Oak-smoked pork shoulder — my version", first.name)
        assertTrue(first.targetFromStory)
        assertEquals(162.0, first.targetF!!, 0.0) // 72.2 °C, the last food reading
        val second = PublicationFork.prefill(detail, 1)
        assertFalse(second.targetFromStory)
        assertTrue(second.targetF == null)
    }

    @Test fun aStoryNamingAMissingChannelFailsTheFork() {
        val fixture = fixture()
        val detailJson = JSONObject(fixture.getJSONObject("detail").toString())
        val story = detailJson.getJSONObject("payload").getJSONArray("recipes").getJSONObject(0).getJSONObject("story")
        story.put("channels", JSONArray()) // measurements now name a channel the story does not carry
        val detail = PublicDetail.parse(detailJson)
        try {
            PublicationFork.prepare(detail, PublicationFork.untweaked(detail), now = 1L)
            fail("A story whose measurements name a missing channel must fail, like the pack builder's 422.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("channel"))
        }
    }
}
