package com.medicabinet.app.features.settings.namedproducts

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R

/**
 * The confirmation before forgetting a product. The boxes of that code keep their name; the message says how many
 * are still in the cabinet and that the next scan of the code asks for a name. **Cancel** changes nothing.
 */
@Composable
fun ForgetNamedProductDialog(request: ForgetRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val count = request.boxesInCabinet
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.namedProducts_forget_title, request.row.product.name)) },
        text = {
            Text(
                if (count == 0) {
                    stringResource(R.string.namedProducts_forget_message)
                } else {
                    pluralStringResource(R.plurals.namedProducts_forget_messageInCabinet, count, count)
                },
                modifier = Modifier.testTag("namedProducts.forget.message")
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = 48.dp).testTag("namedProducts.forget.confirm")
            ) { Text(stringResource(R.string.namedProducts_forget)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag("namedProducts.forget.cancel")
            ) { Text(stringResource(R.string.box_edit_cancel)) }
        }
    )
}
