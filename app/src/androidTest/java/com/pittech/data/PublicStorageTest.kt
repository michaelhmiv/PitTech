package com.pittech.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.domain.RecipeSnapshotCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.UUID

class PublicStorageTest {
    @Test fun actualServerPackImportsOnlySelectedOwnedRecipesAndStartsANewIndependentMeal() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val f=JSONObject(context.assets.open("public-native-v1.json").bufferedReader().use { it.readText() })
        val database=Room.inMemoryDatabaseBuilder(context,PitTechDatabase::class.java).build();val storage=PhotoStorage(context)
        try {
            val repository=CookRepository(database,storage);val transfer=PitTechDataTransfer(context,repository)
            val pack=transfer.previewCookPack(Base64.getDecoder().decode(f.getString("packBase64")).inputStream())
            val selected=pack.snapshot.recipes.first { it.name.contains("pork",true) }.currentRevisionId
            val scoped=pack.selectCookPackRecipes(setOf(selected));repository.importSnapshot(scoped.snapshot,scoped.attachments)
            assertEquals(1,database.recipeDao().recipes().size);assertTrue(database.cookDao().getAllCooks().isEmpty());assertTrue(database.reminderDao().rules().isEmpty())
            val snapshot=RecipeSnapshotCodec.decode(database.recipeDao().revision(selected)!!.snapshotJson);assertEquals(f.getJSONObject("detail").getString("id"),snapshot.publicSource!!.itemId);assertTrue(snapshot.publicSource.relativeTiming)
            val meal=repository.startRecipeMeal("My own cook",listOf(selected));assertEquals(1,database.cookDao().getDishesForCook(meal).size)
            assertEquals(1,database.recipeDao().assets().size);assertEquals(1,database.companionDao().photos().size)
            assertTrue(database.cookDao().getAllCooks().single().startedAtUtcMillis>1_700_000_000_000L)
            val sharing=SharingRepository(database,storage,repository);val draftId=sharing.fromRecipe(selected)
            val row=sharing.get(draftId)!!;val draft=com.pittech.domain.SharingDraftCodec.decode(row.payload);val owned=sharing.assets(draftId)
            val value=draft.copy(identityMode="anonymous",entries=draft.entries.map { e->e.copy(includeStory=true,photoIds=owned.filter { it.action==e.id }.map { it.id }) })
            sharing.save(draftId,value,row.updatedAtUtcMillis)
            val publication=sharing.preparePublication(draftId,"recipe")
            assertEquals(f.getJSONObject("detail").getString("id"),publication.payload.getJSONObject("source").getString("itemId"))
            val frozen=publication.payload.getJSONArray("recipes").getJSONObject(0).getJSONObject("story")
            assertTrue(com.pittech.domain.SharingDraftCodec.sameStory(f.getJSONObject("detail").getJSONObject("payload").getJSONArray("recipes").getJSONObject(0).getJSONObject("story").toString(),frozen.toString()))
            assertEquals(1,publication.photos.size);assertTrue(publication.bytes.size<64*1024*1024)
            val exported=File(context.filesDir,"pittech-publication").apply { mkdirs() }.resolve("android-publication.pittech"); exported.writeBytes(publication.bytes)
            val recovered=pendingPublication(exported)
            assertTrue(com.pittech.domain.SharingDraftCodec.sameStory(publication.payload.toString(),recovered.payload.toString())); assertArrayEquals(publication.bytes,recovered.bytes)
            assertEquals(publication.photos.keys,recovered.photos.keys); publication.photos.forEach { (id,bytes) -> assertArrayEquals(bytes,recovered.photos[id]) }
        } finally {database.recipeDao().assets().forEach { storage.delete(it.relativeFilePath) };database.companionDao().photos().forEach { storage.delete(it.relativePath) };database.close()}
    }
    @Test fun cachedBrowsingSurvivesRestartButAnUnavailableRecipeCannotUseStaleRemoteData() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val f=JSONObject(context.assets.open("public-native-v1.json").bufferedReader().use { it.readText() });val namespace="test-"+UUID.randomUUID().toString()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var status=200
        val api=object : PublicAPI {
            override suspend fun json(path: String): ByteArray { if(status!=200)throw PublicFailure(status);return f.getJSONObject(if(path.startsWith("/v1/discover?"))"feed" else "detail").toString().toByteArray() }
            override suspend fun pack(id: String,revision: String,to: File)=error("No import during browsing")
            override suspend fun image(id: String,asset: JSONObject)=Base64.getDecoder().decode(f.getString("imageBase64"))
        }
        try {
            val one=PublicManager(context,scope,{_,_,_,_->fail("Browsing imported data")},api,true,PublicCache(context,namespace));one.refresh();withTimeout(10000){one.state.first { !it.loading && it.items.isNotEmpty() }}
            val id=f.getJSONObject("detail").getString("id");one.open(id);withTimeout(10000){one.state.first { it.detail!=null && !it.detailLoading }}
            status=0
            val two=PublicManager(context,scope,{_,_,_,_->fail("Cached browsing imported data")},api,true,PublicCache(context,namespace));two.refresh();val state=withTimeout(10000){two.state.first { !it.loading && it.items.isNotEmpty() }};assertTrue(state.message!!.contains("saved"))
            status=404;two.open(id);withTimeout(10000){two.state.first { !it.detailLoading }};assertNull(two.state.value.detail);assertNull(PublicCache(context,namespace).read("/v1/recipes/$id"))
        } finally {scope.cancel();File(context.noBackupFilesDir,"public-browsing-$namespace-v1").deleteRecursively()}
    }
}
