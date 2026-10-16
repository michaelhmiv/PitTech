package com.pittech

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.*
import com.pittech.ui.PitTechTheme
import com.pittech.ui.PublicDiscoveryScreen
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class) class CommunityControlsUserFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun anonymousReportRecoveryAndPublisherBlockKeepTheJournalSeparate(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=JSONObject(context.assets.open("public-native-v1.json").bufferedReader().use{it.readText()})
        val alias=UUID.randomUUID().toString();fixture.getJSONObject("detail").put("publisherAlias",alias)
        val id=fixture.getJSONObject("detail").getString("id");val receipt=UUID.randomUUID().toString()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val accountStorage=object:AccountStorage{var value=AccountState();override fun load()=value;override fun save(value:AccountState){this.value=value}}
        val account=AccountManager(accountStorage,scope,enabled=true)
        val storage=object:CommunityStorage{var value:CommunityControlsState?=null;override fun load()=value;override fun save(value:CommunityControlsState){this.value=value}}
        var calls=0
        val api=CommunityAPI{path,_,access,_->assertNull(access);calls++;if(path=="/v1/reports"){if(calls==1)throw PublicFailure();JSONObject().put("reportId",receipt).put("status","received")}else JSONObject().put("reportId",receipt).put("status","reviewing")}
        val controls=CommunityControls(storage,scope,api)
        val publicAPI=object:PublicAPI{
            override suspend fun json(path:String):ByteArray{
                if(storage.value?.blocks?.isNotEmpty()==true){if(path.startsWith("/v1/discover?"))return JSONObject().put("items",org.json.JSONArray()).put("nextCursor",JSONObject.NULL).toString().toByteArray();throw PublicFailure(404)}
                return fixture.getJSONObject(if(path.startsWith("/v1/discover?"))"feed" else "detail").toString().toByteArray()
            }
            override suspend fun pack(id:String,revision:String,to:File):File=error("Community controls cannot import a journal")
            override suspend fun image(id:String,asset:JSONObject)=Base64.getDecoder().decode(fixture.getString("imageBase64"))
        }
        val namespace="controls-ui-"+UUID.randomUUID().toString()
        val manager=PublicManager(context,scope,{_,_,_,_->error("No import action was requested")},publicAPI,true,PublicCache(context,namespace),audienceProvider={storage.value?.generation?:"empty"})
        try{
            compose.setContent{PitTechTheme{PublicDiscoveryScreen(manager,Modifier,account=account,controls=controls)}}
            compose.waitUntil(10000){manager.state.value.items.isNotEmpty()}
            compose.onNodeWithTag("discover-open-$id").performScrollTo().performClick();compose.waitUntil(10000){manager.state.value.detail!=null}
            compose.onNodeWithTag("public-report").performScrollTo().performClick();compose.onNodeWithTag("report-details").performScrollTo().performTextReplacement("Synthetic private review request")
            compose.onNodeWithTag("report-submit").performClick();compose.waitUntil(10000){controls.presentation.value.pending!=null && !controls.presentation.value.busy};shot("community-report-saved")
            compose.onNodeWithText("Open community controls").performScrollTo().performClick();compose.onNodeWithTag("community-retry").performScrollTo().performClick()
            compose.waitUntil(10000){controls.presentation.value.reports.size==1 && controls.presentation.value.pending==null}
            compose.onNodeWithText("Check report status").performScrollTo().performClick();compose.waitUntil(10000){controls.presentation.value.reports.single().status=="reviewing"};shot("community-report-status")
            compose.onNodeWithText("Done").performClick();compose.onNodeWithTag("public-block").performScrollTo().performClick();compose.waitUntil(10000){controls.presentation.value.blocks.size==1 && manager.state.value.detail==null}
            compose.onNodeWithTag("discover-search").performScrollTo().performClick();compose.waitUntil(10000){!manager.state.value.loading};assertTrue(manager.state.value.items.isEmpty());shot("community-publisher-blocked")
            compose.onNodeWithText("Community controls").performScrollTo().performClick();compose.onNodeWithText("Unblock publisher").performScrollTo().performClick();compose.waitUntil(10000){controls.presentation.value.blocks.isEmpty()}
            compose.onNodeWithText("Done").performClick();compose.onNodeWithTag("discover-search").performScrollTo().performClick();compose.waitUntil(10000){manager.state.value.items.isNotEmpty()};assertNull(accountStorage.value.session)
        }finally{scope.cancel();File(context.noBackupFilesDir,"public-browsing-$namespace-v1").deleteRecursively()}
    }
    private fun shot(name:String){val instrumentation=InstrumentationRegistry.getInstrumentation();val image=instrumentation.uiAutomation.takeScreenshot();val file=File(instrumentation.targetContext.filesDir,"pittech-ui-test/$name.png").apply{parentFile!!.mkdirs()};file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
}
