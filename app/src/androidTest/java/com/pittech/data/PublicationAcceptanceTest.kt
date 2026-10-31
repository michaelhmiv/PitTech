package com.pittech.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class PublicationAcceptanceTest {
    @Test fun selectedVariationWholeCollectionAndANewActualCookKeepTheJournalIndependent() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=JSONObject(context.assets.open("public-native-v1.json").bufferedReader().use { it.readText() })
        val database=Room.inMemoryDatabaseBuilder(context,PitTechDatabase::class.java).build();val storage=PhotoStorage(context);val repository=CookRepository(database,storage)
        try {
            val preview=PitTechDataTransfer(context,repository).previewCookPack(Base64.getDecoder().decode(fixture.getString("packBase64")).inputStream());repository.importSnapshot(preview.snapshot,preview.attachments)
            val meal=repository.startRecipeMeal("My new pork and potatoes cook",preview.snapshot.recipes.map { it.currentRevisionId })
            val dishes=database.cookDao().getDishesForCook(meal);val pork=dishes.first { it.name.contains("pork",true) }
            repository.notes.save(CookNoteDraft(cookId=meal,dishId=pork.id,category="action",action="food_on",title="My food on"))
            repository.notes.save(CookNoteDraft(cookId=meal,dishId=pork.id,title="My measured temperature",temperatures=listOf(NoteTemperature(valueText="167",unit="°F",dishId=pork.id))))
            repository.notes.save(CookNoteDraft(cookId=meal,dishId=pork.id,category="action",action="remove",title="My food off"))
            repository.completeCook(meal);repository.saveDishVerdict(meal,pork.id,4.5,true,"My recorded verdict")
            val journal=repository.exportSnapshot();val sharing=repository.sharing;val draft=sharing.fromMeal(meal,dishes.map { it.id }.toSet());var row=checkNotNull(sharing.get(draft));var document=SharingDraftCodec.decode(row.payload)
            document=document.copy(entries=document.entries.map { it.copy(included=it.sourceDishId==pork.id,includeStory=it.sourceDishId==pork.id) });row=sharing.save(draft,document,row.updatedAtUtcMillis)
            val entry=document.entries.first { it.sourceDishId==pork.id };val snapshot=entry.snapshot.copy(description="My pepper variation",rating=1.0,resultNotes="Editable recipe note",dish=entry.snapshot.dish.copy(preparationItems=entry.snapshot.dish.preparationItems+IngredientDraft("Extra pepper",amountText="2",amountUnit="tsp")))
            sharing.editEntry(draft,entry.id,snapshot,emptyList(),emptyMap(),row.updatedAtUtcMillis)
            val variation=sharing.preparePublication(draft,"recipe");val actual=sharing.preparePublication(draft,"attempt");val recipe=actual.payload.getJSONArray("recipes").getJSONObject(0);val story=recipe.getJSONObject("story")
            assertEquals(4.5,story.getJSONObject("verdict").getDouble("rating"),0.0);assertEquals("My recorded verdict",story.getJSONObject("verdict").getString("notes"))
            assertEquals(1,story.getJSONArray("measurements").length());assertEquals(75.0,story.getJSONArray("measurements").getJSONObject(0).getDouble("valueCelsius"),0.0001)
            val source=fixture.getJSONObject("detail");assertNotEquals(source.getJSONObject("payload").getJSONArray("recipes").getJSONObject(0).getJSONObject("story").getString("attemptId"),story.getString("attemptId"));assertEquals(source.getString("id"),recipe.getJSONObject("source").getString("itemId"))
            val amounts=recipe.getJSONArray("ingredients");assertTrue((0 until amounts.length()).any { amounts.getJSONObject(it).getString("name")=="Salt and pepper" && amounts.getJSONObject(it).getString("amount")=="To taste" })
            assertEquals(1,variation.payload.getJSONArray("recipes").length());assertTrue((0 until recipe.getJSONArray("ingredients").length()).any { recipe.getJSONArray("ingredients").getJSONObject(it).getString("name")=="Extra pepper" })
            row=checkNotNull(sharing.get(draft));document=SharingDraftCodec.decode(row.payload);sharing.save(draft,document.copy(title="My whole meal",identityMode="named",entries=document.entries.map { it.copy(included=true) }),row.updatedAtUtcMillis)
            val collection=sharing.preparePublication(draft,"collection");assertEquals(2,collection.payload.getJSONArray("recipes").length());assertEquals("named",collection.payload.getString("identityMode"))
            try { sharing.preparePublication(draft,"recipe");fail("A multi-dish selection became a single recipe") } catch(_: IllegalArgumentException) { }
            val after=repository.exportSnapshot();assertEquals(journal.events,after.events);assertEquals(journal.results,after.results);assertEquals(journal.dishes,after.dishes);assertEquals(journal.readings,after.readings)
            val folder=File(context.filesDir,"pittech-publication").apply { mkdirs() };for((name,pack) in listOf("variant" to variation,"collection" to collection,"attempt" to actual))File(folder,"android-publication-$name.pittech").writeBytes(pack.bytes)
        } finally { database.recipeDao().assets().forEach { storage.delete(it.relativeFilePath) };database.companionDao().photos().forEach { storage.delete(it.relativePath) };database.close() }
    }

    // Publishable Cooks (issue #250): a finished meal's draft carries its
    // recorded session, and the whole cook publishes as kind=cook — session
    // object, every dish's story on the session clock — while a dish whose
    // story is left out blocks the cook kind (it is excluded, or the meal
    // publishes as a collection instead).
    @Test fun aWholeCookPublishesAsOneSessionWithEveryDishStory() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=JSONObject(context.assets.open("public-native-v1.json").bufferedReader().use { it.readText() })
        val database=Room.inMemoryDatabaseBuilder(context,PitTechDatabase::class.java).build();val storage=PhotoStorage(context);val repository=CookRepository(database,storage)
        try {
            val preview=PitTechDataTransfer(context,repository).previewCookPack(Base64.getDecoder().decode(fixture.getString("packBase64")).inputStream());repository.importSnapshot(preview.snapshot,preview.attachments)
            val meal=repository.startRecipeMeal("My publishable cook",preview.snapshot.recipes.map { it.currentRevisionId })
            val dishes=database.cookDao().getDishesForCook(meal);val pork=dishes.first { it.name.contains("pork",true) }
            repository.notes.save(CookNoteDraft(cookId=meal,dishId=pork.id,category="action",action="food_on",title="My food on"))
            repository.notes.save(CookNoteDraft(cookId=meal,dishId=pork.id,category="action",action="remove",title="My food off"))
            repository.completeCook(meal);repository.saveDishVerdict(meal,pork.id,5.0,true,"Whole cook verdict")
            val sharing=repository.sharing;val draft=sharing.fromMeal(meal,dishes.map { it.id }.toSet());var row=checkNotNull(sharing.get(draft));var document=SharingDraftCodec.decode(row.payload)
            assertTrue("a meal draft captures its recorded session",document.sessionJson!=null)
            document=document.copy(entries=document.entries.map { it.copy(included=true,includeStory=it.sourceDishId==pork.id) });row=sharing.save(draft,document,row.updatedAtUtcMillis)
            try { sharing.preparePublication(draft,"cook");fail("A cook publication accepted a dish without its story") } catch(_: IllegalArgumentException) { }
            document=document.copy(entries=document.entries.map { it.copy(includeStory=true) });row=sharing.save(draft,document,row.updatedAtUtcMillis)
            val cook=sharing.preparePublication(draft,"cook");val payload=cook.payload
            assertEquals("cook",payload.getString("kind"));assertTrue(payload.isNull("measuredAttemptId"))
            val session=payload.getJSONObject("session");assertTrue("a completed cook publishes its session length",!session.isNull("durationMillis") && session.getLong("durationMillis")>=0)
            assertTrue("no serve plan was attached to this cook",session.isNull("serveAtOffsetMillis"))
            assertEquals(2,payload.getJSONArray("recipes").length())
            val porkStory=(0 until payload.getJSONArray("recipes").length()).map { payload.getJSONArray("recipes").getJSONObject(it) }.first { it.getString("title").contains("pork",true) }.getJSONObject("story")
            assertTrue("the measured dish sits on the session clock",!porkStory.isNull("anchorOffsetMillis") && porkStory.getLong("anchorOffsetMillis")>=0)
            assertTrue("every dish in the cook carries its story",(0 until payload.getJSONArray("recipes").length()).all { !payload.getJSONArray("recipes").getJSONObject(it).isNull("story") })
            val collection=sharing.preparePublication(draft,"collection");assertFalse("other kinds gain no session object",collection.payload.has("session"))
        } finally { database.recipeDao().assets().forEach { storage.delete(it.relativeFilePath) };database.companionDao().photos().forEach { storage.delete(it.relativePath) };database.close() }
    }
}
