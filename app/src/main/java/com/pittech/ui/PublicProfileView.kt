package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pittech.data.PublicManager
import com.pittech.data.PublicPresentation

@Composable internal fun PublicProfileContent(manager: PublicManager,state: PublicPresentation) {
    TextButton(onClick={manager.back()}) { Text("Back to Discover") }
    if(state.profileLoading)Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)){CircularProgressIndicator();Text(LoadingCopy.LOADING_PROFILE)}
    state.profileName?.let { name->
        TurnInCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                TurnInMetaLabel("Public cookbook")
                Text(name,style=PitTechTextStyles.bannerCard)
                Text("@${state.profileHandle}",style=PitTechTextStyles.monoMeta,color=pitTechTokens().inkMuted)
                Text("Named publications from this public cookbook. Saving a recipe makes a private copy.",style=MaterialTheme.typography.bodyMedium,color=pitTechTokens().inkMuted)
            }
        }
        state.profileItems.forEach { item->
            TurnInCard(Modifier.fillMaxWidth(),onClick={manager.open(item.id)}) {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(item.title,style=PitTechTextStyles.bannerCard)
                    Text("${item.likes} likes · ${item.forks} forks",style=PitTechTextStyles.monoMeta,color=pitTechTokens().inkMuted)
                }
            }
        }
        if(state.profileItems.isEmpty() && !state.profileLoading){
            TurnInDashedCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(EmptyStateCopy.PROFILE_PUBLICATIONS_TITLE,style=MaterialTheme.typography.bodyMedium)
                    Text(EmptyStateCopy.PROFILE_PUBLICATIONS_BODY,style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)
                }
            }
        }
        state.profileCursor?.let { cursor->OutlinedButton(onClick={manager.openProfile(state.profileHandle!!,cursor)},enabled=!state.profileLoading,modifier=Modifier.heightIn(min=48.dp)) { Text("More recipes") } }
    }
    state.message?.let { TurnInStatusStrip(it,pitTechTokens().flag) }
    OutlinedButton(onClick={manager.openProfile(state.profileHandle!!)},enabled=!state.profileLoading,modifier=Modifier.heightIn(min=48.dp)) { Text("Refresh cookbook") }
}
