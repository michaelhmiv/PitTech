package com.pittech.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.domain.*
import com.pittech.ui.ShareCardRenderer
import com.pittech.ui.ShareCardFiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ShareCardIntegrationTest {
    @Test fun actualRendererCoversChartPhotoMissingDataLongUnicodeAndOptionalRelay() {
        val test = InstrumentationRegistry.getInstrumentation(); val context = test.targetContext
        val fixture = test.context.assets.open("cross-platform/share-card-v1.json").bufferedReader().use { JSONObject(it.readText()) }
        val source = Bitmap.createBitmap(80,120,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(155,95,40)) }
        val photo = ByteArrayOutputStream().use { source.compress(Bitmap.CompressFormat.JPEG,90,it);it.toByteArray() };source.recycle()
        val before = photo.copyOf()
        for (mode in listOf("chart","photo","none","relay")) {
            val content = JSONObject(fixture.toString())
            if (mode == "photo") content.put("hero","photo").put("cropX",0.2).put("cropY",0.8).put("zoom",1.5)
            if (mode == "none") content.put("hero","none").put("milestones",JSONArray()).put("stats",JSONArray()).put("note","")
            if (mode == "relay") content.put("relay",JSONObject().put("code","EMBER-OAK-7").put("url","https://pittech.app/s/EMBER-OAK-7")).put("title","🌶".repeat(150)).put("note","椒🌶 ".repeat(600))
            val bytes = ShareCardRenderer.render(PreparedShareCard(content.toString(),if (mode == "photo") photo else null))
            val image = BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            assertEquals(1080,image.width);assertEquals(1350,image.height);assertEquals(Color.rgb(251,249,244),image.getPixel(0,0));image.recycle()
            File(context.filesDir,"pittech-ui-test/share-card-$mode.png").apply { parentFile!!.mkdirs();writeBytes(bytes) }
            val shared = ShareCardFiles.create(context,bytes);ShareCardFiles.prune(context,shared.lastModified()+25*3600000L);assertFalse(shared.exists())
        }
        assertArrayEquals(before,photo)
        val narrow = Bitmap.createBitmap(1,120,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(155,95,40)) }
        val narrowPhoto = ByteArrayOutputStream().use { narrow.compress(Bitmap.CompressFormat.JPEG,90,it);it.toByteArray() };narrow.recycle()
        val narrowPNG = ShareCardRenderer.render(PreparedShareCard(JSONObject(fixture.toString()).put("hero","photo").toString(),narrowPhoto))
        val narrowImage = BitmapFactory.decodeByteArray(narrowPNG,0,narrowPNG.size)
        assertTrue("A narrow valid photo must fill the hero",Color.red(narrowImage.getPixel(540,500))<200);narrowImage.recycle()
        try { ShareCardRenderer.render(PreparedShareCard(JSONObject(fixture.toString()).put("hero","photo").toString(),byteArrayOf(1)));fail("Bad cover rendered") } catch (_: Exception) { }
    }
    @Test fun builderKeepsStoryVerdictAndNotesExplicitAndPreservesFullTextInThePackModel() {
        val anchor = 1000000L
        val events = listOf(RecipeObservation("on",anchor,"UTC","Food on","","food_on",0,null),RecipeObservation("done",anchor+7200000,"UTC","Done","","dish_done",7200000,null))
        val snapshot = RecipeSnapshot("Pork",DishDraft("Pork","Pork"),description="Private source description",observations=events,rating=1.0,smokingAnchorUtcMillis=anchor)
        val observations = JSONObject(RecipeSnapshotCodec.encode(snapshot)).getJSONArray("observations")
        val story = JSONObject().put("version",1).put("attemptId","actual-source").put("anchorUtcMillis",anchor).put("observations",observations)
            .put("readings",JSONArray(listOf("one","two").map { id -> JSONObject().put("id",id).put("probeId",id).put("probeName","Same name").put("measurementType","food").put("value",74.125).put("unit","°C").put("measuredAtUtcMillis",anchor+60000).put("source","manual").put("qualityStatus","valid").put("samplingIntervalMillis",0) }))
            .put("results",JSONArray().put(JSONObject().put("type","overall_rating").put("numericValue",4.5)))
        val entry = SharingEntry(snapshot=snapshot,storyJson=story.toString())
        val base = SharingDocument("Pepper pork",listOf(entry),card=ShareCardOptions(note="My explicit note 🌶",includeVerdict=true,unit="°C"))
        val quiet = JSONObject(ShareCardContent.build(base,null).content)
        assertEquals("none",quiet.getString("hero"));assertEquals(0,quiet.getJSONArray("curves").length());assertFalse(quiet.toString().contains("Private source"))
        val full = base.copy(entries=listOf(entry.copy(includeStory=true)))
        val shown = JSONObject(ShareCardContent.build(full,null).content)
        assertEquals(2,shown.getJSONArray("curves").length());assertEquals("°C",shown.getString("unit"));assertEquals("4.5 / 5",shown.getJSONArray("stats").getJSONObject(0).getString("value"));assertTrue(shown.isNull("relay"))
        val long = full.copy(card=full.card.copy(note="🌶".repeat(2000)))
        assertEquals(long,SharingDraftCodec.decode(SharingDraftCodec.encode(long)))
        val code = ShareCardContent.build(full,null,ShareCardRelay("EMBER-OAK-7","https://pittech.app/s/EMBER-OAK-7"))
        assertEquals("EMBER-OAK-7",JSONObject(code.content).getJSONObject("relay").getString("code"))
        val dense = JSONArray((0 until 10000).map { i -> JSONObject(story.getJSONArray("readings").getJSONObject(0).toString()).put("id","dense-$i").put("probeId","one").put("measuredAtUtcMillis",anchor+i*15000L).put("value",when(i){1234->120.0;7654->-10.0;else->74.125}) })
        val denseStory = JSONObject(story.toString()).put("readings",dense)
        val chart = JSONObject(ShareCardContent.build(full.copy(entries=listOf(entry.copy(includeStory=true,storyJson=denseStory.toString()))),null).content).getJSONArray("curves").getJSONObject(0).getJSONArray("segments")
        val values = (0 until chart.length()).flatMap { s -> val points=chart.getJSONArray(s);(0 until points.length()).map { points.getJSONObject(it).getDouble("y") } }
        assertTrue("Fragmented recordings must stay bounded in an image",values.size<=200)
        assertEquals(120.0,values.max(),0.0);assertEquals(-10.0,values.min(),0.0)
    }
}
