package com.pittech.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.pittech.data.PublicDetail
import com.pittech.data.PublicManager
import com.pittech.domain.CookInputVocabulary
import com.pittech.domain.RecipeReferenceTimeline
import com.pittech.domain.WrapMaterial
import org.json.JSONObject
import java.util.Locale

@Composable internal fun PublicStoryView(story: JSONObject,temperatureUnit: String) {
    val channels=remember(story){story.rows("channels")}
    var showingValues by remember(story) { mutableStateOf(false) }
    var selected by remember(story){mutableStateOf(channels.firstOrNull()?.getString("id").orEmpty())}
    val channel=channels.firstOrNull { it.getString("id")==selected }
    val samples=remember(story,selected){story.rows("measurements").filter { it.getString("channelId")==selected }.sortedBy { it.getLong("offsetMillis") }}
    val points=remember(samples){
        val stride=maxOf(1,kotlin.math.ceil(samples.size/1000.0).toInt())
        val min=samples.indices.minByOrNull { samples[it].getDouble("valueCelsius") };val max=samples.indices.maxByOrNull { samples[it].getDouble("valueCelsius") }
        samples.filterIndexed { index,_->index%stride==0 || index==samples.lastIndex || index==min || index==max }
    }
    val anchor=if(story.isNull("foodOnOffsetMillis"))0.0 else story.getDouble("foodOnOffsetMillis")
    fun time(offset: Double)=com.pittech.domain.RecipeReferenceTimeline.relativeLabel((offset-anchor).toLong())
    fun value(sample: JSONObject)=sample.getDouble("valueCelsius").let { if(temperatureUnit=="°C")it else it*9/5+32 }
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        TurnInMetaLabel("Recorded story")
        Text("Recorded cook story",style=PitTechTextStyles.bannerCard)
        if(story.isNull("foodOnOffsetMillis"))Text("Food on anchor wasn't published. Times use the shared timeline.",style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)
        if(channel!=null) {
            val labels=channels.mapIndexed { index,c -> c.getString("label")+" · "+c.getString("role")+" · channel "+(index+1) }
            SimpleDropdownField("Reference channel",labels[channels.indexOf(channel)],labels,{ label->selected=channels[labels.indexOf(label)].getString("id") })
            if(samples.isNotEmpty()) {
                val minX=(samples.minOf { it.getDouble("offsetMillis") }-anchor)/3_600_000;val maxX=(samples.maxOf { it.getDouble("offsetMillis") }-anchor)/3_600_000
                val minY=samples.minOf(::value);val maxY=samples.maxOf(::value)
                val summary="${channel.getString("label")} · ${channel.getString("role")} · ${samples.size} samples · ${String.format(Locale.getDefault(),"%.1f",minY)} to ${String.format(Locale.getDefault(),"%.1f",maxY)} $temperatureUnit"
                val color=MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(220.dp).semantics { contentDescription=summary }) {
                    val radius=4.dp.toPx()
                    val spanX=if(maxX==minX)1.0 else maxX-minX;val spanY=if(maxY==minY)1.0 else maxY-minY
                    for(point in points){
                        val x=if(maxX==minX)size.width/2 else (20+(((point.getDouble("offsetMillis")-anchor)/3_600_000-minX)/spanX)*(size.width-40)).toFloat()
                        val y=if(maxY==minY)size.height/2 else (size.height-20-((value(point)-minY)/spanY)*(size.height-40)).toFloat()
                        if(point.getString("quality")=="manual")drawRect(color,Offset(x-radius,y-radius),Size(2*radius,2*radius)) else drawCircle(color,radius,Offset(x,y))
                    }
                }
                Text("${String.format(Locale.getDefault(),"%.1f",minX)}–${String.format(Locale.getDefault(),"%.1f",maxX)} hours · $temperatureUnit",style=PitTechTextStyles.monoMeta,color=pitTechTokens().inkMuted)
                Text(summary,style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)
                Text("Points show measurements; gaps aren't filled. Squares are manual readings; circles are recorded.",style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)
                TextButton(onClick={ showingValues=!showingValues }) { Text(if(showingValues)"Hide recorded values" else "View recorded values") }
                if(showingValues)points.forEach { point->Text(time(point.getDouble("offsetMillis"))+" · "+String.format(Locale.getDefault(),"%.1f",value(point))+" "+temperatureUnit+" · "+point.getString("quality"),style=PitTechTextStyles.monoMeta) }
                if(points.size<samples.size)Text("Showing ${points.size} original points for readability.",style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)
            } else Text("No measurements were published for this channel.")
        }
        story.rows("milestones").forEach { row->
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(time(row.getDouble("offsetMillis")),style=PitTechTextStyles.monoMeta,color=pitTechTokens().emberText)
                Text(row.optString("text"),style=MaterialTheme.typography.bodyMedium)
            }
        }
        if(!story.isNull("foodOffOffsetMillis"))Text("Recorded Food off · "+time(story.getDouble("foodOffOffsetMillis")),style=PitTechTextStyles.monoMeta,color=pitTechTokens().inkMuted)
        // Publishable Cooks (issue #250): the dish's recorded inputs, lifted
        // into the story at publish time. Only cook publications carry the
        // key; a null value means the cook recorded no inputs, and fields
        // read as not recorded — never guessed.
        if(story.has("inputs")) {
            val inputs=if(story.isNull("inputs"))null else story.getJSONObject("inputs")
            TurnInMetaLabel("Cook inputs")
            if(inputs==null)Text(CookInputVocabulary.NOT_RECORDED_LABEL)
            else {
                Text("Wrap · "+(if(inputs.isNull("wrapMaterial"))CookInputVocabulary.NOT_RECORDED_LABEL else WrapMaterial.fromArchive(inputs.getString("wrapMaterial"))?.label ?: CookInputVocabulary.NOT_RECORDED_LABEL))
                Text("Rub · "+(if(inputs.isNull("rub"))CookInputVocabulary.NOT_RECORDED_LABEL else inputs.getString("rub")))
                Text("Spritz · "+when {
                    inputs.isNull("spritz")->CookInputVocabulary.NOT_RECORDED_LABEL
                    inputs.getBoolean("spritz")->"Yes"+(if(inputs.isNull("spritzMedium"))"" else " · "+inputs.getString("spritzMedium"))
                    else->"No"
                })
            }
        }
        story.getJSONObject("verdict").let { verdict->
            if(!verdict.isNull("rating")) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    TurnInStars(verdict.getDouble("rating"))
                    Text("Recorded rating · ${java.math.BigDecimal.valueOf(verdict.getDouble("rating")).stripTrailingZeros().toPlainString()} / 5",style=MaterialTheme.typography.bodyMedium)
                }
            }
            if(!verdict.isNull("cookAgain"))Text(if(verdict.getBoolean("cookAgain"))"Would cook again" else "Would change next time")
            if(verdict.optString("notes").isNotEmpty())Text(verdict.getString("notes"))
        }
    }
}
private fun sessionDurationLabel(millis: Long): String { val minutes=millis/60_000; return if(minutes>=60)"${minutes/60}h ${minutes%60}m" else "${minutes}m" }

