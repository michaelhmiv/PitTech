package com.pittech.ui

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.pittech.data.PreparedShareCard
import com.pittech.data.ShareCardContent
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.*

/** Fixed export pixels; the editor/preview retain system text scaling independently. */
object ShareCardRenderer {
    const val WIDTH = 1080
    const val HEIGHT = 1350
    private val ink = Color.rgb(36, 38, 34)
    private val supporting = Color.rgb(81, 76, 69)
    private val sage = Color.rgb(88, 102, 90)
    private val palette = listOf(Color.rgb(176, 77, 46), sage, Color.rgb(65, 108, 138), Color.rgb(139, 93, 132), Color.rgb(161, 122, 44), Color.rgb(53, 125, 120))
    private fun objects(o: JSONObject, key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    fun render(input: PreparedShareCard): ByteArray {
        val root = JSONObject(input.content); require(root.getInt("version") == 1)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        var cover: Bitmap? = null
        try {
            val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(245, 240, 231))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            fun text(value: String, x: Float, y: Float, width: Float, size: Float, lines: Int = 1, color: Int = ink, bold: Boolean = false): Float {
                val count = value.codePointCount(0,value.length)
                val display = if (count > 600) value.substring(0,value.offsetByCodePoints(0,600)) + "…" else value
                val font = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL) }
                val layout = StaticLayout.Builder.obtain(display, 0, display.length, font, width.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false).setLineSpacing(3f, 1f).setMaxLines(lines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
                canvas.save(); canvas.translate(x, y); layout.draw(canvas); canvas.restore(); return layout.height.toFloat()
            }
            val left = 72f; val width = 936f
            text("PITTECH COOKBOOK", left, 60f, width, 22f, color = sage, bold = true)
            var y = 108f
            y += text(root.getString("title"), left, y, width, 56f, 3, bold = true) + 18f
            val dishes = root.getJSONArray("dishes"); require(dishes.length() in 1..20)
            val names = (0 until minOf(3, dishes.length())).map { dishes.getString(it) }
            y += text(names.joinToString(" · ") + if (dishes.length() > 3) " · +${dishes.length() - 3} more" else "", left, y, width, 24f, 2, sage) + 24f
            val milestones = objects(root, "milestones"); val stats = objects(root, "stats"); val note = root.optString("note")
            require(milestones.size <= 5 && stats.size <= 3)
            val relay = root.optJSONObject("relay"); val footer = if (relay == null) 1258f else 1160f
            val remaining = (if (milestones.isEmpty()) 0f else 34 + 44f * milestones.size + 20) + (if (stats.isEmpty()) 0f else 100f) + (if (note.isEmpty()) 0f else 88f)
            if (root.getString("hero") != "none") {
                val height = minOf(390f, footer - 32 - y - remaining).coerceAtLeast(180f)
                val rect = RectF(left, y, left + width, y + height)
                if (root.getString("hero") == "photo") {
                    val bytes = input.photo ?: error("Choose an included cover photo.")
                    val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("This cover photo could not be rendered."); cover = image
                    val scale = max(rect.width() / image.width, rect.height() / image.height) * root.getDouble("zoom").toFloat()
                    val w = rect.width() / scale; val h = rect.height() / scale
                    val cropWidth = w.roundToInt().coerceIn(1,image.width); val cropHeight = h.roundToInt().coerceIn(1,image.height)
                    val x = ((image.width - cropWidth) * root.getDouble("cropX")).roundToInt(); val top = ((image.height - cropHeight) * root.getDouble("cropY")).roundToInt()
                    val crop = Rect(x,top,x+cropWidth,top+cropHeight)
                    val path = Path().apply { addRoundRect(rect, 24f, 24f, Path.Direction.CW) }
                    canvas.save(); canvas.clipPath(path); canvas.drawBitmap(image, crop, rect, paint); canvas.restore()
                } else chart(canvas, root, rect, ::text)
                y += height + 24f
            }
            if (milestones.isNotEmpty()) {
                text("RECORDED MILESTONES", left, y, width, 20f, color = sage, bold = true); y += 34
                for (item in milestones) {
                    val offset = if (item.isNull("offsetMillis")) null else item.getLong("offsetMillis")
                    text(ShareCardContent.offset(offset), left, y, 160f, 25f, color = supporting)
                    text("${item.getString("dish")} · ${item.getString("title")}", left + 170, y, width - 170, 27f); y += 44
                }
                y += 20
            }
            if (stats.isNotEmpty()) {
                val cell = width / stats.size
                stats.forEachIndexed { i, item -> text(item.getString("label"), left + i * cell, y, cell - 16, 20f, color = supporting); text(item.getString("value"), left + i * cell, y + 32, cell - 16, 31f, bold = true) }
                y += 100
            }
            if (note.isNotEmpty()) text("“$note”", left, y, width, 28f, 2)
            paint.color = Color.rgb(226, 217, 205); canvas.drawLine(left, footer - 20, left + width, footer - 20, paint)
            if (relay != null) {
                text("GET THE COOK PACK · ${relay.getString("code")}", left, footer, width, 28f, bold = true)
                text(relay.getString("url").removePrefix("https://"), left, footer + 42, width, 24f, color = supporting)
            }
            text("PitTech", left, 1285f, 300f, 32f, bold = true)
            text("Cook. Keep. Share.", left + 450, 1292f, width - 450, 22f, color = supporting)
            return ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
        } finally { cover?.recycle(); bitmap.recycle() }
    }
    private fun chart(canvas: Canvas, root: JSONObject, rect: RectF,
        text: (String, Float, Float, Float, Float, Int, Int, Boolean) -> Float) {
        val curves = objects(root, "curves"); val all = curves.flatMap { c -> c.getJSONArray("segments").let { a -> (0 until a.length()).flatMap { i -> a.getJSONArray(i).let { s -> (0 until s.length()).map { s.getJSONObject(it) } } } } }
        require(all.isNotEmpty() && all.all { it.getDouble("x").isFinite() && it.getDouble("y").isFinite() }) { "These readings cannot be drawn. Choose No hero or share a Cook Pack." }
        val x0 = all.minOf { it.getDouble("x") }; val x1 = max(x0 + 1, all.maxOf { it.getDouble("x") })
        val low = all.minOf { it.getDouble("y") }; val high = all.maxOf { it.getDouble("y") }; val pad = max(5.0, max(high - low, abs(low) * .01) * .1)
        val y0 = low - pad; val y1 = high + pad; require((y1 - y0).isFinite() && y1 > y0) { "These readings cannot be drawn. Choose No hero or share a Cook Pack." }
        val p = RectF(rect.left + 68, rect.top + 18, rect.right - 20, rect.bottom - 106)
        fun x(v: Double) = p.left + ((v - x0) / (x1 - x0) * p.width()).toFloat()
        fun y(v: Double) = p.bottom - ((v - y0) / (y1 - y0) * p.height()).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f }
        repeat(3) { i -> val v = y0 + (y1 - y0) * i / 2; paint.color = Color.rgb(226,217,205); canvas.drawLine(p.left, y(v), p.right, y(v), paint); text(String.format(Locale.US,"%.0f",v),rect.left,y(v)-12,62f,18f,1,supporting,false) }
        text(root.getString("unit"), rect.left, rect.top - 8, 62f, 18f, 1, supporting, false)
        text("Simplified trace · ${curves.size} of ${root.getInt("channelCount")} channels",rect.right-370,rect.top-8,370f,18f,1,supporting,false)
        text("${String.format(Locale.US,"%.0f",x0)} → ${String.format(Locale.US,"%.0f",x1)} min since each dish's Food on", p.left, p.bottom + 8, p.width(), 19f, 1, supporting, false)
        curves.forEachIndexed { i, curve ->
            paint.color = palette[i % palette.size]; paint.strokeWidth = 4f
            val segments = curve.getJSONArray("segments")
            for (s in 0 until segments.length()) {
                val points = segments.getJSONArray(s)
                for (j in 0 until points.length()) {
                    val a = points.getJSONObject(j); val at = PointF(x(a.getDouble("x")), y(a.getDouble("y")))
                    if (j > 0) { val b = points.getJSONObject(j-1); canvas.drawLine(x(b.getDouble("x")), y(b.getDouble("y")), at.x, at.y, paint) }
                    canvas.drawCircle(at.x, at.y, 4f, paint)
                }
            }
            text("${i+1}. ${curve.getString("label")}", rect.left + i%2 * rect.width()/2, rect.bottom - 70 + i/2*24, rect.width()/2 - 10, 19f, 1, palette[i % palette.size], false)
        }
    }
}
