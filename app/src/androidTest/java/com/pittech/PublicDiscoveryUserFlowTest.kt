package com.pittech

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

@RunWith(AndroidJUnit4::class) class PublicDiscoveryUserFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun signedOutBrowsingSavesOwnedRecipesAndExplicitlyStartsANewCook() {
        val app=compose.activity.application as PitTechApplication
        val f=JSONObject(app.assets.open("public-native-v1.json").bufferedReader().use { it.readText() })
        runBlocking(Dispatchers.IO){app.database.clearAllTables()}
        val api=object : PublicAPI {
            override suspend fun json(path: String)=f.getJSONObject(if(path.startsWith("/v1/discover?"))"feed" else "detail").toString().toByteArray()
            override suspend fun pack(id: String,revision: String,to: File)=to.apply { writeBytes(Base64.getDecoder().decode(f.getString("packBase64"))) }
            override suspend fun image(id: String,asset: JSONObject)=Base64.getDecoder().decode(f.getString("imageBase64"))
        }
        compose.runOnUiThread { ViewModelProvider(compose.activity).get(CooksViewModel::class.java).installPublicTestAPI(api) }
        compose.activityRule.scenario.recreate()
        waitFor("nav-discover");compose.onNodeWithTag("nav-discover").performClick()
        val id=f.getJSONObject("detail").getString("id");waitFor("discover-open-$id");compose.onNodeWithTag("discover-open-$id").performScrollTo().performClick()
        waitFor("public-title");shot("public-detail")
        compose.onNodeWithTag("public-save").performScrollTo().performClick();waitFor("pack-save-recipes");shot("public-selective-preview")
        compose.onNodeWithTag("pack-save-recipes").assertIsDisplayed().performClick()
        compose.waitUntil(10000){compose.onAllNodesWithTag("pack-save-recipes").fetchSemanticsNodes().isEmpty()}
        val saved=runBlocking(Dispatchers.IO){app.database.recipeDao().recipes()};assertEquals(2,saved.size)
        assertTrue(runBlocking(Dispatchers.IO){app.database.cookDao().getAllCooks().isEmpty()})
        val source=runBlocking(Dispatchers.IO){app.database.recipeDao().revision(saved.first().currentRevisionId)!!}
        assertEquals(id,com.pittech.domain.RecipeSnapshotCodec.decode(source.snapshotJson).publicSource!!.itemId)
        compose.onNodeWithTag("nav-recipes").performClick()
        val pork=saved.first { it.name.contains("pork",true) };waitFor("recipe-open-${pork.id}");compose.onNodeWithTag("recipe-open-${pork.id}").performScrollTo().performClick()
        waitFor("source-review-open");compose.onNodeWithTag("source-review-open").performScrollTo().performClick()
        waitFor("source-review-check");compose.onNodeWithTag("source-review-check").performScrollTo().performClick()
        waitFor("source-review-member-0");compose.onNodeWithTag("source-review-member-0").performScrollTo().performClick()
        compose.onNodeWithText("Ingredients · unchanged").performScrollTo().assertIsDisplayed();compose.onAllNodesWithText("1. Salt and pepper · To taste").onFirst().performScrollTo().assertIsDisplayed();shot("source-change-review")
        compose.onNodeWithTag("source-review-done").performScrollTo().performClick();compose.onNodeWithTag("recipe-details-close").performClick()
        compose.onNodeWithTag("nav-discover").performClick()
        compose.onNodeWithTag("public-cook").performScrollTo().performClick();waitFor("pack-save-recipes")
        compose.onNodeWithTag("pack-save-recipes").assertIsDisplayed().performClick()
        compose.waitUntil(15000){runBlocking(Dispatchers.IO){app.database.cookDao().getAllCooks().isNotEmpty()}}
        val cook=runBlocking(Dispatchers.IO){app.database.cookDao().getAllCooks().single()};assertEquals(2,runBlocking(Dispatchers.IO){app.database.cookDao().getDishesForCook(cook.id).size})
        val dish=runBlocking(Dispatchers.IO){app.database.cookDao().getDishesForCook(cook.id).first()}
        waitFor("food-card-${dish.id}");compose.onNodeWithTag("food-card-${dish.id}").performScrollTo().assertIsDisplayed()
        assertTrue(runBlocking(Dispatchers.IO){app.database.reminderDao().rules().isEmpty()});shot("public-cook")
    }
    @Test fun changingBlocksDoesNotReuseAnotherAudienceOfflineCache(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=JSONObject(context.assets.open("public-native-v1.json").bufferedReader().use{it.readText()})
        val generation=java.util.concurrent.atomic.AtomicReference("before");val offline=java.util.concurrent.atomic.AtomicBoolean(false)
        val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Main.immediate)
        val namespace="controls-"+java.util.UUID.randomUUID().toString()
        val api=object:com.pittech.data.PublicAPI{
            override suspend fun json(path:String):ByteArray{if(offline.get())throw com.pittech.data.PublicFailure();return fixture.getJSONObject("feed").toString().toByteArray()}
            override suspend fun pack(id:String,revision:String,to:File):File=error("Browsing cannot import")
            override suspend fun image(id:String,asset:JSONObject):ByteArray=error("Browsing cannot load a photo here")
        }
        val manager=com.pittech.data.PublicManager(context,scope,{_,_,_,_->},api,true,com.pittech.data.PublicCache(context,namespace),audienceProvider={generation.get()})
        try{
            compose.runOnIdle{manager.refresh()};compose.waitUntil(10000){!manager.state.value.loading && manager.state.value.items.isNotEmpty()}
            offline.set(true);generation.set("after");compose.runOnIdle{manager.controlsChanged();manager.refresh()};compose.waitUntil(10000){!manager.state.value.loading};assertTrue(manager.state.value.items.isEmpty())
            generation.set("after-unblock");compose.runOnIdle{manager.controlsChanged();manager.refresh()};compose.waitUntil(10000){!manager.state.value.loading};assertTrue(manager.state.value.items.isEmpty())
        }finally{scope.coroutineContext[kotlinx.coroutines.Job]?.cancel();File(context.noBackupFilesDir,"public-browsing-$namespace-v1").deleteRecursively()}
    }
    private fun waitFor(tag: String){compose.waitUntil(20000){compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}}
    private fun shot(name: String){compose.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync();val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();val file=File(compose.activity.filesDir,"pittech-ui-test/$name.png").apply { parentFile!!.mkdirs() };file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()}
}
