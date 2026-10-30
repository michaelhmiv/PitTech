package com.pittech.data

import com.pittech.domain.DishDraft
import com.pittech.domain.IngredientDraft
import com.pittech.domain.PlaybookTarget
import com.pittech.domain.PublicRecipeSource
import com.pittech.domain.RecipeInstruction
import com.pittech.domain.RecipeObservation
import com.pittech.domain.RecipeSnapshot
import com.pittech.domain.RecipeSnapshotCodec
import com.pittech.domain.WrapMaterial
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Flowing Slice 5, element 4.2: Fork — a publication saved as *mine*
 * from the detail payload already on the device, with no Cook Pack
 * download round-trip. The conversion mirrors the server's pack
 * builder (`api-service/src/publication-cook-pack.ts`) field for
 * field, so a fork lands in exactly the end state a pack import
 * produces — same snapshot shape, same frozen story in the companion
 * record's entry (found by the guide's pin via sourceRevisionId),
 * same "Saved public recipe" change note when nothing was tweaked.
 * The two honest transport differences: photo bytes are the original
 * verified bytes from the media endpoint rather than the pack's JPEG
 * re-encode, and identities are minted on device instead of by the
 * server. The result feeds [CookRepository.importSnapshot] — the same
 * restore writer the pack import confirms into.
 */
internal object PublicationFork {
    /** The pack builder's internal coordinate origin: Date.UTC(2000,0,1).
     * Relative timing only; never displayed as a cook date. */
    const val ORIGIN: Long = 946_684_800_000L

    enum class WrapChoice { AS_WRITTEN, NO_WRAP, FOIL, BUTCHER_PAPER }

    /** One recipe's tweak-lite decisions (element 4.3): rub, wrap,
     * target — plus the fork's name for it. */
    data class Tweak(
        val name: String,
        val rub: String,
        val wrap: WrapChoice,
        val targetF: Double?,
        /** True when [targetF] was pre-filled from the source cook's
         * last recorded food reading — the sheet says so. */
        val targetFromStory: Boolean = false,
    )

    data class SavedFork(val recipeId: String, val revisionId: String, val name: String)

    class Prepared(
        val snapshot: ExportSnapshot,
        /** Attachment row id → the detail media entry whose verified
         * bytes fill it. Two rows (recipe asset + companion photo) can
         * point at the same media entry. */
        val photoMedia: Map<String, JSONObject>,
        val saved: List<SavedFork>,
    )

