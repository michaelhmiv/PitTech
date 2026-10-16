package com.pittech.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PrivateSyncMergeTest {
    @Test fun sharedVectorsKeepBothEditsDeletionTombstonesAndIndependentRecipeBranches(){
        val fixture=JSONObject(checkNotNull(javaClass.getResourceAsStream("/sync-merge-v1.json")).bufferedReader().use{it.readText()});assertEquals(1,fixture.getInt("contractVersion"));val cases=fixture.getJSONArray("cases");assertEquals(10,cases.length())
        for(i in 0 until cases.length()){
            val v=cases.getJSONObject(i);val base=v.getJSONArray("base");val local=v.getJSONArray("local");val remote=v.getJSONArray("remote")
            if(v.optBoolean("rejected"))assertThrows(v.getString("name"),Exception::class.java){PrivateSyncMerge.merge(base,local,remote)}
            else {val result=PrivateSyncMerge.merge(base,local,remote);assertEquals(v.getString("name"),PrivateSyncMerge.stable(v.getJSONObject("expected")),PrivateSyncMerge.stable(result));assertEquals(PrivateSyncMerge.stable(result),PrivateSyncMerge.stable(PrivateSyncMerge.merge(base,remote,local)))}
        }
    }
}
