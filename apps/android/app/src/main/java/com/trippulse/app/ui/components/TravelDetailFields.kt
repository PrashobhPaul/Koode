package com.trippulse.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.trippulse.app.core.InputRules
import com.trippulse.app.domain.DetailField
import com.trippulse.app.domain.TravelDetails
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Spacing

/**
 * The vehicle and booking questions for one mode of travel.
 *
 * Rendered from [TravelDetails.fieldsFor] rather than hand-written per screen,
 * so the create screen, the mid-journey switch and any future surface ask the
 * same questions in the same words — and adding a field to a mode is a change
 * in one place rather than three.
 *
 * Choice fields are chips instead of a dropdown on purpose: the lists are
 * short, and a chip shows what the options *are* without a tap. Every list
 * ends in "Other", which turns the field into free text rather than trapping
 * someone whose bus company we never heard of.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TravelDetailFields(
    mode: String?,
    values: Map<String, String>,
    onChange: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KoodeTheme.colors
    val fields = TravelDetails.fieldsFor(mode)
    if (fields.isEmpty()) return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        fields.forEach { field ->
            if (field.isChoice) ChoiceField(field, values, onChange)
            else TextField(field, values, onChange)

            if (field.hint != null) {
                Text(
                    field.hint,
                    color = colors.textLow,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceField(
    field: DetailField,
    values: Map<String, String>,
    onChange: (String, String) -> Unit
) {
    val colors = KoodeTheme.colors
    val current = values[field.key].orEmpty()
    // "Other" is selected when the value is not one of the listed options.
    //
    // Emptiness rather than blankness, deliberately: choosing Other writes a
    // single space so the chip has something to latch onto before anything is
    // typed. Testing isNotBlank() here treated that space as "nothing chosen",
    // so the chip never lit and the text box never appeared -- the Other
    // option was unusable.
    val listed = field.options.contains(current)
    val showFreeText = current.isNotEmpty() && !listed

    Column {
        Text(
            field.label + if (field.required) "" else " (optional)",
            color = colors.textLow,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(Spacing.sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            field.options.forEach { option ->
                val isOther = option.equals("Other", ignoreCase = true)
                val selected = if (isOther) showFreeText else current == option
                KoodeChip(
                    option,
                    selected,
                    // Choosing Other clears the field so the text box starts
                    // empty and the chip stays lit while they type.
                    { onChange(field.key, if (isOther) " " else option) }
                )
            }
        }
        if (showFreeText) {
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = current.trimStart(),
                onValueChange = { onChange(field.key, InputRules.itemText(it)) },
                label = { Text(field.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TextField(
    field: DetailField,
    values: Map<String, String>,
    onChange: (String, String) -> Unit
) {
    OutlinedTextField(
        value = values[field.key].orEmpty(),
        onValueChange = { raw ->
            val cleaned = when {
                field.digitsOnly -> InputRules.digits(raw)
                field.uppercase -> raw.uppercase().filter { it.isLetterOrDigit() || it == '-' || it == '/' }
                else -> InputRules.itemText(raw)
            }
            onChange(field.key, cleaned)
        },
        label = { Text(field.label + if (field.required) "" else " (optional)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization =
                if (field.uppercase) KeyboardCapitalization.Characters
                else KeyboardCapitalization.Words
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
