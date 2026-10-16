package com.pittech.data

import org.json.JSONObject

internal data class DeletionReceipt(val deletionId: String,val statusToken: String,val state: String) {
    override fun toString()="DeletionReceipt(redacted)"
    fun validate(){require(deletionUUID(deletionId) && AccountProtocol.credential(statusToken) && state in setOf("queued","purging","notifying","complete"))}
    fun json()=JSONObject().put("deletionId",deletionId).put("statusToken",statusToken).put("state",state)
    val description get()=when(state){
        "queued"->"Your account deletion is queued."
        "purging"->"Original server data is being removed. Cleanup retries automatically if storage is unavailable."
        "notifying"->"Your account and original server data have been removed. The completion email is being processed."
        else->"Account deletion is complete. Your local journal and other cooks’ independent copies are separate."
    }
    companion object {fun parse(o:JSONObject)=DeletionReceipt(o.getString("deletionId"),o.getString("statusToken"),o.getString("state")).also{it.validate()}}
}
/** Device-only proof and receipt. Never included in journal exports or private sync. */
internal data class DeletionRecord(val userId:String?,val operationId:String,val verifier:String?,val receipt:DeletionReceipt?=null){
    override fun toString()="DeletionRecord(redacted)"
    fun validate(){require(deletionUUID(operationId) && (userId==null || deletionUUID(userId)) && (verifier?.let(AccountProtocol::credential) ?: (receipt?.state=="complete")) && (receipt!=null || userId!=null));receipt?.validate()}
    fun request()=JSONObject().put("operationId",operationId).put("proofChallenge",AccountProtocol.proof(requireNotNull(verifier))).put("confirm","delete-account").put("policyVersion","account-deletion-v1")
    fun json()=JSONObject().put("userId",userId?:JSONObject.NULL).put("operationId",operationId).put("verifier",verifier?:JSONObject.NULL).put("receipt",receipt?.json()?:JSONObject.NULL)
    companion object {fun parse(o:JSONObject)=DeletionRecord(if(o.isNull("userId"))null else o.getString("userId"),o.getString("operationId"),if(o.isNull("verifier"))null else o.getString("verifier"),o.optJSONObject("receipt")?.let(DeletionReceipt::parse)).also{it.validate()}}
}
private fun deletionUUID(value:String)=value.matches(Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))
