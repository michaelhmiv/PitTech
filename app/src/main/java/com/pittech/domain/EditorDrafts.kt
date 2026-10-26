package com.pittech.domain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * PL-24 (#172): draft persistence is the house standard. The note
 * composer's autosave/resume pattern (Slice 5) and the Start Cook setup
 * draft (gap-fill A, [SetupDraftCodec]) extend to every multi-field
 * editor: dish, dish details, recipe, playbook step, cook alert, and
 * Android Feedback (PL-27). Leaving an editor and returning restores the
 * work; saving clears the draft; the editor shows a "Resume draft"
 * affordance while a restored draft is in place.
 *
 * Drafts are device-local working state ONLY: they live in their own
 * SharedPreferences file, are never exported, synced, or backed up, and
 * hold no credentials. Codecs here never validate — an in-progress
 * draft is allowed to be invalid (that is the point of keeping it), so
 * they deliberately do NOT reuse the validating snapshot/playbook
 * codecs for storage.
 */
interface EditorDraftStore {
    fun load(key: String): String?
    fun save(key: String, value: String)
    fun clear(key: String)
}

class SharedPreferencesEditorDraftStore(context: Context) : EditorDraftStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    override fun load(key: String): String? = preferences.getString(key, null)
    override fun save(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
    override fun clear(key: String) {
        preferences.edit().remove(key).apply()
    }
    companion object { const val PREFERENCES = "pittech-editor-drafts" }
}

/** In-memory store for tests and previews. */
class MemoryEditorDraftStore : EditorDraftStore {
    private val values = mutableMapOf<String, String>()
    override fun load(key: String): String? = values[key]
    override fun save(key: String, value: String) { values[key] = value }
    override fun clear(key: String) { values.remove(key) }
}

object EditorDraftKeys {
    const val FEEDBACK = "feedback"
    fun dish(scope: String) = "dish:$scope"
    fun dishDetails(dishId: String) = "dish-details:$dishId"
    fun recipe(recipeId: String?, attemptDishId: String?) =
        "recipe:" + (recipeId ?: attemptDishId?.let { "attempt:$it" } ?: "new")
    fun step(stepId: String) = "step:$stepId"
    fun alert(scope: String) = "alert:$scope"
}

private fun JSONObject.nullString(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONObject.nullLong(key: String): Long? = if (isNull(key)) null else getLong(key)
private fun JSONObject.nullDouble(key: String): Double? = if (isNull(key)) null else getDouble(key)
private fun JSONObject.nullBool(key: String): Boolean? = if (isNull(key)) null else getBoolean(key)
private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
private fun List<String>.jsonArray(): JSONArray = JSONArray().also { array -> forEach { array.put(it) } }
private fun Map<String, String>.jsonObject(): JSONObject = JSONObject().also { o -> forEach { (k, v) -> o.put(k, v) } }
private fun JSONObject.stringMap(): Map<String, String> {
    val result = mutableMapOf<String, String>()
    keys().forEach { key -> result[key] = getString(key) }
    return result
}

/** Full [DishDraft] round trip including photo URIs (the playbook draft
 * codec omits photos; an interrupted dish edit must not lose them). */
object DishDraftCodec {
    fun encode(draft: DishDraft): String = JSONObject()
        .put("name", draft.name).put("foodType", draft.foodType).put("cut", draft.cut)
        .put("weight", draft.weightText).put("weightUnit", draft.weightUnit)
        .put("startingCondition", draft.startingCondition ?: JSONObject.NULL)
        .put("boneIn", draft.boneIn ?: JSONObject.NULL)
        .put("placement", draft.placement).put("grade", draft.gradeOrSource)
        .put("thickness", draft.thicknessNotes).put("prep", draft.prepNotes)
        .put("ingredients", JSONArray(draft.preparationItems.map { i ->
            JSONObject().put("name", i.name).put("stage", i.stage).put("brand", i.brand)
                .put("amount", i.amountText).put("unit", i.amountUnit)
        }))
        .put("photos", draft.photoUris.jsonArray())
        .toString()

    fun decode(raw: String): DishDraft? = runCatching {
        val o = JSONObject(raw)
        DishDraft(
            name = o.getString("name"), foodType = o.getString("foodType"),
            cut = o.optString("cut"), weightText = o.optString("weight"),
            weightUnit = o.optString("weightUnit", "lb"),
            startingCondition = o.nullString("startingCondition"),
            boneIn = o.nullBool("boneIn"),
            placement = o.optString("placement"), gradeOrSource = o.optString("grade"),
            thicknessNotes = o.optString("thickness"), prepNotes = o.optString("prep"),
            preparationItems = o.optJSONArray("ingredients")?.let { array ->
                (0 until array.length()).map { index ->
                    val i = array.getJSONObject(index)
                    IngredientDraft(i.getString("name"), i.optString("stage", "seasoning"),
                        i.optString("brand"), i.optString("amount"), i.optString("unit", "tbsp"))
                }
            }.orEmpty(),
            photoUris = o.optJSONArray("photos")?.strings().orEmpty(),
        )
    }.getOrNull()
}

/**
 * The recipe editor's working state. The snapshot's identity fields
 * (observations, rating, anchor, public source) are NOT stored — they
 * are not edited here and come from the request again on restore; the
 * working copies of instructions/targets are stored with their raw
 * numeric text so an invalid in-progress value survives the round trip.
 */
data class RecipeInstructionDraft(
    val id: String, val title: String, val details: String, val method: String?,
    val sourceEventId: String?, val action: String?,
    val offsetText: String, val durationText: String,
)

data class RecipeTargetDraft(
    val type: String, val valueText: String, val unit: String, val explanation: String,
)

data class RecipeEditorDraft(
    val name: String, val description: String, val servings: String, val resultNotes: String,
    val dish: DishDraft, val tags: String, val pit: String,
    val instructions: List<RecipeInstructionDraft>, val targets: List<RecipeTargetDraft>,
    val keptAssets: Set<String>, val keptPhotos: Set<String>,
    val captions: Map<String, String>, val addedPhotos: List<String>,
    val contextIds: Set<String>, val keptFacts: Set<String>,
)

object RecipeEditorDraftCodec {
    fun encode(draft: RecipeEditorDraft): String = JSONObject()
        .put("name", draft.name).put("description", draft.description)
        .put("servings", draft.servings).put("resultNotes", draft.resultNotes)
        .put("dish", JSONObject(DishDraftCodec.encode(draft.dish)))
        .put("tags", draft.tags).put("pit", draft.pit)
        .put("instructions", JSONArray(draft.instructions.map { i ->
            JSONObject().put("id", i.id).put("title", i.title).put("details", i.details)
                .put("method", i.method ?: JSONObject.NULL)
                .put("sourceEventId", i.sourceEventId ?: JSONObject.NULL)
                .put("action", i.action ?: JSONObject.NULL)
                .put("offset", i.offsetText).put("duration", i.durationText)
        }))
        .put("targets", JSONArray(draft.targets.map { t ->
            JSONObject().put("type", t.type).put("value", t.valueText)
                .put("unit", t.unit).put("explanation", t.explanation)
        }))
        .put("keptAssets", draft.keptAssets.toList().jsonArray())
        .put("keptPhotos", draft.keptPhotos.toList().jsonArray())
        .put("captions", draft.captions.jsonObject())
        .put("addedPhotos", draft.addedPhotos.jsonArray())
        .put("contextIds", draft.contextIds.toList().jsonArray())
        .put("keptFacts", draft.keptFacts.toList().jsonArray())
        .toString()

    fun decode(raw: String): RecipeEditorDraft? = runCatching {
        val o = JSONObject(raw)
        RecipeEditorDraft(
            name = o.getString("name"), description = o.optString("description"),
            servings = o.optString("servings"), resultNotes = o.optString("resultNotes"),
            dish = DishDraftCodec.decode(o.getJSONObject("dish").toString()) ?: return null,
            tags = o.optString("tags"), pit = o.optString("pit"),
            instructions = o.optJSONArray("instructions")?.let { array ->
                (0 until array.length()).map { index ->
                    val i = array.getJSONObject(index)
                    RecipeInstructionDraft(
                        i.getString("id"), i.optString("title"), i.optString("details"),
                        i.nullString("method"), i.nullString("sourceEventId"), i.nullString("action"),
                        i.optString("offset"), i.optString("duration"),
                    )
                }
            }.orEmpty(),
            targets = o.optJSONArray("targets")?.let { array ->
                (0 until array.length()).map { index ->
                    val t = array.getJSONObject(index)
                    RecipeTargetDraft(t.getString("type"), t.optString("value"), t.optString("unit", "°F"), t.optString("explanation"))
                }
            }.orEmpty(),
            keptAssets = o.optJSONArray("keptAssets")?.strings()?.toSet().orEmpty(),
            keptPhotos = o.optJSONArray("keptPhotos")?.strings()?.toSet().orEmpty(),
            captions = o.optJSONObject("captions")?.stringMap().orEmpty(),
            addedPhotos = o.optJSONArray("addedPhotos")?.strings().orEmpty(),
            contextIds = o.optJSONArray("contextIds")?.strings()?.toSet().orEmpty(),
            keptFacts = o.optJSONArray("keptFacts")?.strings()?.toSet().orEmpty(),
        )
    }.getOrNull()
}

/** The playbook step editor's working state: the step itself (via the
 * playbook codec, which does not validate single steps) plus the raw
 * numeric/clock text fields. */
data class StepEditorDraft(
    val step: PlaybookStep, val minutes: String, val temp: String, val tempUnit: String,
    val repeat: String, val time: String, val date: String,
)

object StepEditorDraftCodec {
    fun encode(draft: StepEditorDraft): String = JSONObject()
        .put("step", PlaybookCodec.stepJson(draft.step))
        .put("minutes", draft.minutes).put("temp", draft.temp).put("tempUnit", draft.tempUnit)
        .put("repeat", draft.repeat).put("time", draft.time).put("date", draft.date)
        .toString()

    fun decode(raw: String): StepEditorDraft? = runCatching {
        val o = JSONObject(raw)
        StepEditorDraft(
            step = PlaybookCodec.step(o.getJSONObject("step")),
            minutes = o.optString("minutes"), temp = o.optString("temp"),
            tempUnit = o.optString("tempUnit", "°F"), repeat = o.optString("repeat"),
            time = o.optString("time"), date = o.optString("date"),
        )
    }.getOrNull()
}

/** The cook-alert editor's working state. The rule is encoded manually:
 * [CookAlertEngine.encode] validates, and an in-progress rule (say, a
 * target alert with the temperature not typed yet) must still persist. */
data class AlertEditorDraft(
    val rule: CookAlertRule, val title: String, val tempUnit: String,
    val target: String, val low: String, val high: String,
    val dwell: String, val missing: String, val timed: Boolean,
)

object AlertEditorDraftCodec {
    fun encode(draft: AlertEditorDraft): String {
        val rule = draft.rule
        return JSONObject()
            .put("rule", JSONObject()
                .put("id", rule.id).put("type", rule.type)
                .put("dishId", rule.dishId ?: JSONObject.NULL)
                .put("probeName", rule.probeName)
                .put("targetF", rule.targetF ?: JSONObject.NULL)
                .put("lowF", rule.lowF ?: JSONObject.NULL)
                .put("highF", rule.highF ?: JSONObject.NULL)
                .put("dwell", rule.dwellMillis).put("missing", rule.missingMillis)
                .put("recoveryF", rule.recoveryF).put("repeat", rule.repeatMillis)
                .put("enabled", rule.enabled).put("stage", rule.stage))
            .put("title", draft.title).put("tempUnit", draft.tempUnit)
            .put("target", draft.target).put("low", draft.low).put("high", draft.high)
            .put("dwell", draft.dwell).put("missing", draft.missing).put("timed", draft.timed)
            .toString()
    }

    fun decode(raw: String): AlertEditorDraft? = runCatching {
        val o = JSONObject(raw)
        val r = o.getJSONObject("rule")
        AlertEditorDraft(
            rule = CookAlertRule(
                id = r.getString("id"), type = r.getString("type"),
                dishId = r.nullString("dishId"), probeName = r.optString("probeName"),
                targetF = r.nullDouble("targetF"), lowF = r.nullDouble("lowF"), highF = r.nullDouble("highF"),
                dwellMillis = r.optLong("dwell", 120_000), missingMillis = r.optLong("missing", 180_000),
                recoveryF = r.optDouble("recoveryF", 5.0), repeatMillis = r.optLong("repeat", 900_000),
                enabled = r.optBoolean("enabled", true), stage = r.optString("stage", "cooking"),
            ),
            title = o.optString("title"), tempUnit = o.optString("tempUnit", "°F"),
            target = o.optString("target"), low = o.optString("low"), high = o.optString("high"),
            dwell = o.optString("dwell"), missing = o.optString("missing"),
            timed = o.optBoolean("timed"),
        )
    }.getOrNull()
}

/** Android Feedback's working state (PL-27): kind is the FeedbackKind
 * name; drafts survive cancel and clear only on a successful submit. */
data class FeedbackDraft(
    val kind: String, val title: String, val description: String, val includeCrashReport: Boolean,
)

object FeedbackDraftCodec {
    fun encode(draft: FeedbackDraft): String = JSONObject()
        .put("kind", draft.kind).put("title", draft.title)
        .put("description", draft.description).put("includeCrashReport", draft.includeCrashReport)
        .toString()

    fun decode(raw: String): FeedbackDraft? = runCatching {
        val o = JSONObject(raw)
        FeedbackDraft(
            kind = o.getString("kind"), title = o.optString("title"),
            description = o.optString("description"), includeCrashReport = o.optBoolean("includeCrashReport"),
        )
    }.getOrNull()
}
