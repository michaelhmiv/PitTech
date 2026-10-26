package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.data.*
import org.json.JSONObject

@Composable internal fun CommunityActions(detail:PublicDetail,controls:CommunityControls,account:AccountManager,manager:PublicManager){
    val state by controls.presentation.collectAsStateWithLifecycle()
    var report by remember{mutableStateOf(false)};var publisher by remember{mutableStateOf(false)};var managing by remember{mutableStateOf(false)}
    TextButton(onClick={publisher=false;report=true},enabled=!state.busy,modifier=Modifier.testTag("public-report")){Text("Report this publication")}
    detail.publisherAlias?.let{alias->
        TextButton(onClick={publisher=true;report=true},enabled=!state.busy,modifier=Modifier.testTag("public-report-publisher")){Text("Report publisher")}
        TextButton(onClick={controls.block(alias,detail.title,true,account){manager.controlsChanged()}},enabled=!state.busy && state.pending==null,modifier=Modifier.testTag("public-block")){Text("Block publisher")}
    }
    Text("Reports and blocks work without signing in. Blocking does not remove copies already saved in your journal.",style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)
    TextButton(onClick={managing=true}){Text("Reports, saved requests and blocked publishers")}
    if(report)CommunityReportDialog(detail,publisher,controls,account,onClose={report=false},onManage={report=false;managing=true})
    if(managing)CommunityControlsDialog(controls,account,manager){managing=false}
}
@Composable internal fun CommunityControlsDialog(controls:CommunityControls,account:AccountManager,manager:PublicManager,onClose:()->Unit){
    val state by controls.presentation.collectAsStateWithLifecycle()
    // PL-22 (DD-6): community controls are a management surface — a sheet.
    SheetSurface(onDismiss=onClose,title="Community controls",actions={TextButton(onClick=onClose){Text("Done")}}){Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        state.pending?.let{pending->
            TurnInCard(Modifier.fillMaxWidth()){Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(if(pending.rejected)"Rejected request" else "Saved request",style=PitTechTextStyles.bannerCard)
                Text(if(pending.kind=="report")"Your report is saved on this device." else "Your account block change is saved on this device.")
                Text(if(pending.userId==null)"Retry stays anonymous on this device." else "Retry requires the account that started this request.",color=pitTechTokens().inkMuted)
                val fields=remember(pending.body){JSONObject(pending.body)}
                if(fields.has("reason")){Text("Reason · "+fields.getString("reason"),style=PitTechTextStyles.monoMeta,color=pitTechTokens().inkMuted);if(fields.getString("details").isNotEmpty())Text(fields.getString("details"))}
                if(pending.rejected)TextButton(onClick={controls.discardRejected()},enabled=!state.busy){Text("Discard rejected request")}
                else Button(onClick={controls.retry(account)},enabled=!state.busy,modifier=Modifier.testTag("community-retry")){Text("Retry the saved request")}
            }}
        }
        Text("Blocked publishers on this device",style=PitTechTextStyles.bannerCard)
        if(state.blocks.isEmpty()){TurnInDashedCard(Modifier.fillMaxWidth()){Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(EmptyStateCopy.BLOCKS_TITLE);Text(EmptyStateCopy.BLOCKS_BODY,style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)}}}
        state.blocks.forEach{block->TurnInCard(Modifier.fillMaxWidth()){Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text(block.title.ifEmpty{"Blocked publisher"},style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f));TextButton(onClick={controls.block(block.alias,block.title,false,account){manager.controlsChanged()}},enabled=!state.busy && state.pending==null){Text("Unblock publisher")}}}}
        Text("Report receipts",style=PitTechTextStyles.bannerCard)
        if(state.reports.isEmpty()){TurnInDashedCard(Modifier.fillMaxWidth()){Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(EmptyStateCopy.REPORTS_TITLE);Text(EmptyStateCopy.REPORTS_BODY,style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)}}}
        state.reports.forEach{receipt->Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("Report "+receipt.id.take(8)+" · "+receipt.status,style=PitTechTextStyles.monoMeta,modifier=Modifier.weight(1f));TextButton(onClick={controls.checkReport(receipt,account)},enabled=!state.busy){Text("Check report status")}}}
        Text("Only your report status is shown. Moderator notes and account identities stay private.",style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)
        if(state.busy)Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){CircularProgressIndicator();Text(LoadingCopy.WORKING)}
        state.message?.let{TurnInStatusStrip(it,pitTechTokens().flag,Modifier.testTag("community-message"))}
    }}
}
@Composable private fun CommunityReportDialog(detail:PublicDetail,publisher:Boolean,controls:CommunityControls,account:AccountManager,onClose:()->Unit,onManage:()->Unit){
    val state by controls.presentation.collectAsStateWithLifecycle()
    var reason by remember{mutableStateOf("other")};var details by remember{mutableStateOf("")};var asset by remember{mutableStateOf("")};var submitted by remember{mutableStateOf(false)}
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(state.pending,state.message){if(submitted && state.pending==null && state.message?.startsWith("Report received")==true)onClose()}
    // PL-22 (DD-6): the report form is a form — a sheet.
    SheetSurface(onDismiss=onClose,title=if(publisher)"Report publisher" else "Report publication",actions={
        TextButton(onClick=onClose){Text("Cancel")}
        TextButton(onClick={keyboard?.hide();submitted=true;controls.report(detail.id,asset.takeIf{it.isNotEmpty()},publisher,reason,details,account)},enabled=!state.busy && state.pending==null && details.length<=2000,modifier=Modifier.testTag("report-submit")){Text("Send report")}
    }){Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(detail.title,style=PitTechTextStyles.bannerCard)
        Text(if(publisher)"Report this publisher for review. Anonymous identity is not exposed." else "Report selected public content for review.",color=pitTechTokens().inkMuted)
        if(!publisher){val labels=listOf("Whole publication")+detail.media.indices.map{"Photo ${it+1}"};SimpleDropdownField("Content",if(asset.isEmpty())labels[0] else labels[detail.media.indexOfFirst{it.getString("id")==asset}+1],labels,{label->asset=if(label==labels[0])"" else detail.media[labels.indexOf(label)-1].getString("id")},enabled=!state.busy)}
        SimpleDropdownField("Reason",reason,CommunityControls.reasons,{reason=it},enabled=!state.busy)
        TurnInLiteralBoxedField("Details (optional, up to 2,000 characters)",Modifier.fillMaxWidth()){
            OutlinedTextField(details,{details=it},minLines=4,isError=details.length>2000,supportingText={Text("${details.length}/2000 characters")},modifier=Modifier.fillMaxWidth().testTag("report-details"))
        }
        Text("Do not include passwords or private device credentials. Your report is private to the review team.",style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)
        if(state.pending!=null){Text("Recover or review the saved request before sending another report.");TextButton(onClick=onManage){Text("Open community controls")}}
        state.message?.let{Text(it)}
    }}
}
