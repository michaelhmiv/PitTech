package com.pittech.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pittech.data.PublicDetail
import com.pittech.data.PublicManager
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
        Text("Recorded cook story",style=MaterialTheme.typography.titleLarge)
        if(story.isNull("foodOnOffsetMillis"))Text("Food on anchor wasn't published. Times use the shared timeline.",style=MaterialTheme.typography.bodySmall)
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
                Text("${String.format(Locale.getDefault(),"%.1f",minX)}–${String.format(Locale.getDefault(),"%.1f",maxX)} hours · $temperatureUnit",style=MaterialTheme.typography.bodySmall)
                Text(summary,style=MaterialTheme.typography.bodySmall)
                Text("Points show measurements; gaps aren't filled. Squares are manual readings; circles are recorded.",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={ showingValues=!showingValues }) { Text(if(showingValues)"Hide recorded values" else "View recorded values") }
                if(showingValues)points.forEach { point->Text(time(point.getDouble("offsetMillis"))+" · "+String.format(Locale.getDefault(),"%.1f",value(point))+" "+temperatureUnit+" · "+point.getString("quality")) }
                if(points.size<samples.size)Text("Showing ${points.size} original points for readability.",style=MaterialTheme.typography.bodySmall)
            } else Text("No measurements were published for this channel.")
        }
        story.rows("milestones").forEach { row->Text(time(row.getDouble("offsetMillis"))+" · "+row.optString("text")) }
        if(!story.isNull("foodOffOffsetMillis"))Text("Recorded Food off · "+time(story.getDouble("foodOffOffsetMillis")))
        story.getJSONObject("verdict").let { verdict->
            if(!verdict.isNull("rating"))Text("Recorded rating · ${verdict.getInt("rating")} / 5")
            if(!verdict.isNull("cookAgain"))Text(if(verdict.getBoolean("cookAgain"))"Would cook again" else "Would change next time")
            if(verdict.optString("notes").isNotEmpty())Text(verdict.getString("notes"))
        }
    }
}
@Composable internal fun PublicAdditionalPhoto(manager: PublicManager,detail: PublicDetail,asset: JSONObject,caption: String) {
    var bytes by remember(asset.getString("sha256")){mutableStateOf<ByteArray?>(null)}
    LaunchedEffect(asset.getString("sha256")){try{bytes=manager.image(detail.id,asset)}catch(_: Exception){}}
    val bitmap=remember(bytes){bytes?.let { BitmapFactory.decodeByteArray(it,0,it.size) }}
    if(bitmap!=null)Image(bitmap.asImageBitmap(),caption.ifEmpty { "Selected recipe photograph" },Modifier.fillMaxWidth().heightIn(max=280.dp)) else Text("Photograph unavailable")
    if(caption.isNotEmpty())Text(caption,style=MaterialTheme.typography.bodySmall)
}
private fun JSONObject.rows(key: String)=getJSONArray(key).let { a->(0 until a.length()).map(a::getJSONObject) }
