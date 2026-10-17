package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pittech.data.PublicManager
import com.pittech.data.PublicPresentation

@Composable internal fun PublicProfileContent(manager: PublicManager,state: PublicPresentation) {
    TextButton(onClick={manager.back()}) { Text("Back to Discover") }
    if(state.profileLoading)CircularProgressIndicator()
    state.profileName?.let { name->
        Text(name,style=MaterialTheme.typography.headlineMedium)
        Text("@${state.profileHandle}")
        Text("Named publications from this public cookbook. Saving a recipe makes a private copy.")
        state.profileItems.forEach { item->
            OutlinedCard(onClick={manager.open(item.id)},modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(item.title,style=MaterialTheme.typography.titleMedium)
                    Text("${item.likes} likes · ${item.forks} forks",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        if(state.profileItems.isEmpty() && !state.profileLoading)Text("No named publications here yet.")
        state.profileCursor?.let { cursor->OutlinedButton(onClick={manager.openProfile(state.profileHandle!!,cursor)},enabled=!state.profileLoading) { Text("More recipes") } }
    }
    state.message?.let { Text(it) }
    OutlinedButton(onClick={manager.openProfile(state.profileHandle!!)},enabled=!state.profileLoading) { Text("Refresh cookbook") }
}
