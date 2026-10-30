package com.pittech

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.*
import com.pittech.domain.RecipeSnapshotCodec
import com.pittech.ui.PitTechTheme
import com.pittech.ui.PublicDiscoveryScreen
import com.pittech.ui.ServePlanDialogs
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * Flowing Slice 5 gate evidence (§6, Slice 5 row): the chain as one
 * flow test — Discover → star → fork → tweak → plan-this → start
 * time shown. The world is the house hermetic pattern (compare
 * PublicationUserFlowTest): an in-memory library, the writer-faithful
 * public fixture behind a fake PublicAPI, a signed-in account vault,
 * and a recording PublicationAPI so the star's write is observed, not
 * assumed. The fork handler mirrors CooksViewModel.forkPublication's
 * glue (prepare → verified photo bytes → importSnapshot) against the
 * test manager; plan-this runs the real CooksViewModel.planRecipe.
 */
@RunWith(AndroidJUnit4::class) class FlowingHappyPathFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class RecordingPublicationAPI : PublicationAPI {
        val writes = mutableListOf<Pair<String, String>>()
        override suspend fun read(path: String, access: String): JSONObject = JSONObject()
        override suspend fun write(operation: PublicationOperation, file: File, access: String): JSONObject {
            writes += operation.method to operation.path
            // The writer's finish() contract for a like: the response
            // names the item and carries the new liked state.
            return JSONObject().put("id", operation.path.split('/')[3]).put("liked", operation.method == "POST")
                .put("likes", 13).put("forks", 3)
        }
    }

    @Test fun discoverStarForkTweakPlanThisShowsAStartTime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = JSONObject(context.assets.open("public-native-v1.json").bufferedReader().use { it.readText() })
        val detail = fixture.getJSONObject("detail")
        val id = detail.getString("id")
        // The plan lookup reads the application's own plan store;
        // clear it so no earlier lane's plan can absorb this lane.
        val app = context.applicationContext as PitTechApplication
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        val database = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val storage = PhotoStorage(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = CookRepository(database, storage)
        val transfer = PitTechDataTransfer(context, repository)
        val viewModel = CooksViewModel(repository, transfer, context)
        val publicAPI = object : PublicAPI {
            override suspend fun json(path: String) =
                fixture.getJSONObject(if (path.startsWith("/v1/discover?")) "feed" else "detail").toString().toByteArray()
            override suspend fun pack(id: String, revision: String, to: File): File = error("The fork downloads no pack")
            override suspend fun image(id: String, asset: JSONObject) = Base64.getDecoder().decode(fixture.getString("imageBase64"))
        }
        val namespace = "happy-path-" + UUID.randomUUID().toString()
        val manager = PublicManager(context, scope, { _, _, _, _ -> error("No pack preview in this chain") }, publicAPI, true,
            PublicCache(context, namespace),
            onFork = { forkDetail, tweaks ->
                val prepared = PublicationFork.prepare(forkDetail, tweaks, System.currentTimeMillis())
                val bytesByMedia = mutableMapOf<String, ByteArray>()
                val attachments = mutableMapOf<String, ByteArray>()
                for ((key, media) in prepared.photoMedia) {
                    val mediaId = media.getString("id")
                    attachments[key] = bytesByMedia.getOrPut(mediaId) { publicAPI.image(forkDetail.id, media) }
                }
                repository.importSnapshot(prepared.snapshot, attachments)
                prepared.saved
            },
            onPlanRecipe = { recipeId, revisionId -> viewModel.planRecipe(recipeId, revisionId) })
        val vault = object : AccountStorage {
            var state = AccountState(session = AccountSession("A".repeat(43), "A".repeat(43), "2100-01-01T00:00:00.000Z", "2100-02-01T00:00:00.000Z", "12345678-1234-4234-8234-123456789abc"))
            override fun load() = state
            override fun save(value: AccountState) { state = value }
        }
        val account = AccountManager(vault, scope, AccountAPI { _, _, _ -> error("No authentication request expected") }, true)
        val publicationAPI = RecordingPublicationAPI()
        val writerRoot = File(context.noBackupFilesDir, "happy-path-writer-" + UUID.randomUUID())
        val writer = PublicationWriter(AccountPublicationSession(account), PublicationOperationsStore(writerRoot), publicationAPI)
        try {
            compose.setContent {
                PitTechTheme {
                    Column {
                        PublicDiscoveryScreen(manager, Modifier, "°F", account, writer, null) { }
                        ServePlanDialogs(viewModel)
                    }
                }
            }
            // Discover: the card browses by photo, time and temp slots —
            // the fixture's story carries a duration (7H 40M) and no pit
            // channel, so time shows and temp honestly doesn't.
            waitFor("discover-open-$id")
            compose.waitUntil(15000) { compose.onAllNodesWithText("7H 40M", substring = true).fetchSemanticsNodes().isNotEmpty() }
            shot("public-card")
            compose.onNodeWithTag("discover-open-$id").performScrollTo().performClick()
            waitFor("public-title")

            // Star: the placard records the like under its spec name.
            waitFor("public-star")
            compose.onNodeWithTag("public-star").performScrollTo().performClick()
            compose.waitUntil(10000) { publicationAPI.writes.any { it == ("POST" to "/v1/recipes/$id/like") } }
            compose.onNodeWithText("Your star is saved.").performScrollTo().assertIsDisplayed()

            // Fork → tweak-lite: rub, wrap and target are the decisions.
            compose.onNodeWithTag("public-fork").performScrollTo().performClick()
            waitFor("fork-save")
            compose.onNodeWithTag("fork-rub-0").performScrollTo().performTextReplacement("House rub")
            compose.onNodeWithTag("fork-wrap-0-foil").performScrollTo().performClick()
            compose.onNodeWithTag("fork-target-0").performScrollTo().performTextReplacement("165")
            shot("public-fork-tweak")
            compose.onNodeWithTag("fork-save").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("fork-save").fetchSemanticsNodes().isEmpty() || compose.onAllNodesWithTag("fork-error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("fork-error").assertDoesNotExist()
            waitFor("public-open-recipes")
            assertEquals(id, manager.state.value.savedDetailId)

            // The fork landed with the tweaks on it and the story intact.
            val saved = runBlocking(Dispatchers.IO) { database.recipeDao().recipes() }
            assertEquals(2, saved.size)
            val pork = saved.first { it.name.contains("pork", true) }
            assertTrue(pork.name.endsWith("— my version"))
            val snapshot = runBlocking(Dispatchers.IO) {
                RecipeSnapshotCodec.decode(database.recipeDao().revision(pork.currentRevisionId)!!.snapshotJson)
            }
            assertEquals("House rub", com.pittech.domain.CookInputVocabulary.rubFromIngredients(snapshot.dish.preparationItems))
            assertEquals("Foil", snapshot.instructions.first { it.action == "wrap" }.method)
            assertEquals(165.0, snapshot.targets.single().value, 0.0)
            assertEquals(id, snapshot.publicSource!!.itemId)
            assertEquals(3, snapshot.observations.size)

            // Plan this: the planner opens on the fork, pre-filled from
            // its frozen story, and the board shows a start time.
            compose.onNodeWithTag("public-plan-this").performScrollTo().performClick()
            compose.waitUntil(15000) { viewModel.servePlanEditor.value != null }
            val plan = viewModel.servePlanEditor.value!!
            assertEquals(1, plan.schedules.size)
            assertEquals(460L, plan.schedules[0].cookMaxMinutes)
            assertTrue(plan.schedules[0].evidenceNote.contains("a PitTech cook's recorded cook"))
            waitFor("plan-board-headline")
            compose.onNodeWithTag("plan-board-headline").performScrollTo().assertIsDisplayed()
            val headlineText = compose.onNodeWithTag("plan-board-headline").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .joinToString("") { it.text }
            assertTrue("a start time is shown, not a form: '$headlineText'", headlineText.contains(":"))
            shot("public-plan-this")
        } finally {
            scope.cancel()
            database.close()
            File(context.noBackupFilesDir, "public-browsing-$namespace-v1").deleteRecursively()
            writerRoot.deleteRecursively()
        }
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(20000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(compose.activity.filesDir, "pittech-ui-test/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
