package com.omrgrader.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.omrgrader.domain.*
import com.omrgrader.export.DatasetSynchronizer
import com.omrgrader.presentation.components.*
import com.omrgrader.presentation.navigation.Routes
import com.omrgrader.presentation.viewmodels.*

@Composable
fun ExaminationResultsOverviewScreen(
    examId: String, vm: ResultsViewModel, docs: DocumentsViewModel, nav: NavHostController
) {
    val context = LocalContext.current
    LaunchedEffect(examId) { vm.load(examId) }
    val dataState by vm.data.collectAsState()
    val query by vm.query.collectAsState()
    val sortByScore by vm.sortByScore.collectAsState()
    val docsMessage by docs.message.collectAsState()
    AppScreen("Examination Results Overview", dataState,
        onBack = { nav.popBackStack() }, onRetry = { vm.load(examId) }) { data ->
        val rows = data.submissions.mapIndexed { index, sub ->
            Triple(sub, data.results.getOrNull(index), data.archives[sub.id])
        }.filter { (sub, grade, _) ->
            val student = sub.studentId?.let { data.students[it] }
            val haystack = listOf(sub.id, student?.studentNumber ?: "", student?.fullName ?: "",
                student?.section ?: "", grade?.passingStatus ?: "", sub.gradingStatus.name)
                .joinToString(" ")
            query.isBlank() || haystack.contains(query, ignoreCase = true)
        }.let { list ->
            if (sortByScore) list.sortedByDescending { it.second?.score ?: 0.0 }
            else list.sortedBy { (sub, _, _) ->
                sub.studentId?.let { data.students[it]?.studentNumber } ?: "UNASSIGNED"
            }
        }
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                StatCard("Submissions", data.stats.submissionCount.toString(), Modifier.weight(1f))
                StatCard("Pass", data.stats.passCount.toString(), Modifier.weight(1f))
                StatCard("Pending", data.stats.pendingCount.toString(), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                StatCard("Mean", "%.2f".format(data.stats.mean), Modifier.weight(1f))
                StatCard("Median", "%.2f".format(data.stats.median), Modifier.weight(1f))
                StatCard("Pass %", "%.1f%%".format(data.stats.passPercentage), Modifier.weight(1f))
            }
            OutlinedTextField(value = query, onValueChange = { vm.setQuery(it) },
                label = { Text("Search student, submission or status") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { vm.toggleSort() }) {
                    Text(if (sortByScore) "Sort by student" else "Sort by score")
                }
                Button(onClick = { vm.load(examId) }) { Text("Refresh KPIs") }
                Button(onClick = { docs.regenerate(examId) }) { Text("Regenerate CSV") }
            }
            OperationBanner(docsMessage)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { nav.navigate(Routes.itemAnalysis(examId)) }) { Text("Item analysis") }
                Button(onClick = { nav.navigate(Routes.csvDatasets(examId)) }) { Text("CSV datasets") }
                Button(onClick = { nav.navigate(Routes.exportHub(examId)) }) { Text("Export hub") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ExportButton("Wide CSV", "text/csv", context, docs,
                    produce = { DatasetSynchronizer.fileName(examId, DatasetType.WIDE_ANSWERS) to
                        docs.wideCsv(examId) })
                ExportButton("Audit CSV", "text/csv", context, docs,
                    produce = { DatasetSynchronizer.fileName(examId, DatasetType.RECOGNITION_AUDIT) to
                        docs.auditCsv(examId) })
                ExportButton("Exam report PDF", "application/pdf", context, docs,
                    produce = { "EXAM_${examId}_PERFORMANCE_REPORT.pdf" to docs.examReportPdf(examId) })
            }
            if (rows.isEmpty()) MessageBanner("No submissions match this search.")
            LazyColumn(Modifier.weight(1f)) {
                items(rows) { (sub, grade, archive) ->
                    val student = sub.studentId?.let { data.students[it] }
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(student?.fullName ?: "Unassigned",
                                style = MaterialTheme.typography.titleSmall)
                            Text("${student?.studentNumber ?: "UNASSIGNED"} · ${student?.section ?: ""}")
                            Text("Score ${grade?.score ?: sub.score ?: 0.0} / ${grade?.maxScore ?: 0.0} · " +
                                "${grade?.percentage ?: sub.percentage ?: 0.0}% · ${grade?.passingStatus ?: sub.gradingStatus}")
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { nav.navigate(Routes.resultDetails(examId, sub.id)) }) {
                                    Text("Details")
                                }
                                TextButton(onClick = {
                                    nav.navigate(Routes.verification(sub.id, examId))
                                }) { Text("Verify") }
                                TextButton(
                                    enabled = archive != null &&
                                        archive.generationStatus == GenerationStatus.GENERATED &&
                                        archive.pdfFilePath.isNotBlank(),
                                    onClick = { archive?.let { nav.navigate(Routes.pdfViewer(it.pdfFilePath)) } }
                                ) { Text(if (archive == null) "No PDF" else "View PDF") }
                            }
                        }
                    }
                }
            }
            Button(onClick = { nav.navigate(Routes.EXAMS) }) { Text("Back to examinations") }
        }
    }
}

