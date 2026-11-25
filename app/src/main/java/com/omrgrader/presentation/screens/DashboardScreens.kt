package com.omrgrader.presentation.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.omrgrader.core.Ids
import com.omrgrader.data.repository.layout
import com.omrgrader.domain.*
import com.omrgrader.domain.RosterImport
import com.omrgrader.presentation.components.*
import com.omrgrader.presentation.navigation.Routes
import com.omrgrader.presentation.viewmodels.*
import com.omrgrader.platform.printPdf
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(vm: DashboardViewModel, nav: NavHostController) {
    val state by vm.dashboard.collectAsState()
    AppScreen("OpenScantron Dashboard", state) { data ->
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth()) {
                    StatCard("Exams", data.examCount.toString(), Modifier.weight(1f))
                    StatCard("Submissions", data.submissionCount.toString(), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    StatCard("Archived PDFs", data.archivedPdfCount.toString(), Modifier.weight(1f))
                    StatCard("Synced CSVs", data.syncedCsvCount.toString(), Modifier.weight(1f))
                }
            }
            item {
                Text("Offline workflow", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { nav.navigate(Routes.CREATE_EXAM) }) { Text("Create exam") }
                    Button(onClick = { nav.navigate(Routes.EXAMS) }) { Text("Exams") }
                    Button(onClick = { nav.navigate(Routes.ROSTER) }) { Text("Roster") }
                }
                Spacer(Modifier.height(6.dp))
                val target = data.recentExams.firstOrNull()?.id
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { nav.navigate(if (target != null) Routes.scanning(target) else Routes.EXAMS) }) {
                        Text("Scan")
                    }
                    Button(onClick = { nav.navigate(if (target != null) Routes.results(target) else Routes.EXAMS) }) {
                        Text("Results")
                    }
                    Button(onClick = { nav.navigate(Routes.ARCHIVES) }) { Text("Archives") }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { nav.navigate(if (target != null) Routes.csvDatasets(target) else Routes.EXAMS) }) {
                        Text("CSV datasets")
                    }
                    Button(onClick = { nav.navigate(if (target != null) Routes.exportHub(target) else Routes.EXAMS) }) {
                        Text("Export")
                    }
                    Button(onClick = { nav.navigate(Routes.SETTINGS) }) { Text("Settings") }
                }
            }
            item { Text("Recent examinations", style = MaterialTheme.typography.titleMedium) }
            if (data.recentExams.isEmpty()) item { Text("No examinations yet. Create one to begin.") }
            items(data.recentExams) { exam ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(exam.title, style = MaterialTheme.typography.titleSmall)
                        Text("${exam.subject} · ${exam.totalQuestions} items · ${exam.totalPoints} points")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { nav.navigate(Routes.results(exam.id)) }) { Text("Results") }
                            TextButton(onClick = { nav.navigate(Routes.scanning(exam.id)) }) { Text("Scan") }
                            TextButton(onClick = { nav.navigate(Routes.editExam(exam.id)) }) { Text("Edit") }
                        }
                    }
                }
            }
            item { Text("Recent submissions", style = MaterialTheme.typography.titleMedium) }
            if (data.recentSubmissions.isEmpty()) item { Text("No scans committed yet.") }
            items(data.recentSubmissions) { sub ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Submission ${sub.id}")
                        Text("${sub.gradingStatus} · score ${sub.score ?: 0.0} · ${sub.processingStatus}")
                    }
                }
            }
        }
    }
}

