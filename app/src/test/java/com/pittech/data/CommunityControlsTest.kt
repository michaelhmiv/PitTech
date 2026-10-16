package com.pittech.data

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CommunityControlsTest {
    private class Storage:CommunityStorage{var value:CommunityControlsState?=null;var fail=false;override fun load()=value;override fun save(value:CommunityControlsState){check(!fail);this.value=CommunityControlsState.parse(value.json())}}
    private class Accounts:AccountStorage{var value=AccountState();override fun load()=value;override fun save(value:AccountState){this.value=value}}
    private fun account(scope:CoroutineScope,user:String?=null):AccountManager{val storage=Accounts();if(user!=null)storage.value=AccountState(session=AccountSession("A".repeat(43),"A".repeat(43),"2100-01-01T00:00:00Z","2100-02-01T00:00:00Z",user));return AccountManager(storage,scope,enabled=true)}
    @Test fun anonymousReportRetainsExactBodyAndSessionAcrossRestart()=runBlocking{
        val storage=Storage();val user=account(this);val receipt=UUID.randomUUID().toString();var original:String?=null;var originalSession:String?=null;var calls=0
        val api=CommunityAPI{path,body,access,session->assertEquals("/v1/reports",path);assertNull(access);assertNotNull(storage.value?.pending);if(original==null){original=body;originalSession=session}else{assertEquals(original,body);assertEquals(originalSession,session)};calls++;if(calls==1)throw PublicFailure();JSONObject().put("reportId",receipt).put("status","received")}
        val first=CommunityControls(storage,this,api);first.report(UUID.randomUUID().toString(),null,false,"privacy","Synthetic private report",user);yield();assertNotNull(first.presentation.value.pending)
        val restarted=CommunityControls(storage,this,api);restarted.retry(user);yield();assertNull(restarted.presentation.value.pending);assertEquals(receipt,restarted.presentation.value.reports.single().id);assertFalse(storage.value!!.json().toString().contains("Synthetic private report"))
    }
    @Test fun pendingReportCannotMoveToAnotherAccountAndStorageFailureNeverSends()=runBlocking{
        val storage=Storage();val alice=account(this,UUID.randomUUID().toString());val bob=account(this,UUID.randomUUID().toString());var calls=0
        val api=CommunityAPI{_,_,_,_->calls++;throw PublicFailure()};val controls=CommunityControls(storage,this,api)
        storage.fail=true;controls.report(UUID.randomUUID().toString(),null,true,"spam","",alice);yield();assertEquals(0,calls)
        storage.fail=false;controls.report(UUID.randomUUID().toString(),null,true,"spam","",alice);yield();assertEquals(1,calls);val saved=controls.presentation.value.pending!!.body
        controls.retry(bob);yield();assertEquals(1,calls);assertEquals(saved,controls.presentation.value.pending!!.body)
    }
    @Test fun anonymousBlockPersistsAndChangesAudienceBeforeNetwork()=runBlocking{
        val storage=Storage();val user=account(this);val alias=UUID.randomUUID().toString();var calls=0;var changed=0
        val controls=CommunityControls(storage,this,CommunityAPI{_,_,_,_->calls++;throw PublicFailure()})
        controls.block(alias,"Synthetic public recipe",true,user){changed++;assertEquals(alias,storage.value!!.blocks.single().alias)};yield();assertEquals(0,calls);assertEquals(1,changed);val generation=storage.value!!.generation
        val restarted=CommunityControls(storage,this);assertEquals(alias,restarted.presentation.value.blocks.single().alias);restarted.block(alias,"Synthetic public recipe",false,user);yield();assertTrue(restarted.presentation.value.blocks.isEmpty());assertNotEquals(generation,storage.value!!.generation)
    }
    @Test fun definiteRejectionRetainsRequestUntilExplicitDiscard()=runBlocking{
        val storage=Storage();val user=account(this);val controls=CommunityControls(storage,this,CommunityAPI{_,_,_,_->throw PublicFailure(422)})
        controls.report(UUID.randomUUID().toString(),null,false,"other","",user);yield();assertTrue(controls.presentation.value.pending!!.rejected);controls.discardRejected();assertNull(controls.presentation.value.pending)
    }
}
