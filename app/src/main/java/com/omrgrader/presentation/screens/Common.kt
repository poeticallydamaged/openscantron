package com.omrgrader.presentation.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.omrgrader.data.repository.OmrRepository
import com.omrgrader.domain.ExamPresets
import com.omrgrader.domain.PageSize
import com.omrgrader.presentation.viewmodels.DocumentsViewModel
import com.omrgrader.presentation.viewmodels.ExamForm
import kotlinx.coroutines.launch

fun readUriBytes(context: Context, uri: Uri): ByteArray =
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalArgumentException("cannot read document")

@Composable
fun rememberShareBytes(context: Context, docs: DocumentsViewModel): (String, ByteArray, String) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(context, docs, scope) { { fileName, bytes, mime ->
        scope.launch {
            try {
                val uri = docs.export(fileName, bytes, mime)
                context.startActivity(Intent.createChooser(
                    docs.shareIntent(uri, mime), "Share $fileName"))
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    } }
}

@Composable
fun rememberShareExistingFile(context: Context, docs: DocumentsViewModel): (String, String) -> Unit =
    remember(context, docs) { { path, mime ->
        try {
            val uri = docs.uriForFile(path)
            context.startActivity(Intent.createChooser(
                docs.shareIntent(uri, mime), "Share document"))
        } catch (e: Exception) {
            Toast.makeText(context, "Share failed: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    } }

@Composable
fun rememberOpenExistingFile(context: Context, docs: DocumentsViewModel): (String, String) -> Unit =
    remember(context, docs) { { path, mime ->
        try {
            val uri = docs.uriForFile(path)
            context.startActivity(Intent.createChooser(
                docs.viewIntent(uri, mime), "Open document"))
        } catch (e: Exception) {
            Toast.makeText(context, "Open failed: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    } }

@Composable
fun ExportButton(
    label: String,
    mime: String,
    context: Context,
    docs: DocumentsViewModel,
    onProduced: (String, ByteArray, String) -> Unit = { _, _, _ -> },
    produce: suspend () -> Pair<String, ByteArray>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scope = rememberCoroutineScope()
    Button(modifier = modifier, enabled = enabled, onClick = {
        scope.launch {
            try {
                val (name, bytes) = produce()
                val uri = docs.export(name, bytes, mime)
                context.startActivity(Intent.createChooser(
                    docs.shareIntent(uri, mime), "Share $name"))
                onProduced(name, bytes, mime)
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    }) { Text(label) }
}

@Composable
fun rememberTreeSaver(context: Context, docs: DocumentsViewModel): (String, ByteArray, String) -> Unit {
    var pending by remember { mutableStateOf<Triple<String, ByteArray, String>?>(null) }
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val item = pending
        if (tree != null && item != null) {
            context.contentResolver.takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            scope.launch {
                try {
                    docs.writeToTree(tree, item.first, item.second, item.third)
                    Toast.makeText(context, "Saved ${item.first} to selected folder", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "SAF save failed: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            }
        }
        pending = null
    }
    return remember(context, docs, scope) { { name, bytes, mime ->
        pending = Triple(name, bytes, mime)
        launcher.launch(null)
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamFormFields(form: ExamForm, modifier: Modifier = Modifier, onChange: (ExamForm) -> Unit) {
    var labelsExpanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = form.title, onValueChange = { onChange(form.copy(title = it)) },
            label = { Text("Examination title *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = form.subject, onValueChange = { onChange(form.copy(subject = it)) },
            label = { Text("Subject") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = form.description, onValueChange = { onChange(form.copy(description = it)) },
            label = { Text("Description / instructions") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(value = form.date.toString(),
            onValueChange = { onChange(form.copy(date = it.toLongOrNull() ?: form.date)) },
            label = { Text("Exam date (epoch milliseconds UTC)") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Text("Question count preset", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExamPresets.questionCounts.take(4).forEach { count ->
                FilterChip(selected = form.questionCount == count,
                    onClick = { onChange(form.copy(questionCount = count)) },
                    label = { Text(count.toString()) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExamPresets.questionCounts.drop(4).forEach { count ->
                FilterChip(selected = form.questionCount == count,
                    onClick = { onChange(form.copy(questionCount = count)) },
                    label = { Text(count.toString()) })
            }
        }
        OutlinedTextField(value = form.questionCount.toString(),
            onValueChange = { onChange(form.copy(questionCount = (it.toIntOrNull() ?: 1).coerceIn(1, 200))) },
            label = { Text("Custom question count (1..200)") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Text("Answer labels", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExamPresets.choiceSets.forEach { set ->
                FilterChip(selected = form.choices == set,
                    onClick = { onChange(form.copy(choices = set)) },
                    label = { Text(set.joinToString("")) })
            }
        }
        var custom by remember(form.choices) { mutableStateOf(form.choices.joinToString(",")) }
        OutlinedTextField(value = custom,
            onValueChange = { value ->
                custom = value
                val labels = value.split(',').map { it.trim() }.filter { it.length == 1 }
                if (labels.isNotEmpty() && labels.size <= 6 && labels.distinct().size == labels.size)
                    onChange(form.copy(choices = labels))
            },
            label = { Text("Custom labels, comma-separated (A,B,C,D,E,F)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("Page size", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageSize.entries.forEach { size ->
                FilterChip(selected = form.pageSize == size,
                    onClick = { onChange(form.copy(pageSize = size)) },
                    label = { Text(size.name) })
            }
        }
        OutlinedTextField(value = form.pointsPerCorrect, onValueChange = { onChange(form.copy(pointsPerCorrect = it)) },
            label = { Text("Points per correct answer") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(value = form.incorrectDeduction,
            onValueChange = { onChange(form.copy(incorrectDeduction = it)) },
            label = { Text("Deduction per incorrect answer") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(value = form.unansweredPoints,
            onValueChange = { onChange(form.copy(unansweredPoints = it)) },
            label = { Text("Points for unanswered") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(value = form.passingThreshold,
            onValueChange = { onChange(form.copy(passingThreshold = it)) },
            label = { Text("Passing threshold (%)") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Partial credit for eligible multiple marks")
            Switch(checked = form.partialCredit, onCheckedChange = { onChange(form.copy(partialCredit = it)) })
        }
    }
}

@Composable
fun OperationBanner(state: com.omrgrader.presentation.components.UiState<String>) {
    when (state) {
        is com.omrgrader.presentation.components.UiState.Success ->
            if (state.data.isNotBlank()) com.omrgrader.presentation.components.MessageBanner(state.data)
        is com.omrgrader.presentation.components.UiState.Error ->
            com.omrgrader.presentation.components.MessageBanner(state.message, isError = true)
        else -> Unit
    }
}
