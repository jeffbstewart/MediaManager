package net.stewart.mediamanager.tv.ui.components

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation

private val TvTextFieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color(0xFFE0E0E0),
        focusedBorderColor = Color(0xFF90CAF9),
        unfocusedBorderColor = Color(0xFF9E9E9E),
        focusedLabelColor = Color(0xFF90CAF9),
        unfocusedLabelColor = Color(0xFFBDBDBD),
        cursorColor = Color(0xFF90CAF9),
        focusedContainerColor = Color(0xFF1E1E1E),
        unfocusedContainerColor = Color(0xFF1E1E1E),
    )

/** OutlinedTextField with TV-appropriate contrast colors. */
@Composable
fun TvOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        colors = TvTextFieldColors,
        modifier = modifier
    )
}