@Composable
fun ExaminationListScreen(vm: ExamViewModel, nav: NavHostController) {
    val state by vm.exams.collectAsState()
    val operation by vm.operation.collectAsState()
    var showArchived by remember { mutableStateOf(false) }
    AppScreen("Examinations", state, onBack = { nav.popBackStack() },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate(Routes.CREATE_EXAM) }) { Text("+") }
        }) { exams ->
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Include archived examinations")
                Switch(checked = showArchived, onCheckedChange = {
                    showArchived = it; vm.setShowArchived(it)
                })
            }
            OperationBanner(operation)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(exams) { exam ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(exam.title, style = MaterialTheme.typography.titleMedium)
                            Text("${exam.subject} · ${exam.totalQuestions} questions · ${exam.totalPoints} points")
                            if (exam.isArchived) MessageBanner("Archived examination", isError = false)
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(onClick = { nav.navigate(Routes.scanning(exam.id)) }) { Text("Scan") }
                                TextButton(onClick = { nav.navigate(Routes.results(exam.id)) }) { Text("Results") }
                                TextButton(onClick = { nav.navigate(Routes.editExam(exam.id)) }) { Text("Edit") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(onClick = { nav.navigate(Routes.answerKey(exam.id)) }) { Text("Key") }
                                TextButton(onClick = { nav.navigate(Routes.template(exam.id)) }) { Text("Template") }
                                TextButton(onClick = { vm.duplicate(exam.id) }) { Text("Duplicate") }
                                TextButton(onClick = { vm.archive(exam.id, !exam.isArchived) }) {
                                    Text(if (exam.isArchived) "Restore" else "Archive")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateExaminationScreen(vm: ExamViewModel, nav: NavHostController) {
    val form by vm.form.collectAsState()
    val operation by vm.operation.collectAsState()
    AppScreen("Create Examination", UiState.Success(Unit), onBack = { nav.popBackStack() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            OperationBanner(operation)
            ExamFormFields(form) { vm.updateForm(it) }
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = form.title.isNotBlank(), onClick = {
                    vm.createExam { id ->
                        vm.clearOperation()
                        nav.navigate(Routes.answerKey(id)) { popUpTo(Routes.EXAMS) }
                    }
                }) { Text("Create and edit answer key") }
                Button(onClick = { vm.updateForm(ExamForm()) }) { Text("Reset form") }
                Button(onClick = { nav.popBackStack() }) { Text("Cancel") }
            }
        }
    }
}

@Composable
fun EditExaminationScreen(examId: String, vm: ExamViewModel, nav: NavHostController) {
    LaunchedEffect(examId) { vm.selectExam(examId) }
    val examState by vm.exam.collectAsState()
    val form by vm.form.collectAsState()
    val operation by vm.operation.collectAsState()
    AppScreen("Edit Examination", examState, onBack = { nav.popBackStack() }) { exam ->
        LaunchedEffect(exam.id, exam.updatedAt) { vm.loadForm(exam) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            OperationBanner(operation)
            ExamFormFields(form) { vm.updateForm(it) }
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.updateExam(exam) }) { Text("Save changes") }
                Button(onClick = { nav.navigate(Routes.answerKey(exam.id)) }) { Text("Answer key") }
                Button(onClick = { nav.navigate(Routes.template(exam.id)) }) { Text("Template") }
                Button(onClick = { nav.navigate(Routes.results(exam.id)) }) { Text("Results") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnswerKeyEditorScreen(examId: String, vm: ExamViewModel, nav: NavHostController) {
    LaunchedEffect(examId) { vm.selectExam(examId) }
    val examState by vm.exam.collectAsState()
    val keysState by vm.keys.collectAsState()
    val templatesState by vm.templates.collectAsState()
    val draft by vm.keyDraft.collectAsState()
    val operation by vm.operation.collectAsState()
    AppScreen("Versioned Answer Key", examState, onBack = { nav.popBackStack() }) { exam ->
        val templates = (templatesState as? UiState.Success)?.data ?: emptyList()
        val choices = templates.firstOrNull()?.answerChoices ?: emptyList()
        val missing = if (choices.isEmpty()) (1..exam.totalQuestions).toList()
        else AnswerKeyVersionService.validate(draft, exam.totalQuestions, choices)
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            if (choices.isEmpty()) {
                MessageBanner("Configure an answer-sheet template before creating a key.", isError = true)
                Button(onClick = { nav.navigate(Routes.template(examId)) }) { Text("Configure template") }
                return@Column
            }
            OperationBanner(operation)
            InfoRow("Active key", exam.activeAnswerKeyVersionId ?: "none")
            InfoRow("Missing/invalid items", missing.size.toString())
            val keyVersions = (keysState as? UiState.Success)?.data ?: emptyList()
            Text("Versions: " + (keyVersions.joinToString { "v${it.version}" }.ifBlank { "none" }))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { vm.fillKeyDraft(exam.totalQuestions, choices.first()) }) {
                    Text("Fill ${choices.first()}")
                }
                Button(onClick = { vm.clearKeyDraft() }) { Text("Clear") }
            }
            LazyColumn(Modifier.weight(1f).padding(vertical = 6.dp)) {
                items(exam.totalQuestions) { index ->
                    val q = index + 1
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Q${q.toString().padStart(3, '0')}", Modifier.width(56.dp))
                            choices.forEach { choice ->
                                FilterChip(selected = draft[q] == choice,
                                    onClick = { vm.setKeyAnswer(q, choice) },
                                    label = { Text(choice) })
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { vm.saveKey(examId, choices) }) { Text("Save version") }
                Button(onClick = { vm.editActiveKey(examId, choices) }) { Text("Edit active") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(enabled = exam.activeAnswerKeyVersionId != null,
                    onClick = { exam.activeAnswerKeyVersionId?.let { vm.regrade(examId, it) } }) {
                    Text("Regrade active")
                }
                Button(onClick = { nav.navigate(Routes.template(examId)) }) { Text("Template") }
                Button(onClick = { nav.popBackStack() }) { Text("Back") }
            }
        }
    }
}

@Composable
fun StudentRosterScreen(
    vm: ExamViewModel, docs: DocumentsViewModel, nav: NavHostController
) {
    val state by vm.students.collectAsState()
    val operation by vm.operation.collectAsState()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var editId by remember { mutableStateOf<String?>(null) }
    var number by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Student?>(null) }
    AppScreen("Student Roster", state, onBack = { nav.popBackStack() }) { students ->
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            OutlinedTextField(value = query, onValueChange = {
                query = it; vm.setStudentQuery(it)
            }, label = { Text("Search by number, name or section") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OperationBanner(operation)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    OutlinedTextField(value = number, onValueChange = { number = it },
                        label = { Text("Student number *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("Full name *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = section, onValueChange = { section = it },
                        label = { Text("Section") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = info, onValueChange = { info = it },
                        label = { Text("Additional information") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(enabled = number.isNotBlank() && name.isNotBlank(), onClick = {
                            vm.saveStudent(Student(editId ?: Ids.newId("student"),
                                number.trim(), name.trim(), section.trim(), info.trim()))
                            editId = null; number = ""; name = ""; section = ""; info = ""
                        }) { Text(if (editId == null) "Add student" else "Update student") }
                        Button(onClick = {
                            editId = null; number = ""; name = ""; section = ""; info = ""
                        }) { Text("Clear") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { nav.navigate(Routes.ROSTER_IMPORT) }) { Text("Import CSV") }
                ExportButton("Export roster CSV", "text/csv", context, docs,
                    produce = { "STUDENT_ROSTER.csv" to docs.rosterCsv() })
            }
            LazyColumn(Modifier.weight(1f)) {
                items(students) { student ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(Modifier.padding(10.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(student.fullName, style = MaterialTheme.typography.titleSmall)
                                Text("${student.studentNumber} · ${student.section} · ${student.additionalInformation}")
                            }
                            Column {
                                TextButton(onClick = {
                                    editId = student.id; number = student.studentNumber
                                    name = student.fullName; section = student.section
                                    info = student.additionalInformation
                                }) { Text("Edit") }
                                TextButton(onClick = { pendingDelete = student }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (pendingDelete != null) {
        AlertDialog(onDismissRequest = { pendingDelete = null },
            title = { Text("Delete student?") },
            text = { Text("This removes the roster row. Existing submission snapshots are preserved.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete?.let { vm.removeStudent(it.id) }; pendingDelete = null
                }) { Text("Delete") }
            }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } })
    }
}

@Composable
fun StudentImportScreen(
    vm: ExamViewModel, docs: DocumentsViewModel, nav: NavHostController
) {
    val context = LocalContext.current
    val importState by vm.import.collectAsState()
    var readError by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            readError = null
            vm.importStudents(readUriBytes(context, uri))
        } catch (e: Exception) {
            readError = "Could not read CSV: ${e.javaClass.simpleName}"
        }
    }
    AppScreen("Import Student CSV", UiState.Success(Unit), onBack = { nav.popBackStack() }) {
        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Expected columns: student_number, full_name, section, additional_information. " +
                "A header row is optional; RFC 4180 quoting and UTF-8 BOM are supported.")
            Button(onClick = { launcher.launch("text/*") }) { Text("Select roster CSV") }
            ExportButton("Download CSV template", "text/csv", context, docs,
                produce = { "ROSTER_IMPORT_TEMPLATE.csv" to RosterImport.template() })
            readError?.let { MessageBanner(it, isError = true) }
            when (val result = importState) {
                is UiState.Success -> {
                    MessageBanner("Imported ${result.data.valid.size} student(s).")
                    InfoRow("Duplicate numbers", result.data.duplicates.size.toString())
                    InfoRow("Invalid rows", result.data.invalidRows.joinToString(", ").ifBlank { "none" })
                    InfoRow("Header recognized", result.data.headerRecognized.toString())
                    if (result.data.duplicates.isNotEmpty())
                        MessageBanner("Duplicates were not overwritten: ${result.data.duplicates.joinToString()}", true)
                }
                is UiState.Error -> MessageBanner(result.message, isError = true)
                is UiState.Loading -> CircularProgressIndicator()
                else -> Unit
            }
            Button(onClick = { nav.navigate(Routes.ROSTER) }) { Text("Back to roster") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateConfigurationScreen(
    examId: String, vm: ExamViewModel, nav: NavHostController
) {
    LaunchedEffect(examId) { vm.selectExam(examId) }
    val examState by vm.exam.collectAsState()
    val templatesState by vm.templates.collectAsState()
    val operation by vm.operation.collectAsState()
    var count by remember { mutableStateOf("20") }
    var pageSize by remember { mutableStateOf(PageSize.A4) }
    var labels by remember { mutableStateOf("A,B,C,D") }
    var columns by remember { mutableStateOf("8") }
    AppScreen("Template Configuration", examState, onBack = { nav.popBackStack() }) { exam ->
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            OperationBanner(operation)
            Text("${exam.title} · ${exam.totalQuestions} configured questions")
            OutlinedTextField(value = count, onValueChange = { count = it },
                label = { Text("Question count for this layout") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("Page size")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PageSize.entries.forEach { size ->
                    FilterChip(selected = pageSize == size, onClick = { pageSize = size },
                        label = { Text(size.name) })
                }
            }
            Text("Answer labels")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExamPresets.choiceSets.forEach { set ->
                    FilterChip(selected = labels == set.joinToString(","),
                        onClick = { labels = set.joinToString(",") },
                        label = { Text(set.joinToString("")) })
                }
            }
            OutlinedTextField(value = labels, onValueChange = { labels = it },
                label = { Text("Custom comma-separated labels") }, singleLine = true)
            OutlinedTextField(value = columns, onValueChange = { columns = it },
                label = { Text("Student ID digit columns") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            val parsedLabels = labels.split(',').map { it.trim() }.filter { it.length == 1 }.distinct()
            Button(enabled = parsedLabels.isNotEmpty() && parsedLabels.size <= 6, onClick = {
                vm.createTemplate(examId, pageSize,
                    (count.toIntOrNull() ?: exam.totalQuestions).coerceIn(1, 200),
                    parsedLabels, (columns.toIntOrNull() ?: 8).coerceIn(1, 12))
            }) { Text("Create versioned template + QR metadata") }
            when (val state = templatesState) {
                is UiState.Success -> {
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.data) { template ->
                            val layout = template.layout()
                            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Column(Modifier.padding(8.dp)) {
                                    Text(template.templateCode, style = MaterialTheme.typography.titleSmall)
                                    Text("v${template.version} · ${template.pageSize} · ${template.questionCount} items · " +
                                        "${layout.idColumns} ID columns · ${layout.markers.size} fiducials")
                                    Text("QR: ${layout.metadataPayload()}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Button(onClick = {
                        state.data.firstOrNull()?.let { nav.navigate(Routes.sheetPreview(it.id)) }
                    }, enabled = state.data.isNotEmpty()) { Text("Preview first template") }
                }
                is UiState.Empty -> MessageBanner(state.message)
                is UiState.Error -> MessageBanner(state.message, isError = true)
                else -> CircularProgressIndicator()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { nav.navigate(Routes.answerKey(examId)) }) { Text("Answer key") }
                Button(onClick = { nav.popBackStack() }) { Text("Back") }
            }
        }
    }
}

@Composable
fun AnswerSheetPreviewScreen(
    templateId: String, context: Context, docs: DocumentsViewModel, nav: NavHostController
) {
    var copies by remember { mutableStateOf(1) }
    var pdf by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pdfPath by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }
    var pageCount by remember { mutableStateOf(0) }
    val shareBytes = rememberShareBytes(context, docs)
    val shareExisting = rememberShareExistingFile(context, docs)
    val openExisting = rememberOpenExistingFile(context, docs)
    val saveToTree = rememberTreeSaver(context, docs)
    val scope = rememberCoroutineScope()
    LaunchedEffect(templateId, copies) {
        pdf = null; error = null; pdfPath = ""; page = 0
        try {
            val bytes = docs.blankSheet(templateId, copies)
            if (bytes.isEmpty()) error = "Template not found or PDF generation failed"
            else {
                val name = "EXAM_SHEET_${templateId}_${copies}_COPIES.pdf"
                docs.export(name, bytes, "application/pdf")
                pdf = name to bytes
                pdfPath = docs.previewPdfPath(name)
            }
        } catch (e: Exception) { error = e.javaClass.simpleName }
    }
    val state: UiState<Pair<String, ByteArray>> = when {
        error != null -> UiState.Error(error!!)
        pdf == null -> UiState.Loading
        else -> UiState.Success(pdf!!)
    }
    AppScreen("Answer Sheet Preview", state, onBack = { nav.popBackStack() }) { (name, bytes) ->
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            OutlinedTextField(value = copies.toString(), onValueChange = {
                copies = (it.toIntOrNull() ?: 1).coerceIn(1, 50)
            }, label = { Text("Copies / pages") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { shareBytes(name, bytes, "application/pdf") }) { Text("Share") }
                Button(onClick = { printPdf(context, name, bytes) }) { Text("Print") }
                Button(onClick = { saveToTree(name, bytes, "application/pdf") }) { Text("Save to folder") }
                Button(onClick = { openExisting(pdfPath, "application/pdf") }) { Text("Open") }
                Button(onClick = { shareExisting(pdfPath, "application/pdf") }) { Text("Share file") }
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = page > 0, onClick = { page-- }) { Text("Previous page") }
                Text("Page ${page + 1} / ${pageCount.coerceAtLeast(1)}")
                TextButton(enabled = page < pageCount - 1, onClick = { page++ }) { Text("Next page") }
            }
            PdfPreview(pdfPath, Modifier.fillMaxWidth().weight(1f), page) { pageCount = it }
            Button(onClick = { nav.popBackStack() }) { Text("Back") }
        }
    }
}
