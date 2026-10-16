package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pittech.data.*
import com.pittech.domain.RecipeSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PublicSourceReviewButton(snapshot: RecipeSnapshot,manager: PublicManager) {
    val source=snapshot.publicSource ?: return
    var showing by remember(source) { mutableStateOf(false) }
    var detail by remember(source) { mutableStateOf<PublicDetail?>(null) };var offline by remember { mutableStateOf(false) };var selected by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) };var message by remember { mutableStateOf<String?>(null) };val scope=rememberCoroutineScope()
    TextButton(onClick={ showing=true },modifier=Modifier.testTag("source-review-open")) { Text("Review shared changes") }
    if(showing)ModalBottomSheet(onDismissRequest={ showing=false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Review shared changes",style=MaterialTheme.typography.titleLarge)
            Text("Compare your saved copy with the shared source. Your edits may also appear as differences. Meals already using your saved version keep it.")
            Text("Saved source reference · "+source.revisionId.take(8))
            Button(onClick={ if(!busy) { busy=true;detail=null;selected=null;message=null;scope.launch {
                try { val result=manager.detailForReview(source.itemId);detail=result.first;offline=result.second }
                catch(e: Exception) { if(e is CancellationException)throw e;message=e.message ?: "The shared recipe could not be checked." } finally { busy=false }
            } } },enabled=!busy,modifier=Modifier.testTag("source-review-check")) { Text("Check shared source") }
            detail?.let { value ->
                Text(if(offline)"Saved browsing preview only. Connect to check the current source." else if(value.revisionId==source.revisionId)"The shared reference is unchanged." else "The shared recipe has a different reference.")
                Text("Shared reference · "+value.revisionId.take(8));Text("Choose the shared dish to compare. A collection may contain several dishes.")
                val recipes=value.payload.getJSONArray("recipes")
                for(index in 0 until recipes.length())TextButton(onClick={ selected=index },modifier=Modifier.testTag("source-review-member-$index")) { Text(recipes.getJSONObject(index).getString("title")) }
                selected?.takeIf { it in 0 until recipes.length() }?.let { index ->
                    val sections=runCatching { SourceUpdateComparison.sections(snapshot,recipes.getJSONObject(index)) }.getOrNull()
                    if(sections==null)Text("This recipe could not be compared safely. Your saved copy is unchanged.")
                    else sections.forEach { section ->
                        Text(section.title+if(section.changed)" · differences" else " · unchanged",style=MaterialTheme.typography.titleMedium)
                        SourceComparisonRows("Your saved copy",section.saved);SourceComparisonRows(if(offline)"Saved shared preview" else "Shared recipe",section.shared)
                    }
                }
            }
            Text("This review does not replace recipes, reference measurements, or an active meal. To keep a different shared version, open its source and use the separate Save preview.")
            if(busy)CircularProgressIndicator();message?.let { Text(it) };TextButton(onClick={ showing=false },modifier=Modifier.testTag("source-review-done")) { Text("Done") }
        }
    }
}
@Composable private fun SourceComparisonRows(title: String,rows: List<String>) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium);if(rows.isEmpty())Text("None listed")
        rows.forEachIndexed { index,row -> Text("${index+1}. $row") }
    } }
}
