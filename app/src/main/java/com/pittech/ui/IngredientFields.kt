package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pittech.domain.IngredientDraft

@Composable
internal fun IngredientFields(items: List<IngredientDraft>, onChange: (List<IngredientDraft>) -> Unit) {
    items.forEachIndexed { index, item ->
        fun change(next: IngredientDraft) { onChange(items.mapIndexed { i, old -> if (i == index) next else old }) }
        OutlinedTextField(item.name, { change(item.copy(name = it)) }, label = { Text("Ingredient") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(item.amountText, { change(item.copy(amountText = it)) }, label = { Text("Amount (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            SimpleDropdownField("Unit", item.amountUnit, listOf("cup", "tbsp", "tsp", "fl oz", "ml", "L", "g", "oz", "lb", "kg", "count"), { change(item.copy(amountUnit = it)) }, modifier = Modifier.weight(1f))
        }
        SimpleDropdownField("Type", item.stage.replace('_', ' ').replaceFirstChar { it.titlecase() },
            listOf("Main ingredient", "Seasoning", "Sauce", "Marinade", "Binder", "Brine", "Glaze", "Topping", "Other"),
            { change(item.copy(stage = it.lowercase().replace(' ', '_'))) })
        OutlinedTextField(item.brand, { change(item.copy(brand = it)) }, label = { Text("Brand (optional)") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { onChange(items.filterIndexed { i, _ -> i != index }) }) { Text("Remove ingredient") }
    }
    TextButton(onClick = { onChange(items + IngredientDraft("", stage = "main_ingredient", amountUnit = "count")) }, enabled = items.size < 100) { Text("+ Ingredient") }
}
