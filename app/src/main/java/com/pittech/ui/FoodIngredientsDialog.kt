package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel
import com.pittech.data.*
import com.pittech.domain.CookEntryValidation
import java.util.UUID

/** Ingredient names are sufficient; precision is available when it helps. */
@Composable internal fun FoodIngredientsDialog(data: CookDetailData, dish: DishEntity,
    viewModel: CooksViewModel, onClose: () -> Unit) {
    val ingredients = data.ingredients.filter { it.dishId == dish.id }
    var selectedId by rememberSaveable(dish.id) { mutableStateOf<String?>(null) }
    val selected = ingredients.firstOrNull { it.id == selectedId }
    var name by rememberSaveable(selectedId) { mutableStateOf(selected?.name.orEmpty()) }
    var stage by rememberSaveable(selectedId) { mutableStateOf(selected?.stage ?: "seasoning") }
    var brand by rememberSaveable(selectedId) { mutableStateOf(selected?.brand.orEmpty()) }
    var amount by rememberSaveable(selectedId) { mutableStateOf(selected?.amountText ?: selected?.amountValue?.toString().orEmpty()) }
    var unit by rememberSaveable(selectedId) { mutableStateOf(selected?.amountUnit ?: "tbsp") }
    var notes by rememberSaveable(selectedId) { mutableStateOf(selected?.notes.orEmpty()) }
    var appliedAt by rememberSaveable(selectedId) { mutableLongStateOf(selected?.appliedAtUtcMillis ?: System.currentTimeMillis()) }
    var includeTime by rememberSaveable(selectedId) { mutableStateOf(selected?.appliedAtUtcMillis != null) }
    var details by rememberSaveable(selectedId) { mutableStateOf(false) }
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    // PL-22 (DD-6): the ingredient editor is an item editor — full screen.
    FullScreenSurface(onDismiss = { if (!busy) onClose() }, title = dish.name + " ingredients", dismissEnabled = !busy,
        actions = {
            TextButton(onClick = onClose, enabled = !busy) { Text("Close") }
            TextButton(onClick = {
                val ingredient = (selected ?: IngredientEntity(UUID.randomUUID().toString(), data.cook.id, dish.id,
                    stage, name, createdAtUtcMillis = System.currentTimeMillis())).copy(name = name, stage = stage,
                    brand = brand.trim().ifBlank { null }, amountValue = CookEntryValidation.optionalPositiveNumber(amount), amountText = amount.trim().ifBlank { null },
                    amountUnit = unit.takeIf { amount.isNotBlank() }, notes = notes.trim().ifBlank { null },
                    appliedAtUtcMillis = appliedAt.takeIf { includeTime })
                viewModel.saveIngredient(ingredient) { saved -> if (saved) onClose() }
            }, enabled = !busy && name.isNotBlank() && CookEntryValidation.isOptionalPositiveNumberValid(amount),
                modifier = Modifier.testTag("food-ingredient-save")) { Text("Save ingredient") }
        }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ingredients.forEach { ingredient ->
                TextButton(onClick = { selectedId = ingredient.id }, modifier = Modifier.testTag("ingredient-edit-" + ingredient.id)) { Text(ingredient.name) }
            }
            if (selectedId != null) TextButton(onClick = { selectedId = null }) { Text("Add another ingredient") }
            OutlinedTextField(name, { name = it }, label = { Text("Ingredient") }, modifier = Modifier.fillMaxWidth().testTag("food-ingredient-name"))
            TextButton(onClick = { details = !details }, modifier = Modifier.testTag("food-ingredient-details")) { Text(if (details) "Less detail" else "More detail") }
            if (details) {
                SimpleDropdownField("Type", stage, listOf("main_ingredient", "seasoning", "sauce", "marinade", "binder", "brine", "glaze", "topping", "injection", "other"), { stage = it })
                OutlinedTextField(brand, { brand = it }, label = { Text("Brand") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(amount, { amount = it }, label = { Text("Amount (optional)") },
                        isError = !CookEntryValidation.isOptionalPositiveNumberValid(amount),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f).testTag("food-ingredient-amount"))
                    SimpleDropdownField("Unit", unit, listOf("cup", "tbsp", "tsp", "fl oz", "ml", "L", "g", "oz", "lb", "kg", "count", "other"), { unit = it }, modifier = Modifier.width(100.dp))
                }
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { includeTime = !includeTime }) { Text(if (includeTime) "Remove applied time" else "Set applied time") }
                if (includeTime) DateTimePickerField("Applied at", appliedAt, { appliedAt = it }, "ingredient-applied-time")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
