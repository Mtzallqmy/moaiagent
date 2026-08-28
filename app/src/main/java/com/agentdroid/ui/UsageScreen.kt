package com.agentdroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agentdroid.R
import com.agentdroid.core.ai.UsageLedger
import com.agentdroid.core.ai.UsageRecord
import com.agentdroid.core.model.ProviderKind
import com.agentdroid.integration.ModelTokenRate
import com.agentdroid.integration.UsageCostStore
import java.util.Locale

@Composable
fun UsageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val records by UsageLedger.records.collectAsState()
    val costStore = remember(context) { UsageCostStore(context) }
    var pricingTarget by remember { mutableStateOf<Pair<ProviderKind, String>?>(null) }
    var pricingRevision by remember { mutableIntStateOf(0) }
    val prompt = records.sumOf { it.usage.promptTokens ?: 0 }
    val completion = records.sumOf { it.usage.completionTokens ?: 0 }
    val total = records.sumOf { it.usage.totalTokens ?: ((it.usage.promptTokens ?: 0) + (it.usage.completionTokens ?: 0)) }
    val cost = remember(records, pricingRevision) { costStore.estimate(records) }
    val groups = records.groupBy { it.providerKind to it.modelId }.entries.sortedByDescending { entry -> entry.value.maxOfOrNull(UsageRecord::timestamp) ?: 0L }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.usage)) }, navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } })
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        UsageRow(stringResource(R.string.usage_prompt_tokens), prompt.toString())
                        UsageRow(stringResource(R.string.usage_completion_tokens), completion.toString())
                        UsageRow(stringResource(R.string.usage_total_tokens), total.toString())
                        UsageRow(stringResource(R.string.usage_messages), records.size.toString())
                        UsageRow(stringResource(R.string.usage_cost_estimate), String.format(Locale.US, "$%.6f", cost.usd))
                        Text(stringResource(R.string.usage_unpriced, cost.unpricedRecords), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.usage_pricing_note), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(groups, key = { "${it.key.first}:${it.key.second}" }) { entry ->
                val key = entry.key
                val usage = entry.value
                val input = usage.sumOf { it.usage.promptTokens ?: 0 }
                val output = usage.sumOf { it.usage.completionTokens ?: 0 }
                val rate = costStore.rate(key.first, key.second)
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${key.first.name} • ${key.second}", style = MaterialTheme.typography.titleMedium)
                        Text("${stringResource(R.string.usage_prompt_tokens)}: $input")
                        Text("${stringResource(R.string.usage_completion_tokens)}: $output")
                        rate?.let { Text(String.format(Locale.US, "$%.4f / $%.4f per 1M", it.inputUsdPerMillion, it.outputUsdPerMillion)) }
                        TextButton(onClick = { pricingTarget = key }) { Text(stringResource(R.string.usage_set_pricing)) }
                    }
                }
            }
            item { OutlinedButton(onClick = { UsageLedger.clear() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.usage_clear_session)) } }
        }
    }

    pricingTarget?.let { target ->
        PricingDialog(
            provider = target.first,
            model = target.second,
            current = costStore.rate(target.first, target.second),
            onDismiss = { pricingTarget = null },
            onSave = { rate -> costStore.setRate(target.first, target.second, rate); pricingRevision++; pricingTarget = null }
        )
    }
}

@Composable private fun UsageRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Text(value) }
}

@Composable
private fun PricingDialog(provider: ProviderKind, model: String, current: ModelTokenRate?, onDismiss: () -> Unit, onSave: (ModelTokenRate) -> Unit) {
    var input by remember { mutableStateOf(current?.inputUsdPerMillion?.toString().orEmpty()) }
    var output by remember { mutableStateOf(current?.outputUsdPerMillion?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.usage_pricing_title, "${provider.name} • $model")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(input, { input = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text(stringResource(R.string.usage_input_rate)) })
                OutlinedTextField(output, { output = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text(stringResource(R.string.usage_output_rate)) })
            }
        },
        confirmButton = { Button(onClick = { onSave(ModelTokenRate(input.toDoubleOrNull() ?: 0.0, output.toDoubleOrNull() ?: 0.0)) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
