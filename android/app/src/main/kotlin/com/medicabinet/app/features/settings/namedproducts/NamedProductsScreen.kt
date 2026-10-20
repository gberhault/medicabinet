package com.medicabinet.app.features.settings.namedproducts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medicabinet.app.R
import com.medicabinet.app.features.CategoryLabels
import com.medicabinet.app.features.cabinet.BoxCode
import com.medicabinet.core.NamedProductSummary

/**
 * Settings → Products you named: the products the household gave a name to because the medicines database does not
 * know their code. A row opens **Edit**; the overflow menu (and TalkBack's actions) offer **Edit** and **Forget**.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NamedProductsScreen(viewModel: NamedProductsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<NamedProductSummary?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.namedProducts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.about_back))
                    }
                }
            )
        }
    ) { padding ->
        val content = Modifier.padding(padding).fillMaxSize()
        when (val current = state) {
            NamedProductsUiState.Loading -> LoadingMessage(content)
            NamedProductsUiState.Failed -> StatusMessage(
                content,
                title = R.string.namedProducts_error_title,
                message = R.string.namedProducts_error_message,
                tag = "namedProducts.error",
                onRetry = viewModel::observe
            )
            is NamedProductsUiState.Loaded -> if (current.rows.isEmpty()) {
                StatusMessage(
                    content,
                    title = R.string.namedProducts_empty_title,
                    message = R.string.namedProducts_empty_message,
                    tag = "namedProducts.empty"
                )
            } else {
                ProductList(current.rows, onEdit = { editing = it }, onForget = viewModel::requestForget, content)
            }
        }
    }
    NamedProductsOverlays(viewModel, editing, onEditDone = { editing = null })
}

@Composable
private fun LoadingMessage(modifier: Modifier) {
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) { CircularProgressIndicator(Modifier.testTag("namedProducts.loading")) }
}

/** An empty or failed list: an icon and text, with **Retry** for a failure. */
@Composable
private fun StatusMessage(modifier: Modifier, title: Int, message: Int, tag: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier.padding(24.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val icon = if (onRetry == null) Icons.AutoMirrored.Outlined.Label else Icons.Outlined.ErrorOutline
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp))
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(stringResource(message), textAlign = TextAlign.Center)
        onRetry?.let { Button(onClick = it) { Text(stringResource(R.string.locations_error_retry)) } }
    }
}

@Composable
private fun ProductList(
    rows: List<NamedProductSummary>,
    onEdit: (NamedProductSummary) -> Unit,
    onForget: (NamedProductSummary) -> Unit,
    modifier: Modifier
) {
    LazyColumn(modifier.testTag("namedProducts.list")) {
        items(rows, key = { it.product.id }) { row -> ProductRow(row, onEdit, onForget) }
    }
}

@Composable
private fun ProductRow(
    row: NamedProductSummary,
    onEdit: (NamedProductSummary) -> Unit,
    onForget: (NamedProductSummary) -> Unit
) {
    val edit = stringResource(R.string.box_edit_action)
    val forget = stringResource(R.string.namedProducts_forget)
    val product = row.product
    val code = BoxCode.display(product.gtin).orEmpty()
    val category = stringResource(CategoryLabels.resource(product.category))
    ListItem(
        headlineContent = { Text(product.name) },
        supportingContent = {
            Column {
                Text("$code · $category")
                Text(
                    if (row.boxesInCabinet > 0) {
                        pluralStringResource(R.plurals.namedProducts_inCabinet, row.boxesInCabinet, row.boxesInCabinet)
                    } else {
                        stringResource(R.string.namedProducts_notInCabinet)
                    }
                )
            }
        },
        trailingContent = { RowMenu(onEdit = { onEdit(row) }, onForget = { onForget(row) }) },
        modifier = Modifier
            .clickable(onClickLabel = edit) { onEdit(row) }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(edit) {
                        onEdit(row)
                        true
                    },
                    CustomAccessibilityAction(forget) {
                        onForget(row)
                        true
                    }
                )
            }
    )
}

@Composable
private fun RowMenu(onEdit: () -> Unit, onForget: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("namedProducts.menu")) {
        Icon(Icons.Outlined.MoreVert, stringResource(R.string.namedProducts_more))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.box_edit_action)) },
            onClick = {
                open = false
                onEdit()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.namedProducts_forget)) },
            onClick = {
                open = false
                onForget()
            }
        )
    }
}