    private fun JSONObject.rows(key: String): List<JSONObject> =
        optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()

    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    /** JS `String(number)` for the values a weight can take: integral
     * values carry no decimal point ("5", not "5.0"). */
    internal fun jsNumber(value: Double): String =
        if (value.isFinite() && value == Math.floor(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()

    private fun formatTarget(value: Double): String = jsNumber(value)

    /** The sheet's opening state for one payload recipe: the book's
     * "<title> — my version" name, the rub as written (publications
     * carry no rub entry, so blank), wrap as written, and a target
     * pre-filled from the source cook's last recorded food reading
     * when the story carries one — a recorded fact, never a guess. */
    fun prefill(detail: PublicDetail, recipeIndex: Int): Tweak {
        val recipe = detail.recipes[recipeIndex]
        val story = recipe.optJSONObject("story")
        val lastFood = story?.rows("measurements")?.filter { it.optString("role") == "food" }
            ?.maxByOrNull { it.optLong("offsetMillis") }
        val targetF = lastFood?.let { it.getDouble("valueCelsius") * 9.0 / 5.0 + 32.0 }
        return Tweak(
            name = recipe.getString("title") + " — my version",
            rub = "",
            wrap = WrapChoice.AS_WRITTEN,
            targetF = targetF?.let { Math.round(it).toDouble() },
            targetFromStory = targetF != null,
        )
    }

    /** The decisions exactly as written — the silent fork behind
     * "Plan this" on the publication page (no sheet shown). */
    fun untweaked(detail: PublicDetail): List<Tweak> = detail.recipes.map { recipe ->
        Tweak(name = recipe.getString("title"), rub = "", wrap = WrapChoice.AS_WRITTEN, targetF = null)
    }

    fun prepare(detail: PublicDetail, tweaks: List<Tweak>, now: Long): Prepared {
        require(tweaks.size == detail.recipes.size) { "Fork decisions are needed for every recipe in this publication." }
        val packId = UUID.randomUUID().toString()
        val recipes = mutableListOf<SavedRecipe>()
        val revisions = mutableListOf<RecipeRevision>()
        val assets = mutableListOf<RecipeAsset>()
        val companionPhotos = mutableListOf<CompanionPhoto>()
        val photoMedia = mutableMapOf<String, JSONObject>()
        val entries = JSONArray()
        val saved = mutableListOf<SavedFork>()
        var heroPhotoId: String? = null
        val mediaById = detail.media.associateBy { it.getString("id") }

        detail.recipes.forEachIndexed { index, recipe ->
            val tweak = tweaks[index]
            val recipeId = UUID.randomUUID().toString()
            val revisionId = UUID.randomUUID().toString()
            val entryId = UUID.randomUUID().toString()
            val converted = convertRecipe(recipe, detail)
            val snapshot = applyTweak(converted.snapshot, tweak)
            val snapshotJson = RecipeSnapshotCodec.encode(snapshot)
            val changeNotes = changeNotes(detail, recipe, converted.snapshot, tweak)
            recipes += SavedRecipe(recipeId, snapshot.name, snapshot.description, JSONArray(snapshot.tags).toString(), revisionId, now, now, null, false)
            revisions += RecipeRevision(revisionId, recipeId, null, 1, null, null, snapshot.schemaVersion, snapshotJson, now, changeNotes)
            val photoIds = mutableListOf<String>()
            recipe.rows("assets").forEach { asset ->
                val media = mediaById[asset.getString("id")] ?: error("A photo in this publication is unavailable.")
                val extension = when (media.getString("mimeType")) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                val caption = asset.optString("caption")
                val assetRowId = UUID.randomUUID().toString()
                val photoRowId = UUID.randomUUID().toString()
                assets += RecipeAsset(assetRowId, revisionId, "photos/$assetRowId.$extension", media.getString("mimeType"), caption, null, now)
                companionPhotos += CompanionPhoto(photoRowId, packId, "photos/$photoRowId.$extension", media.getString("mimeType"), caption, index, entryId)
                photoMedia[assetRowId] = media
                photoMedia[photoRowId] = media
                photoIds += photoRowId
                if (detail.payload.optString("heroAssetId") == asset.getString("id")) heroPhotoId = photoRowId
            }
            entries.put(JSONObject()
                .put("id", entryId)
                .put("sourceRevisionId", revisionId)
                .put("snapshotJson", snapshotJson)
                .put("story", converted.storyJson)
                .put("included", true)
                .put("includeStory", converted.storyPresent)
                .put("photoIds", JSONArray(photoIds)))
            saved += SavedFork(recipeId, revisionId, snapshot.name)
        }
        val kind = if (detail.recipes.size == 1) "recipe_reference" else "collection_revision"
        val payload = JSONObject()
            .put("version", 1)
            .put("title", detail.title)
            .put("identityMode", "anonymous")
            .put("collectionId", packId)
            .put("entries", entries)
        if (heroPhotoId != null) payload.put("heroPhotoId", heroPhotoId)
        val record = CompanionRecord(packId, kind, null, detail.title, payload.toString(), now, now)
        return Prepared(
            snapshot = ExportSnapshot(
                cooks = emptyList(), dishes = emptyList(), ingredients = emptyList(), events = emptyList(),
                readings = emptyList(), targets = emptyList(), results = emptyList(), devices = emptyList(),
                probes = emptyList(), photos = emptyList(),
                companionRecords = listOf(record), companionPhotos = companionPhotos,
                recipes = recipes, recipeRevisions = revisions, recipeAssets = assets,
            ),
            photoMedia = photoMedia,
            saved = saved,
        )
    }

    private fun changeNotes(detail: PublicDetail, recipe: JSONObject, converted: RecipeSnapshot, tweak: Tweak): String {
        val parts = mutableListOf<String>()
        // A publication payload carries no rub at all, so any rub typed
        // in the sheet is a real change by construction.
        if (tweak.rub.isNotBlank()) parts += "Rub: ${tweak.rub.trim()}"
        if (tweak.wrap != WrapChoice.AS_WRITTEN) parts += "Wrap: ${wrapMaterial(tweak.wrap).label}"
        if (tweak.targetF != null) parts += "Target: ${formatTarget(tweak.targetF)}°F"
        val renamed = tweak.name.trim() != recipe.getString("title")
        if (parts.isEmpty() && !renamed) return "Saved public recipe"
        return "Fork of ${detail.title}" + if (parts.isEmpty()) "" else " — " + parts.joinToString(" · ")
    }

    private fun wrapMaterial(choice: WrapChoice): WrapMaterial = when (choice) {
        WrapChoice.NO_WRAP -> WrapMaterial.NONE
        WrapChoice.FOIL -> WrapMaterial.FOIL
        WrapChoice.BUTCHER_PAPER -> WrapMaterial.BUTCHER_PAPER
        WrapChoice.AS_WRITTEN -> error("As-written has no material.")
    }

    private fun applyTweak(snapshot: RecipeSnapshot, tweak: Tweak): RecipeSnapshot {
        var result = snapshot
        if (tweak.name.isNotBlank()) result = result.copy(name = tweak.name.trim())
        if (tweak.rub.isNotBlank()) {
            // The rub lands as the rub-staged preparation item — the
            // form every reader reads back for a saved recipe (cook
            // creation's retain fallback, the version editor's rub
            // field, the §4.2 provenance capture). The snapshot codec
            // does not carry DishDraft.rub, so setting the field alone
            // would silently drop the decision at encode time (caught
            // by PublicationForkTest on the first CI run).
            val items = result.dish.preparationItems.filterNot { it.stage.equals("rub", ignoreCase = true) } +
                IngredientDraft(name = tweak.rub.trim(), stage = "rub")
            result = result.copy(dish = result.dish.copy(preparationItems = items))
        }
        if (tweak.wrap != WrapChoice.AS_WRITTEN) {
            val label = wrapMaterial(tweak.wrap).label
            val wrapIndex = result.instructions.indexOfFirst { it.action == "wrap" }
            result = if (wrapIndex >= 0) {
                result.copy(instructions = result.instructions.mapIndexed { i, instruction ->
                    if (i == wrapIndex) instruction.copy(method = label) else instruction
                })
            } else {
                result.copy(instructions = result.instructions + RecipeInstruction(
                    id = UUID.randomUUID().toString(), title = "Wrap", details = "", method = label, action = "wrap",
                ))
            }
        }
        if (tweak.targetF != null) {
            result = result.copy(targets = listOf(PlaybookTarget(0, "personal_finish", tweak.targetF, "°F", null)))
        }
        return result
    }

    private class Converted(val snapshot: RecipeSnapshot, val storyJson: JSONObject, val storyPresent: Boolean)

    /** One payload recipe → the snapshot + frozen story the pack
     * builder would have written for it. */
    private fun convertRecipe(recipe: JSONObject, detail: PublicDetail): Converted {
        val story = recipe.optJSONObject("story")
        val anchor: Long? = if (story != null && !story.isNull("foodOnOffsetMillis")) ORIGIN + story.getLong("foodOnOffsetMillis") else null
        val observations = mutableListOf<RecipeObservation>()
        val observationJson = JSONArray()
        fun addObservation(observation: RecipeObservation) {
            observations += observation
            observationJson.put(JSONObject()
                .put("sourceEventId", observation.sourceEventId)
                .put("occurredAtUtcMillis", observation.occurredAtUtcMillis)
                .put("timeZoneId", observation.timeZoneId)
                .put("title", observation.title)
                .put("details", observation.details)
                .put("action", observation.action ?: JSONObject.NULL)
                .put("offsetMillis", observation.offsetMillis ?: JSONObject.NULL)
                .put("recordedTemperatures", JSONArray()))
        }
        story?.rows("milestones")?.forEach { milestone ->
            val text = milestone.optString("text")
            val action = milestone.stringOrNull("action")
            addObservation(RecipeObservation(
                sourceEventId = UUID.randomUUID().toString(),
                occurredAtUtcMillis = ORIGIN + milestone.getLong("offsetMillis"),
                timeZoneId = "UTC",
                title = text.ifEmpty { action.orEmpty() }.ifEmpty { "Recorded action" },
                details = text,
                action = action,
                offsetMillis = anchor?.let { ORIGIN + milestone.getLong("offsetMillis") - it },
                temperatureContextJson = null,
            ))
        }
        if (story != null && !story.isNull("foodOnOffsetMillis") && observations.none { it.action == "food_on" }) {
            addObservation(RecipeObservation(
                sourceEventId = UUID.randomUUID().toString(),
                occurredAtUtcMillis = ORIGIN + story.getLong("foodOnOffsetMillis"),
                timeZoneId = "UTC", title = "Food on", details = "", action = "food_on",
                offsetMillis = 0L, temperatureContextJson = null,
            ))
        }
        if (story != null && !story.isNull("foodOffOffsetMillis") && observations.none { it.action == "remove" }) {
            addObservation(RecipeObservation(
                sourceEventId = UUID.randomUUID().toString(),
                occurredAtUtcMillis = ORIGIN + story.getLong("foodOffOffsetMillis"),
                timeZoneId = "UTC", title = "Food off", details = "", action = "remove",
                offsetMillis = anchor?.let { ORIGIN + story.getLong("foodOffOffsetMillis") - it },
                temperatureContextJson = null,
            ))
        }
        val channels = story?.rows("channels")?.associateBy { it.getString("id") } ?: emptyMap()
        val probeIds = channels.keys.associateWith { UUID.randomUUID().toString() }
        val readingsJson = JSONArray()
        story?.rows("measurements")?.forEach { measurement ->
            val channel = channels[measurement.getString("channelId")]
                ?: error("This publication's story names a channel it does not carry.")
            val quality = measurement.optString("quality")
            readingsJson.put(JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("probeId", probeIds.getValue(measurement.getString("channelId")))
                .put("probeName", channel.optString("label"))
                .put("measurementType", measurement.getString("role"))
                .put("value", measurement.getDouble("valueCelsius"))
                .put("unit", "°C")
                .put("measuredAtUtcMillis", ORIGIN + measurement.getLong("offsetMillis"))
                .put("timeZoneId", "UTC")
                .put("source", if (quality == "manual") "manual" else "recorded")
                .put("qualityStatus", if (quality == "recovered") "recovered" else "valid")
                .put("samplingIntervalMillis", 0))
        }
        val gapsJson = JSONArray()
        story?.rows("gaps")?.forEach { gap ->
            gapsJson.put(ORIGIN + gap.getLong("fromMillis"))
            gapsJson.put(ORIGIN + gap.getLong("toMillis"))
        }
        val storyJson = JSONObject()
            .put("version", 1)
            .put("attemptId", story?.stringOrNull("attemptId") ?: JSONObject.NULL)
            .put("anchorUtcMillis", anchor ?: JSONObject.NULL)
            .put("observations", observationJson)
            .put("readings", readingsJson)
            .put("connectionGaps", gapsJson)
            .put("results", JSONArray())
            .put("publicStory", story ?: JSONObject.NULL)
        val verdict = story?.optJSONObject("verdict")
        val title = recipe.getString("title")
        val snapshot = RecipeSnapshot(
            name = title,
            dish = DishDraft(
                name = title,
                foodType = recipe.optString("foodType"),
                cut = recipe.optString("cut"),
                weightText = if (recipe.isNull("weightGrams")) "" else jsNumber(recipe.getDouble("weightGrams") / 1000.0),
                weightUnit = "kg",
                placement = "",
                gradeOrSource = "",
                thicknessNotes = "",
                prepNotes = recipe.optString("method"),
                preparationItems = recipe.rows("ingredients").map { ingredient ->
                    IngredientDraft(
                        name = ingredient.getString("name"),
                        stage = "main_ingredient",
                        brand = "",
                        amountText = "",
                        amountUnit = ingredient.optString("amount"),
                    )
                },
            ),
            description = recipe.optString("description"),
            tags = recipe.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            instructions = recipe.rows("instructions").map { instruction ->
                RecipeInstruction(
                    id = UUID.randomUUID().toString(),
                    title = instruction.getString("title"),
                    details = instruction.optString("details"),
                    method = instruction.stringOrNull("method"),
                    offsetMillis = if (instruction.isNull("suggestedOffsetMillis")) null else instruction.getLong("suggestedOffsetMillis"),
                    durationMillis = null,
                    sourceEventId = null,
                    action = instruction.stringOrNull("action"),
                )
            },
            observations = observations,
            targets = emptyList(),
            resultNotes = verdict?.optString("notes") ?: "",
            rating = if (verdict == null || verdict.isNull("rating")) null else verdict.getDouble("rating"),
            smokingAnchorUtcMillis = anchor,
            preferredPitTemperatureF = null,
            servings = "",
            cookAgain = if (verdict == null || verdict.isNull("cookAgain")) null else verdict.getBoolean("cookAgain"),
            publicSource = PublicRecipeSource(detail.id, detail.revisionId, detail.credit, removed = false, relativeTiming = true),
        )
        return Converted(snapshot, storyJson, story != null)
    }
}
