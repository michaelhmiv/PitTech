package com.pittech.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pittech.domain.SharingDraftCodec
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.UUID

class OwnedPublicationTest {
    @Test fun ownerEditingWithoutAnOriginalDraftPreservesPublicIdentitiesAndTheActualAttempt() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val root=File(context.cacheDir,"owned-test-"+UUID.randomUUID()).apply { mkdirs() }
        val database=Room.inMemoryDatabaseBuilder(context,PitTechDatabase::class.java).build();val photos=PhotoStorage(context);val repository=CookRepository(database,photos)
        val user="aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa";val owner="feedfeed-feed-4eed-8eed-feedfeedfeed";val receipt=PublicationReceipt(owner,"eeeeeeee-eeee-4eee-eeee-eeeeeeeeeeee",3,"https://pittech.app/c/$owner")
        try {
            val fixture=JSONObject(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("native-publication-android-v1.json").bufferedReader().use { it.readText() });val bytes=Base64.getDecoder().decode(fixture.getString("archiveBase64"));assertEquals(fixture.getString("archiveSha256"),RelayProtocol.hash(bytes))
            val archive=File(root,"original.zip").apply { writeBytes(bytes) };val parsed=pendingPublication(archive);parsed.payload.getJSONArray("recipes").getJSONObject(0).put("method","Smoke");val original=PublicationProjection.pack(parsed.payload,parsed.photos);val draft=OwnedPublication.draft(original,receipt)
            val operations=PublicationOperationsStore(File(root,"operations"));operations.attachEditContext(user,draft.id,draft.context);repository.sharing.saveOwnedPublicationDraft(draft)
            val restarted=PublicationOperationsStore(operations.directory);val restored=checkNotNull(restarted.editContext(user,draft.id));assertEquals(owner,restarted.binding(user,draft.id)?.id);assertNull(restarted.editContext("bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb",draft.id))
            val row=checkNotNull(repository.sharing.get(draft.id));val document=SharingDraftCodec.decode(row.payload);val entry=document.entries.single()
            val snapshot=entry.snapshot.copy(description="My revised instructions",rating=1.0,instructions=entry.snapshot.instructions.map { it.copy(details="Change the method, not the recorded result.") })
            repository.sharing.editEntry(draft.id,entry.id,snapshot,emptyList(),emptyMap(),row.updatedAtUtcMillis)
            val update=repository.sharing.preparePublication(draft.id,"recipe",restored);val a=original.payload.getJSONArray("recipes").getJSONObject(0);val b=update.payload.getJSONArray("recipes").getJSONObject(0)
            assertEquals(a.opt("method"),b.opt("method"));assertTrue(SharingDraftCodec.sameStory(JSONObject().put("items",a.getJSONArray("ingredients")).toString(),JSONObject().put("items",b.getJSONArray("ingredients")).toString()));assertEquals(a.getString("id"),b.getString("id"));assertTrue(SharingDraftCodec.sameStory(a.getJSONObject("source").toString(),b.getJSONObject("source").toString()));assertTrue(SharingDraftCodec.sameStory(original.payload.getJSONObject("source").toString(),update.payload.getJSONObject("source").toString()))
            assertTrue(SharingDraftCodec.sameStory(a.getJSONObject("story").toString(),b.getJSONObject("story").toString()));assertEquals("My revised instructions",b.getString("description"))
            assertEquals(a.getJSONArray("instructions").getJSONObject(0).getString("id"),b.getJSONArray("instructions").getJSONObject(0).getString("id"));assertEquals(a.getJSONArray("assets").getJSONObject(0).getString("id"),b.getJSONArray("assets").getJSONObject(0).getString("id"))
            val independent=repository.sharing.preparePublication(draft.id,"recipe");assertEquals(owner,independent.payload.getJSONObject("source").getString("itemId"));assertNotEquals(a.getString("id"),independent.payload.getJSONArray("recipes").getJSONObject(0).getString("id"))
            val operation=restarted.prepare(user,"PUT","/v1/recipes/$owner",update.bytes,true,expected=restored.receipt.revisionId,draft=draft.id,kind="recipe")
            val next=receipt.copy(revisionId="ffffffff-ffff-4fff-8fff-ffffffffffff",revision=4);restarted.finish(operation,next.json());assertEquals(next.revisionId,restarted.editContext(user,draft.id)?.receipt?.revisionId)
            assertTrue(database.cookDao().getAllCooks().isEmpty());assertTrue(database.recipeDao().recipes().isEmpty());assertFalse(update.payload.toString().contains(user))
            val exported=File(context.filesDir,"pittech-publication/android-owned-update.pittech").apply { parentFile!!.mkdirs();writeBytes(update.bytes) };assertTrue(SharingDraftCodec.sameStory(update.payload.toString(),pendingPublication(exported).payload.toString()))
            val actual=JSONObject(original.payload.toString()).put("kind","attempt").put("measuredAttemptId",a.getJSONObject("story").getString("attemptId"));val measured=OwnedPublication.draft(PublicationProjection.pack(actual,original.photos),receipt);repository.sharing.saveOwnedPublicationDraft(measured)
            val actualUpdate=repository.sharing.preparePublication(measured.id,"attempt",measured.context);assertEquals(actual.getString("measuredAttemptId"),actualUpdate.payload.getString("measuredAttemptId"));assertTrue(SharingDraftCodec.sameStory(a.getJSONObject("story").toString(),actualUpdate.payload.getJSONArray("recipes").getJSONObject(0).getJSONObject("story").toString()))
            try { repository.sharing.preparePublication(measured.id,"attempt");fail("A reference copy claimed a new actual cook") } catch(_: IllegalArgumentException) { }
        } finally { database.companionDao().photos().forEach { photos.delete(it.relativePath) };database.close();root.deleteRecursively() }
    }
}
