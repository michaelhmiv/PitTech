package com.pittech.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class) class PublicationOperationsTest {
    private val user="aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
    private val draft="bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb"
    private fun root()=File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir,"publication-test-"+UUID.randomUUID())
    @Test fun lostResponseRecoversIdenticalBodyAndOperationAfterRestartWithoutDuplicatePublication() = runBlocking {
        val root=root()
        try {
            val store=PublicationOperationsStore(root); val session=Session(user); val api=API(); val bytes="exact selected public archive".toByteArray()
            val op=store.prepare(user,"POST","/v1/recipes",bytes,true,draft=draft,kind="attempt",termsVersion=PublicationTerms.version); api.loseResponse=true
            try { PublicationWriter(session,store,api).recover(); fail("Expected lost response") } catch(_: IOException) { }
            assertEquals(PublicationTerms.version,store.pending(user)?.termsVersion);assertEquals(op.id,store.pending(user)?.id)
            assertThrows(Exception::class.java) { store.discardRejected(user) }
            val restarted=PublicationOperationsStore(root); PublicationWriter(session,restarted,api).recover()
            assertEquals(1,api.effects.size); assertEquals(listOf(op.id,op.id),api.operations)
            api.bodies.forEach { assertArrayEquals(bytes,it) }
            assertNull(restarted.pending(user)); assertEquals(api.receipt.id,restarted.binding(user,draft)?.id); assertEquals("attempt",restarted.binding(user,draft)?.kind); assertFalse(store.body(op).exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun accountChangeDuringAuthorizationAndCorruptBodyNeverReachServer() = runBlocking {
        val root=root()
        try {
            val store=PublicationOperationsStore(root); val session=Session(user); val api=API()
            val op=store.prepare(user,"POST","/v1/recipes","preview".toByteArray(),true,draft=draft)
            session.onAccess={ session.user="cccccccc-cccc-4ccc-cccc-cccccccccccc" }
            val writer=PublicationWriter(session,store,api)
            try { writer.recover(); fail("Changed account sent request") } catch(_: IllegalStateException) { }
            assertTrue(api.operations.isEmpty()); assertNull(writer.pending()); assertEquals(op.id,store.pending(user)?.id)
            session.user=user; session.onAccess=null; store.body(op).writeText("changed")
            try { writer.recover(); fail("Corrupt body sent request") } catch(_: IllegalArgumentException) { }
            assertTrue(api.operations.isEmpty()); assertEquals(op.id,store.pending(user)?.id)
        } finally { root.deleteRecursively() }
    }
    @Test fun unauthorizedRetriesSameOperationOnceAndDefiniteConflictRequiresExplicitDiscard() = runBlocking {
        val root=root()
        try {
            val store=PublicationOperationsStore(root); val session=Session(user); val api=API()
            val op=store.prepare(user,"POST","/v1/recipes","preview".toByteArray(),true,draft=draft); api.statuses+=401
            PublicationWriter(session,store,api).recover()
            assertEquals(listOf(false,true),session.refreshes); assertEquals(listOf(op.id,op.id),api.operations)
            val conflict=store.prepare(user,"DELETE","/v1/recipes/"+api.receipt.id,"{}".toByteArray(),false,api.receipt.revisionId); api.statuses+=409
            try { PublicationWriter(session,store,api).recover(); fail("Expected conflict") } catch(e: AccountFailure) { assertEquals(409,e.status) }
            assertEquals(conflict.id,store.pending(user)?.id); assertEquals(true,store.pending(user)?.rejected)
            assertThrows(Exception::class.java) { store.prepare(user,"POST","/v1/recipes","other".toByteArray(),true) }
            store.discardRejected(user); assertNull(store.pending(user))
            val malformed=store.prepare(user,"POST","/v1/recipes/"+api.receipt.id+"/like","{}".toByteArray(),false)
            try { PublicationWriter(session,store,api).recover(); fail("Malformed success cleared request") } catch(_: Exception) { }
            assertEquals(malformed.id,store.pending(user)?.id); assertEquals(false,store.pending(user)?.rejected); assertThrows(Exception::class.java) { store.discardRejected(user) }
            api.readStatuses+=401; session.refreshes.clear()
            PublicationWriter(session,store,api).read("/v1/me/profile")
            api.archiveStatuses+=401;session.refreshes.clear()
            val download=File(root,"owner.zip");val writer=PublicationWriter(session,store,api)
            writer.ownedArchive(api.receipt,download);assertEquals(2,api.archives);assertEquals(listOf(false,true),session.refreshes);assertEquals("owner bytes",download.readText())
            api.onArchive={ session.user="cccccccc-cccc-4ccc-cccc-cccccccccccc" }
            try { writer.ownedArchive(api.receipt,download);fail("Changed account received owner bytes") } catch(_: IllegalStateException) { }
            assertFalse(download.exists());session.user=user
            assertEquals(2,api.reads); assertEquals(malformed.id,store.pending(user)?.id)
        } finally { root.deleteRecursively() }
    }
    private class Session(var user: String?): PublicationSession {
        val refreshes=mutableListOf<Boolean>(); var onAccess: (() -> Unit)?=null
        override suspend fun userId()=user
        override suspend fun access(forceRefresh: Boolean): String { refreshes+=forceRefresh; onAccess?.invoke(); return if(forceRefresh)"B".repeat(42)+"A" else "A".repeat(43) }
    }
    private class API: PublicationAPI {
        val receipt=PublicationReceipt("dddddddd-dddd-4ddd-dddd-dddddddddddd","eeeeeeee-eeee-4eee-eeee-eeeeeeeeeeee",1,"https://pittech.app/c/dddddddd-dddd-4ddd-dddd-dddddddddddd")
        val operations=mutableListOf<String>(); val bodies=mutableListOf<ByteArray>(); val effects=mutableSetOf<String>(); val statuses=mutableListOf<Int>(); var loseResponse=false
        val readStatuses=mutableListOf<Int>(); var reads=0
        val archiveStatuses=mutableListOf<Int>();var archives=0;var onArchive: (() -> Unit)?=null
        override suspend fun archive(id: String,revision: String,to: File,access: String): File {
            archives++;assertEquals(receipt.id,id);assertEquals(receipt.revisionId,revision)
            if(archiveStatuses.isNotEmpty())throw AccountFailure(archiveStatuses.removeAt(0))
            to.writeText("owner bytes");onArchive?.invoke();return to
        }
        override suspend fun read(path: String, access: String): JSONObject { reads++; if(readStatuses.isNotEmpty())throw AccountFailure(readStatuses.removeAt(0)); return JSONObject() }
        override suspend fun write(operation: PublicationOperation, file: File, access: String): JSONObject {
            operations+=operation.id; bodies+=file.readBytes()
            if(statuses.isNotEmpty())throw AccountFailure(statuses.removeAt(0))
            effects+=operation.id; if(loseResponse) { loseResponse=false; throw IOException("Lost response") }; return receipt.json()
        }
    }
}