/**
 * Publishable Cooks (issue #250): the session header of a cook publication —
 * the event the dishes shared. Rendered from the payload's session object on
 * both the public detail page and the publisher's own preview.
 */
@Composable internal fun PublicSessionView(session: JSONObject,temperatureUnit: String) {
    fun temperature(value: Double,unit: String): String {
        val converted=when { unit==temperatureUnit->value; unit=="°C" && temperatureUnit=="°F"->value*9/5+32; unit=="°F" && temperatureUnit=="°C"->(value-32)*5/9; else->value }
        val shown=if(unit in setOf("°C","°F"))temperatureUnit else unit
        return java.math.BigDecimal.valueOf(converted).stripTrailingZeros().toPlainString()+" "+shown
    }
    val facts=mutableListOf<String>()
    if(!session.isNull("smokerName"))facts+="Smoker · "+session.getString("smokerName")
    session.optJSONObject("setpoint")?.let { facts+="Ran at "+temperature(it.getDouble("value"),it.getString("unit")) }
    if(!session.isNull("fuelType"))facts+="Fuel · "+session.getString("fuelType")
    if(!session.isNull("woodOrPelletBlend"))facts+="Wood · "+session.getString("woodOrPelletBlend")
    if(!session.isNull("durationMillis"))facts+="Cooked for "+sessionDurationLabel(session.getLong("durationMillis"))
    if(!session.isNull("serveAtOffsetMillis"))facts+="Serve time "+RecipeReferenceTimeline.relativeLabel(session.getLong("serveAtOffsetMillis"))
    val weather=mutableListOf<String>()
    if(!session.isNull("outdoorTemperatureValue"))weather+=temperature(session.getDouble("outdoorTemperatureValue"),session.optString("outdoorTemperatureUnit").ifBlank { temperatureUnit })
    if(session.optString("weatherNotes").isNotEmpty())weather+=session.getString("weatherNotes")
    if(session.optString("windNotes").isNotEmpty())weather+=session.getString("windNotes")
    Column(verticalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.testTag("public-session")) {
        TurnInMetaLabel("The cook")
        facts.forEach { Text(it,style=MaterialTheme.typography.bodyMedium) }
        if(weather.isNotEmpty())Text(weather.joinToString(" · "),style=MaterialTheme.typography.bodyMedium)
        if(session.optString("notes").isNotEmpty())Text(session.getString("notes"))
    }
}

