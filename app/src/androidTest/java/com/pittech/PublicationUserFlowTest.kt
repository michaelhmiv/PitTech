package com.pittech

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.pittech.data.*
import com.pittech.domain.SharingDraftCodec
import com.pittech.ui.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class) class PublicationUserFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun previewRequiresConfirmationBeforePublishAndCookbookOptInAndRemovalAreExplicit() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,PitTechDatabase::class.java).build(); val storage=PhotoStorage(context)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate); val root=File(context.noBackupFilesDir,"publication-flow-"+UUID.randomUUID())
        val repository=CookRepository(database,storage); val transfer=PitTechDataTransfer(context,repository); val viewModel=CooksViewModel(repository,transfer,context)
        val vault=object: AccountStorage {
            var state=AccountState(session=AccountSession("A".repeat(43),"A".repeat(43),"2100-01-01T00:00:00.000Z","2100-02-01T00:00:00.000Z","12345678-1234-4234-8234-123456789abc"))
            override fun load()=state
            override fun save(value: AccountState) { state=value }
        }
        val account=AccountManager(vault,scope,AccountAPI { _,_,_->error("No authentication request expected") },true)
        val api=API(); val writer=PublicationWriter(AccountPublicationSession(account),PublicationOperationsStore(root),api)
        try {
            val draft=runBlocking(Dispatchers.IO) {
                val fixture=JSONObject(context.assets.open("public-native-v1.json").bufferedReader().use { it.readText() })
                val preview=transfer.previewCookPack(Base64.getDecoder().decode(fixture.getString("packBase64")).inputStream())
                val selected=preview.snapshot.recipes.first { it.name.contains("pork",true) }.currentRevisionId
                val scoped=preview.selectCookPackRecipes(setOf(selected)); repository.importSnapshot(scoped.snapshot,scoped.attachments)
                val id=repository.sharing.fromRecipe(selected); val row=repository.sharing.get(id)!!; val value=SharingDraftCodec.decode(row.payload)
                repository.sharing.save(id,value.copy(entries=value.entries.map { it.copy(includeStory=true,photoIds=repository.sharing.assets(id).filter { photo -> photo.action==it.id }.map { photo -> photo.id }) }),row.updatedAtUtcMillis); id
            }
            compose.setContent { PitTechTheme {
                var stage by remember { mutableStateOf(0) }
                if(stage<2)key(stage) { PublicationDialog(draft,viewModel,account,writer) { stage++ } }
                else MyCookbookDialog(viewModel,account,writer) { }
            } }
            waitFor("publication-prepare"); compose.onNodeWithTag("publication-prepare").performScrollTo().performClick()
            waitFor("publication-preview-title"); assertEquals(0,api.writes)
            compose.onNodeWithTag("publication-confirm").performScrollTo().assertIsNotEnabled(); screenshot("publication-preview")
            compose.onNodeWithTag("publication-reviewed").performScrollTo().performClick()
            compose.onNodeWithTag("publication-confirm").performScrollTo().performClick(); waitFor("publication-complete"); assertEquals(1,api.writes); screenshot("publication-complete")
            compose.onNodeWithText("Done").performClick(); waitFor("publication-prepare")
            compose.onNodeWithTag("publication-prepare").performScrollTo().performClick(); waitFor("publication-confirm")
            compose.onNodeWithTag("publication-confirm").performScrollTo().assertTextEquals("Publish this update").assertIsNotEnabled()
            compose.onNodeWithTag("publication-reviewed").performScrollTo().performClick(); compose.onNodeWithTag("publication-confirm").performScrollTo().performClick(); waitFor("publication-complete")
            assertEquals(2,api.writes); assertEquals(2,api.item!!.getInt("revision"))
            compose.onNodeWithText("Done").performClick(); waitFor("cookbook-name")
            compose.onNodeWithTag("cookbook-name").performScrollTo().performTextReplacement("Alex at the pit")
            compose.onNodeWithTag("cookbook-handle").performScrollTo().performTextReplacement("alex_at_the_pit")
            compose.onNodeWithTag("cookbook-public").performScrollTo().performClick()
            compose.onNodeWithTag("cookbook-preview").performScrollTo().performClick(); assertEquals(2,api.writes)
            compose.onNodeWithTag("cookbook-save").performScrollTo().performClick()
            compose.waitUntil(10000) { api.writes==3 }; screenshot("publication-cookbook")
            compose.onNodeWithText("Remove from public access").performScrollTo().performClick(); assertEquals(3,api.writes)
            compose.onNodeWithTag("cookbook-unpublish-confirm").performClick()
            compose.waitUntil(10000) { api.writes==4 && compose.onAllNodesWithText("Removed from public access").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(api.profile.getBoolean("isPublic")); assertEquals("unpublished",api.item!!.getString("status"))
            compose.onNodeWithTag("cookbook-edit-dddddddd-dddd-4ddd-dddd-dddddddddddd").performScrollTo().performClick()
            waitFor("cookbook-create-editing-draft"); compose.onNodeWithTag("cookbook-create-editing-draft").performScrollTo(); screenshot("publication-owner-edit-preview")
            compose.onNodeWithTag("cookbook-create-editing-draft").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick();waitFor("sharing-title")
            compose.onNodeWithTag("sharing-title").performScrollTo().performTextReplacement("Owner revised title")
            compose.onNodeWithTag("sharing-publish").performScrollTo().performClick();waitFor("publication-prepare")
            compose.onNodeWithTag("publication-prepare").performScrollTo().performClick();waitFor("publication-confirm")
            compose.onNodeWithTag("publication-confirm").performScrollTo().assertTextEquals("Publish this update").assertIsNotEnabled();screenshot("publication-owner-update-preview")
            compose.onNodeWithTag("publication-reviewed").performScrollTo().performClick();compose.onNodeWithTag("publication-confirm").performScrollTo().performClick();waitFor("publication-complete")
            assertEquals(5,api.writes);assertEquals(3,api.item!!.getInt("revision"));assertEquals("Owner revised title",api.item!!.getString("title"));assertEquals("active",api.item!!.getString("status"))
            assertEquals("anonymous",api.item!!.getString("identityMode")); assertTrue(runBlocking(Dispatchers.IO) { database.cookDao().getAllCooks().isEmpty() })
        } finally { scope.cancel(); runBlocking(Dispatchers.IO) { database.recipeDao().assets().forEach { storage.delete(it.relativeFilePath) }; database.companionDao().photos().forEach { storage.delete(it.relativePath) } }; database.close(); root.deleteRecursively() }
    }
    private fun waitFor(tag: String) {
        try { compose.waitUntil(20000) { compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() } }
        catch(error: Throwable) { compose.onRoot(useUnmergedTree=true).printToLog("PublicationFailure"); screenshot("publication-failure"); throw error }
    }
    private fun screenshot(name: String) { val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(); val file=File(compose.activity.filesDir,"pittech-ui-test/$name.png").apply { parentFile!!.mkdirs() }; file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle() }
    private class API: PublicationAPI {
        @Volatile var writes=0
        var profile=JSONObject().put("handle",JSONObject.NULL).put("displayName","").put("isPublic",false)
        var item: JSONObject?=null
        var archiveBytes: ByteArray?=null
        private val replies=mutableMapOf<String,JSONObject>()
        override suspend fun read(path: String, access: String): JSONObject = if(path=="/v1/me/profile")JSONObject(profile.toString()) else if(path.startsWith("/v1/me/recipes/"))JSONObject(checkNotNull(item).toString()) else JSONObject().put("items",org.json.JSONArray(item?.let { listOf(it) } ?: emptyList<JSONObject>())).put("nextCursor",JSONObject.NULL)
        override suspend fun archive(id: String,revision: String,to: File,access: String): File {
            require(item!!.getString("id")==id && item!!.getString("revisionId")==revision);to.writeBytes(checkNotNull(archiveBytes));return to
        }
        override suspend fun write(operation: PublicationOperation,file: File,access: String): JSONObject {
            replies[operation.id]?.let { return it }
            val response=if(operation.contentType=="application/zip") {
                require(operation.termsVersion==PublicationTerms.version)
                val preview=pendingPublication(file)
                if(item!=null) { require(operation.method=="PUT" && operation.expectedRevision==item!!.getString("revisionId")); require(operation.path=="/v1/recipes/"+item!!.getString("id")) }
                val number=(item?.getInt("revision") ?: 0)+1; val id="dddddddd-dddd-4ddd-dddd-dddddddddddd"; val revision=UUID.randomUUID().toString()
                item=JSONObject().put("id",id).put("revisionId",revision).put("revision",number).put("title",preview.payload.getString("title")).put("identityMode",preview.payload.getString("identityMode")).put("status","active")
                archiveBytes=file.readBytes()
                PublicationReceipt(id,revision,number,"https://pittech.app/c/$id").json()
            } else if(operation.path=="/v1/me/profile") { profile=JSONObject(file.readText()); JSONObject(profile.toString()) }
            else { item!!.put("status","unpublished"); JSONObject().put("id",item!!.getString("id")).put("unpublished",true) }
            replies[operation.id]=response; writes++; return response
        }
    }
}
