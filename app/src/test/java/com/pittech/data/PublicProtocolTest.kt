package com.pittech.data

import com.pittech.domain.RecipeSnapshotCodec
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream

class PublicProtocolTest {
    private fun fixture()=JSONObject(javaClass.classLoader!!.getResourceAsStream("public-native-v1.json")!!.bufferedReader().use { it.readText() })
    @Test fun sharedServerPayloadAndNativeSnapshotRetainPinnedCreditActionsAndRelativeTiming() {
        val f=fixture();val detail=PublicDetail.parse(f.getJSONObject("detail"));assertEquals("Weekend smoke",detail.title);assertEquals(2,detail.recipes.size)
        assertEquals("a PitTech cook",PublicItem.parse(f.getJSONObject("feed").getJSONArray("items").getJSONObject(0)).credit)
        val raw=Base64.getDecoder().decode(f.getString("packBase64"));assertEquals(f.getString("packSha256"),RelayProtocol.hash(raw))
        var root: JSONObject?=null
        ZipInputStream(ByteArrayInputStream(raw)).use { zip->while(true){val entry=zip.nextEntry ?: break;if(entry.name=="data/pittech.json")root=JSONObject(String(zip.readBytes(),Charsets.UTF_8));zip.closeEntry()} }
        val snapshot=RecipeSnapshotCodec.decode(root!!.getJSONArray("recipeRevisions").getJSONObject(0).getString("snapshotJson"))
        assertEquals(detail.id,snapshot.publicSource!!.itemId);assertTrue(snapshot.publicSource.relativeTiming);assertEquals("wrap",snapshot.instructions[0].action)
        assertNull(snapshot.instructions[0].method);assertEquals("To taste",snapshot.dish.preparationItems[0].amountUnit)
        assertEquals(snapshot,RecipeSnapshotCodec.decode(RecipeSnapshotCodec.encode(snapshot)))
        val forged=JSONObject(f.getJSONObject("detail").toString());forged.getJSONArray("media").getJSONObject(0).put("url","https://foreign.invalid/private")
        assertThrows(Exception::class.java) { PublicDetail.parse(forged) }
    }
    @Test fun anonymousTransportPinsOriginAndRevisionAndRejectsCorruptionAndRedirects() = runBlocking {
        val f=fixture();val bytes=Base64.getDecoder().decode(f.getString("packBase64"));val detail=PublicDetail.parse(f.getJSONObject("detail"))
        for(status in listOf(200,302)) {
            var calls=0
            val http=OkHttpClient.Builder().addInterceptor { chain->
                calls++;val request=chain.request();assertNull(request.header("Authorization"));assertNull(request.header("Cookie"));assertEquals(detail.revisionId,request.header("If-Match"));assertEquals("api.pittech.app",request.url.host)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic").header("Location","https://foreign.invalid/").header("X-Content-SHA256",f.getString("packSha256")).body(bytes.toResponseBody()).build()
            }.build()
            val file=File.createTempFile("public-unit-", ".pittech")
            try { try { PublicClient(http).pack(detail.id,detail.revisionId,file);assertEquals(200,status);assertArrayEquals(bytes,file.readBytes()) } catch(e: PublicFailure){assertEquals(302,status);assertFalse(file.exists())};assertEquals(1,calls) } finally { file.delete() }
        }
    }
}