/**
 * The shared timeline of a cook publication: every dish's milestones placed
 * on the one session clock (story anchor offset + milestone offset). Events
 * recorded once for the whole session appear in several dishes' stories
 * under the same milestone id and are shown once, unlabeled; dish events
 * carry their dish's name. View-only — saving a cook forks its dishes as
 * recipes; the session itself never transfers (issue #250, Option A).
 */
@Composable internal fun PublicSessionTimeline(detail: PublicDetail) {
    val merged=remember(detail.id) {
        val byId=linkedMapOf<String,Triple<Long,String,MutableSet<String>>>()
        var unplaced=false
        detail.recipes.forEach { recipe ->
            val story=recipe.optJSONObject("story") ?: return@forEach
            val milestones=story.rows("milestones")
            if(story.isNull("anchorOffsetMillis")) { if(milestones.isNotEmpty())unplaced=true; return@forEach }
            val anchorOffset=story.getLong("anchorOffsetMillis")
            milestones.forEach { milestone ->
                val id=milestone.getString("id")
                val existing=byId[id]
                if(existing==null)byId[id]=Triple(anchorOffset+milestone.getLong("offsetMillis"),milestone.getString("text"),mutableSetOf(recipe.getString("title")))
                else existing.third.add(recipe.getString("title"))
            }
        }
        byId.values.map { Triple(it.first,it.second,if(it.third.size==1)it.third.single() else null) }
            .sortedWith(compareBy({ it.first },{ it.third ?: "" },{ it.second })) to unplaced
    }
    if(merged.first.isNotEmpty())Column(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.testTag("public-session-timeline")) {
        TurnInMetaLabel("Shared timeline")
        Text("One timeline for the whole cook",style=PitTechTextStyles.bannerCard)
        merged.first.forEach { row ->
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(RecipeReferenceTimeline.relativeLabel(row.first),style=PitTechTextStyles.monoMeta,color=pitTechTokens().emberText)
                Column {
                    row.third?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted) }
                    Text(row.second,style=MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if(merged.second)Text("One dish's story couldn't be placed on the session clock; its own timeline is in its story below.",style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)
    }
}

@Composable internal fun PublicAdditionalPhoto(manager: PublicManager,detail: PublicDetail,asset: JSONObject,caption: String) {
    var bytes by remember(asset.getString("sha256")){mutableStateOf<ByteArray?>(null)}
    LaunchedEffect(asset.getString("sha256")){try{bytes=manager.image(detail.id,asset)}catch(_: Exception){}}
    val bitmap=remember(bytes){bytes?.let { BitmapFactory.decodeByteArray(it,0,it.size) }}
    if(bitmap!=null)Image(bitmap.asImageBitmap(),caption.ifEmpty { "Selected recipe photograph" },Modifier.fillMaxWidth().heightIn(max=280.dp)) else Text("Photograph unavailable")
    if(caption.isNotEmpty())Text(caption,style=MaterialTheme.typography.bodySmall,color=pitTechTokens().inkMuted)
}
private fun JSONObject.rows(key: String)=getJSONArray(key).let { a->(0 until a.length()).map(a::getJSONObject) }
