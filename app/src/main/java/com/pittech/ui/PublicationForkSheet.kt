package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pittech.data.PublicDetail
import com.pittech.data.PublicationFork
import com.pittech.domain.CookInputVocabulary
import com.pittech.domain.WrapMaterial
import org.json.JSONObject

private fun JSONObject.rows(key: String): List<JSONObject> =
    optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()

/**
 * Flowing Slice 5, element 4.3 — tweak-lite: the fork opens three
 * decisions (rub, wrap, target), not the full version editor. The
 * full editor remains one level down, on the saved recipe. The sheet
 * follows the book's Step 3: "Change what you'd change at your own
 * pit. Everything else carries over." Pre-fills are the recipe's own
 * facts — the name gains "— my version", the target is the source
 * cook's last recorded food reading when the story carries one —
 * and saving is the decision that commits them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun PublicationForkSheet(
    detail: PublicDetail,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (List<PublicationFork.Tweak>) -> Unit,
) {
    val tokens = pitTechTokens()
    val prefills = remember(detail.id) { detail.recipes.indices.map { PublicationFork.prefill(detail, it) } }
    var names by remember(detail.id) { mutableStateOf(prefills.map { it.name }) }
    var rubs by remember(detail.id) { mutableStateOf(prefills.map { it.rub }) }
    var wraps by remember(detail.id) { mutableStateOf(prefills.map { it.wrap }) }
    var targets by remember(detail.id) { mutableStateOf(prefills.map { it.targetF?.let { value -> PublicationFork.jsNumber(value) } ?: "" }) }
    val targetsValid = targets.all { it.isBlank() || it.toDoubleOrNull()?.let { value -> value in 32.0..600.0 } == true }
    val namesValid = names.all { it.isNotBlank() && it.trim().length <= 200 }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).testTag("fork-sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TurnInMetaLabel("Fork · 3 decisions, that's it")
                Text("Make it yours", style = PitTechTextStyles.bannerTitle)
                Text(
                    "Change what you'd change at your own pit. Everything else carries over from ${detail.credit}'s cook.",
                    style = PitTechTextStyles.body, color = tokens.inkMuted,
                )
            }
            detail.recipes.forEachIndexed { index, recipe ->
                if (detail.recipes.size > 1) Text(recipe.getString("title"), style = PitTechTextStyles.bannerCard)
                TurnInLiteralBoxedField("Your name for it", Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        names[index], { value -> names = names.toMutableList().also { it[index] = value } },
                        singleLine = true, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("fork-name-$index"),
                    )
                }
                TurnInLiteralBoxedField("Rub", Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        rubs[index], { value -> rubs = rubs.toMutableList().also { it[index] = value } },
                        singleLine = true, enabled = !busy,
                        placeholder = { Text("As written") },
                        modifier = Modifier.fillMaxWidth().testTag("fork-rub-$index"),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TurnInMetaLabel("Wrap")
                    val hint = remember(detail.id, index) {
                        val texts = recipe.rows("instructions").flatMap { listOf(it.optString("title"), it.optString("details"), it.optString("method")) } +
                            (recipe.optJSONObject("story")?.rows("milestones")?.map { it.optString("text") } ?: emptyList())
                        CookInputVocabulary.wrapMaterialHint(texts)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val options = listOf(
                            "As written" to PublicationFork.WrapChoice.AS_WRITTEN,
                            WrapMaterial.NONE.label to PublicationFork.WrapChoice.NO_WRAP,
                            WrapMaterial.FOIL.label to PublicationFork.WrapChoice.FOIL,
                            WrapMaterial.BUTCHER_PAPER.label to PublicationFork.WrapChoice.BUTCHER_PAPER,
                        )
                        options.forEach { (label, choice) ->
                            FilterChip(
                                selected = wraps[index] == choice,
                                onClick = { wraps = wraps.toMutableList().also { it[index] = choice } },
                                enabled = !busy,
                                label = { Text(label) },
                                colors = turnInChipColors(),
                                modifier = Modifier.testTag("fork-wrap-$index-${choice.name.lowercase()}"),
                            )
                        }
                    }
                    if (wraps[index] == PublicationFork.WrapChoice.AS_WRITTEN && hint != null) {
                        Text("As written — ${hint.label.lowercase()} in the source cook.", style = PitTechTextStyles.body, color = tokens.inkMuted)
                    }
                }
                TurnInLiteralBoxedField("Target temp (°F)", Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        targets[index], { value -> targets = targets.toMutableList().also { it[index] = value } },
                        singleLine = true, enabled = !busy,
                        placeholder = { Text("As written — no target") },
                        modifier = Modifier.fillMaxWidth().testTag("fork-target-$index"),
                    )
                }
                if (prefills[index].targetFromStory && targets[index].isNotBlank()) {
                    Text("Pre-filled from the source cook's last recorded food temperature — change it or clear it.", style = PitTechTextStyles.body, color = tokens.inkMuted)
                }
            }
            if (!targetsValid) Text("Use a target between 32 and 600 °F, or leave it blank.", style = PitTechTextStyles.body, color = tokens.flag)
            if (!namesValid) Text("Give your version a name (up to 200 characters).", style = PitTechTextStyles.body, color = tokens.flag)
            error?.let { TurnInStatusStrip(it, tokens.flag, Modifier.testTag("fork-error")) }
            Text("The original stays untouched in Discover.", style = PitTechTextStyles.body, color = tokens.inkMuted)
            Button(
                onClick = {
                    onSave(detail.recipes.indices.map { index ->
                        PublicationFork.Tweak(
                            name = names[index],
                            rub = rubs[index],
                            wrap = wraps[index],
                            targetF = targets[index].toDoubleOrNull(),
                            targetFromStory = prefills[index].targetFromStory &&
                                targets[index].toDoubleOrNull() == prefills[index].targetF,
                        )
                    })
                },
                enabled = !busy && targetsValid && namesValid,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fork-save"),
            ) { Text("Save my version") }
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(); Text("Saving your version…")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