@Composable
fun StudentResultDetailsScreen(
    examId: String, submissionId: String,
    vm: ResultsViewModel, docs: DocumentsViewModel, nav: NavHostController
) {
    val context = LocalContext.current
    LaunchedEffect(examId) { vm.load(examId) }
    val state by vm.data.collectAsState()
    val docsMessage by docs.message.collectAsState()
    val uiState: UiState<Triple<Submission, GradingResult, ResultsData>> = when (state) {
        is UiState.Loading -> UiState.Loading
        is UiState.Empty -> UiState.Empty(state.message)
        is UiState.Error -> UiState.Error(state.message)
        is UiState.Success -> {
            val index = state.data.submissions.indexOfFirst { it.id == submissionId }
            if (index < 0) UiState.Error("Submission not found in Room snapshot")
            else {
                val result = state.data.results.getOrNull(index)
                if (result == null) UiState.Error("Grading result missing for submission")
                else UiState.Success(Triple(state.data.submissions[index], result, state.data))
            }
        }
    }
    AppScreen("Student Result Details", uiState, onBack = { nav.popBackStack() }) { (sub, grade, data) ->
        val student = sub.studentId?.let { data.students[it] }
        val archive = data.archives[sub.id]
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Text(data.examination.title, style = MaterialTheme.typography.titleMedium)
            InfoRow("Student", "${student?.fullName ?: "Unassigned"} (${student?.studentNumber ?: "UNASSIGNED"})")
            InfoRow("Section", student?.section ?: "")
            InfoRow("Submission", sub.id)
            InfoRow("Key version", sub.answerKeyVersionId)
            InfoRow("Score", "${grade.score} / ${grade.maxScore} (${grade.percentage}%)")
            InfoRow("Status", "${sub.gradingStatus} · ${grade.passingStatus}")
            InfoRow("Counts", "correct ${grade.correctCount}, incorrect ${grade.incorrectCount}, " +
                "blank ${grade.blankCount}, invalid ${grade.invalidCount}, pending ${grade.pendingReviewCount}")
            OperationBanner(docsMessage)
            LazyColumn(Modifier.weight(1f)) {
                items(grade.items) { item ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Row(Modifier.padding(8.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Q${item.questionNumber.toString().padStart(3, '0')}")
                            Text("Answer ${item.finalAnswer} / Key ${item.keyAnswer}")
                            Text("${item.outcome} · ${item.points}")
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ExportButton("Student result PDF", "application/pdf", context, docs,
                    produce = { "EXAM_${examId}_STUDENT_RESULT_${submissionId}.pdf" to
                        docs.studentReportPdf(examId, submissionId) })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { nav.navigate(Routes.verification(submissionId, examId)) }) {
                        Text("Recognition verification")
                    }
                    Button(enabled = archive?.generationStatus == GenerationStatus.GENERATED,
                        onClick = { archive?.let { nav.navigate(Routes.pdfViewer(it.pdfFilePath)) } }) {
                        Text("Archived PDF")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { vm.load(examId) }) { Text("Refresh") }
                    Button(onClick = { nav.navigate(Routes.results(examId)) }) { Text("Back to results") }
                }
            }
        }
    }
}

@Composable
fun ItemAnalysisScreen(
    examId: String, vm: ResultsViewModel, docs: DocumentsViewModel, nav: NavHostController
) {
    val context = LocalContext.current
    LaunchedEffect(examId) { vm.load(examId) }
    val state by vm.data.collectAsState()
    val docsMessage by docs.message.collectAsState()
    AppScreen("Question / Item Analysis", state, onBack = { nav.popBackStack() },
        onRetry = { vm.load(examId) }) { data ->
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Text(data.examination.title, style = MaterialTheme.typography.titleMedium)
            InfoRow("Mean %", "%.2f".format(data.stats.meanPercentage))
            InfoRow("Difficulty definition", "p = correct / attempted; null when no attempts")
            InfoRow("Items", data.items.size.toString())
            OperationBanner(docsMessage)
            LazyColumn(Modifier.weight(1f)) {
                items(data.items) { item ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Text("Q${item.questionNumber.toString().padStart(3, '0')} · key ${item.keyAnswer}",
                                style = MaterialTheme.typography.titleSmall)
                            Text("correct %.1f%% · incorrect %.1f%% · blank %.1f%% · invalid %.1f%% · pending %.1f%%".format(
                                item.correctPercentage, item.incorrectPercentage, item.blankPercentage,
                                item.invalidPercentage, item.pendingPercentage))
                            Text("difficulty p=${item.difficultyIndex?.let { "%.2f".format(it) } ?: "n/a"} · " +
                                "top distractor ${item.mostChosenDistractor ?: "-"}")
                            Text("distractors: " + (item.distractorFrequency.entries.joinToString { "${it.key}=${it.value}" }
                                .ifBlank { "none" }))
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ExportButton("Summary CSV", "text/csv", context, docs,
                    produce = { "EXAM_${examId}_SUMMARY.csv" to docs.summaryCsv(examId) })
                ExportButton("Exam report PDF", "application/pdf", context, docs,
                    produce = { "EXAM_${examId}_PERFORMANCE_REPORT.pdf" to docs.examReportPdf(examId) })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { vm.load(examId) }) { Text("Refresh") }
                    Button(onClick = { nav.navigate(Routes.results(examId)) }) { Text("Results") }
                    Button(onClick = { nav.navigate(Routes.csvDatasets(examId)) }) { Text("CSV datasets") }
                }
            }
        }
    }
}
