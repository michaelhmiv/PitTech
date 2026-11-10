package com.pittech.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pittech.domain.IngredientDraft

/**
 * #284: a quick-add box leads the list — type a name, hit Done, the item is
 * appended and the box clears ready for the next one. Entered items sit as
 * one-line rows; tapping one expands the full detail fields in place.
 */
@Composable
internal fun IngredientFields(items: List<IngredientDraft>, onChange: (List<IngredientDraft>) -> Unit) {
    var quickName by remember { mutableStateOf("") }
    var expandedIndex by remember { mutableStateOf<Int?>(null) }
    fun quickAdd() {
        val name = quickName.trim()
        if (name.isEmpty() || items.size >= 100) return
        onChange(items + IngredientDraft(name, stage = "main_ingredient", amountUnit = "count"))
        quickName = ""
    }
    OutlinedTextField(quickName, { quickName = it }, label = { Text("Add ingredient") }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { quickAdd() }),
        modifier = Modifier.fillMaxWidth().testTag("ingredient-quick-add"))
    items.forEachIndexed { index, item ->
        fun change(next: IngredientDraft) { onChange(items.mapIndexed { i, old -> if (i == index) next else old }) }
        val summary = listOfNotNull(
            item.amountText.trim().ifBlank { null }?.let { amount -> amount + " " + item.amountUnit },
            item.stage.replace('_', ' ').replaceFirstChar { it.titlecase() },
            item.brand.trim().ifBlank { null },
        ).joinToString(" · ")
        Text(text = (item.name.ifBlank { "Unnamed ingredient" }) + if (summary.isNotBlank()) " · " + summary else "",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().clickable { expandedIndex = if (expandedIndex == index) null else index }
                .padding(vertical = 8.dp).testTag("ingredient-row-" + index))
        if (expandedIndex == index) {
            OutlinedTextField(item.name, { change(item.copy(name = it)) }, label = { Text("Ingredient") }, modifier = Modifier.fillMaxWidth().testTag("ingredient-name-" + index))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(item.amountText, { change(item.copy(amountText = it)) }, label = { Text("Amount (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                SimpleDropdownField("Unit", item.amountUnit, listOf("cup", "tbsp", "tsp", "fl oz", "ml", "L", "g", "oz", "lb", "kg", "count"), { change(item.copy(amountUnit = it)) }, modifier = Modifier.weight(1f))
            }
            SimpleDropdownField("Type", item.stage.replace('_', ' ').replaceFirstChar { it.titlecase() },
                listOf("Main ingredient", "Seasoning", "Sauce", "Marinade", "Binder", "Brine", "Glaze", "Topping", "Other"),
                { change(item.copy(stage = it.lowercase().replace(' ', '_'))) })
            OutlinedTextField(item.brand, { change(item.copy(brand = it)) }, label = { Text("Brand (optional)") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                if (expandedIndex == index) expandedIndex = null
                onChange(items.filterIndexed { i, _ -> i != index })
            }) { Text("Remove ingredient") }
        }
    }
    TextButton(onClick = {
        onChange(items + IngredientDraft("", stage = "main_ingredient", amountUnit = "count"))
        expandedIndex = items.size
    }, enabled = items.size < 100) { Text("+ Ingredient") }
}
