package com.pittech.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.data.PublicManager
import org.json.JSONObject

@Composable internal fun PublicDiscoveryScreen(manager: PublicManager,modifier: Modifier = Modifier,temperatureUnit: String = "°F") {
    val state by manager.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(state.appliedQuery) }
    var food by rememberSaveable { mutableStateOf(state.appliedFood) }
    var sort by rememberSaveable { mutableStateOf(state.appliedSort) }
    LaunchedEffect(manager) { if(state.items.isEmpty())manager.refresh(state.appliedSort,state.appliedFood,state.appliedQuery) }
    BackHandler(state.detailId != null || state.profileHandle != null) { manager.back() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(state.profileHandle != null) { PublicProfileContent(manager,state) } else if(state.detailId != null) {
            TextButton(onClick={ manager.back() }) { Text("Back to Discover") }
            if(state.detailLoading) CircularProgressIndicator()
            state.detail?.let { detail ->
                state.image?.let { bytes ->
                    val bitmap=remember(bytes) { BitmapFactory.decodeByteArray(bytes,0,bytes.size) }
                    if(bitmap != null)Image(bitmap.asImageBitmap(),"Selected photograph for ${detail.title}",Modifier.fillMaxWidth().heightIn(max=280.dp))
                    val caption=detail.recipes.flatMap { it.rows("assets") }.firstOrNull { it.getString("id")==detail.hero?.getString("id") }?.optString("caption").orEmpty()
                    if(caption.isNotEmpty())Text(caption,style=MaterialTheme.typography.bodySmall)
                }
                Text(detail.title,Modifier.testTag("public-title"),style=MaterialTheme.typography.headlineMedium)
                Text("Shared by ${detail.credit}")
                detail.profileUrl?.let { url->TextButton(onClick={manager.openProfile(com.pittech.data.PublicLink.destination(url).substringAfter(':'))}) { Text("View public cookbook") } }
                Text("Your saved version stays yours. Publishing a variation is a separate choice.")
                Button(onClick={ manager.preview(false) },enabled=!state.preparing,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("public-save")) { Text("Save recipes") }
                OutlinedButton(onClick={ manager.preview(true) },enabled=!state.preparing,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("public-cook")) { Text("Cook this") }
                if(state.preparing) { CircularProgressIndicator();TextButton(onClick={ manager.cancel() }) { Text("Cancel download") } }
                detail.recipes.forEach { recipe ->
                    Text(recipe.getString("title"),style=MaterialTheme.typography.titleLarge)
                    Text(recipe.optString("description"))
                    if(!recipe.isNull("weightGrams"))Text("Recorded weight · ${recipe.getDouble("weightGrams")/1000} kg")
                    recipe.rows("ingredients").forEach { Text(it.optString("name")+" · "+it.optString("amount")) }
                    recipe.rows("instructions").forEach { Text(it.optString("title"),style=MaterialTheme.typography.titleMedium);Text(it.optString("details")) }
                    if(!recipe.isNull("story"))PublicStoryView(recipe.getJSONObject("story"),temperatureUnit)
                    recipe.rows("assets").forEach { photo->detail.media.firstOrNull { it.getString("id")==photo.getString("id") }?.let { asset->
                        if(asset.getString("id")!=detail.hero?.getString("id"))PublicAdditionalPhoto(manager,detail,asset,photo.optString("caption"))
                    } }
                }
            }
            state.message?.let { Text(it,Modifier.testTag("public-message")) }
            OutlinedButton(onClick={ manager.open(state.detailId!!) },enabled=!state.detailLoading && !state.preparing,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("Refresh preview") }
        } else {
            Text("Find your next cook",style=MaterialTheme.typography.headlineMedium)
            Text("Browse, save and cook without an account. Saving makes your own private copy.")
            OutlinedTextField(query,{query=it.take(80)},label={ Text("Search recipes") },modifier=Modifier.fillMaxWidth().testTag("discover-query"))
            OutlinedTextField(food,{food=it.take(80)},label={ Text("Food type (optional)") },modifier=Modifier.fillMaxWidth().testTag("discover-food"))
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                FilterChip(sort=="liked",{sort="liked"},{Text("Most liked")})
                FilterChip(sort=="forked",{sort="forked"},{Text("Most forked")})
            }
            Button(onClick={ manager.refresh(sort,food,query) },enabled=!state.loading,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("discover-search")) { Text("Search recipes") }
            if(state.loading)CircularProgressIndicator()
            state.message?.let { Text(it,Modifier.testTag("discover-message")) }
            if(state.items.isEmpty() && !state.loading && state.message == null)Text("No recipes here yet. Try another food or search.",Modifier.testTag("discover-empty"))
            state.items.forEach { item ->
                OutlinedCard(onClick={ manager.open(item.id) },modifier=Modifier.fillMaxWidth().testTag("discover-open-${item.id}")) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(item.title,style=MaterialTheme.typography.titleLarge)
                        Text(item.credit)
                        Text("${item.likes} likes · ${item.forks} forks",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if(state.nextCursor != null)OutlinedButton(onClick={ manager.refresh(state.appliedSort,state.appliedFood,state.appliedQuery,true) },enabled=!state.loading,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("More recipes") }
        }
    }
}
private fun JSONObject.rows(key: String)=getJSONArray(key).let { array->(0 until array.length()).map(array::getJSONObject) }
