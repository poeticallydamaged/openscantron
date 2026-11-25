package com.omrgrader.presentation.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.omrgrader.data.preferences.AppSettings
import com.omrgrader.presentation.screens.*
import com.omrgrader.presentation.theme.OpenScantronTheme
import com.omrgrader.presentation.viewmodels.*

@Composable
fun AppNav() {
    val nav: NavHostController = rememberNavController()
    val context = LocalContext.current
    val settingsVm: SettingsViewModel = hiltViewModel()
    val dashboardVm: DashboardViewModel = hiltViewModel()
    val examVm: ExamViewModel = hiltViewModel()
    val scanVm: ScanViewModel = hiltViewModel()
    val verificationVm: VerificationViewModel = hiltViewModel()
    val resultsVm: ResultsViewModel = hiltViewModel()
    val documentsVm: DocumentsViewModel = hiltViewModel()
    val theme by settingsVm.settings.collectAsState(initial = AppSettings())

    OpenScantronTheme(theme.theme) {
        NavHost(navController = nav, startDestination = Routes.DASHBOARD) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(dashboardVm, nav)
            }
            composable(Routes.EXAMS) {
                ExaminationListScreen(examVm, nav)
            }
            composable(Routes.CREATE_EXAM) {
                CreateExaminationScreen(examVm, nav)
            }
            composable(Routes.EDIT_EXAM,
                arguments = listOf(navArgument("examId") { type = NavType.StringType })) {
                val examId = it.arguments?.getString("examId").orEmpty()
                EditExaminationScreen(examId, examVm, nav)
            }
            composable(Routes.ANSWER_KEY,
                arguments = listOf(navArgument("examId") { type = NavType.StringType })) {
                val examId = it.arguments?.getString("examId").orEmpty()
                AnswerKeyEditorScreen(examId, examVm, nav)
            }
            composable(Routes.ROSTER) {
                StudentRosterScreen(examVm, documentsVm, nav)
            }
            composable(Routes.ROSTER_IMPORT) {
                StudentImportScreen(examVm, documentsVm, nav)
            }
            composable(Routes.TEMPLATE,
                arguments = listOf(navArgument("examId") { type = NavType.StringType })) {
                val examId = it.arguments?.getString("examId").orEmpty()
                TemplateConfigurationScreen(examId, examVm, nav)
            }
            composable(Routes.SHEET_PREVIEW,
                arguments = listOf(navArgument("templateId") { type = NavType.StringType })) {
                val templateId = it.arguments?.getString("templateId").orEmpty()
                AnswerSheetPreviewScreen(templateId, context, documentsVm, nav)
            }
            composable(Routes.SCANNING,
                arguments = listOf(navArgument("examId") { type = NavType.StringType })) {
                val examId = it.arguments?.getString("examId").orEmpty()
                ScanningCameraScreen(examId, scanVm, settingsVm, nav)
            }
            composable(Routes.CAPTURE_REVIEW,
                arguments = listOf(navArgument("examId") { type = NavType.StringType })) {
                val examId = it.arguments?.getString("examId").orEmpty()
                CapturedImageReviewScreen(examId, scanVm, nav)
            }
            composable(Routes.PROCESSING,
                arguments = listOf(navArgument("examId") { type = NavType.StringType })) {
                val examId = it.arguments?.getString("examId").orEmpty()
                OmrProcessingStatusScreen(examId, scanVm, nav)
            }
            composable(Routes.VERIFICATION, arguments = listOf(
                navArgument("submissionId") { type = NavType.StringType },
                navArgument("examId") { type = NavType.StringType }
            )) {
                val submissionId = it.arguments?.getString("submissionId").orEmpty()
                val examId = it.arguments?.getString("examId").orEmpty()
                RecognitionVerificationScreen(submissionId, examId, verificationVm, examVm,
                    scanVm, documentsVm, nav)
            }
            composable(Routes.BATCH_SUMMARY,
                arguments = listOf(navArgument("examId") { type = NavType.StringType })) {
                val examId = it.arguments?.getString("examId").orEmpty()
                BatchScanningSummaryScreen(examId, scanVm, documentsVm, nav)
            }
            composable(Routes.RESULTS,
                arguments = listOf(navArgument("examId") { type = NavType.StringType })) {
                val examId = it.arguments?.getString("examId").orEmpty()
                ExaminationResultsOverviewScreen(examId, resultsVm, documentsVm, nav)
            }
            composable(Routes.RESULT_DETAILS, arguments = listOf(
                navArgument("examId") { type = NavType.StringType },
                navArgument("submissionId") { type = NavType.StringType }
            )) {
                val examId = it.arguments?.getString("examId").orEmpty()
                val submissionId = it.arguments?.getString("submissionId").orEmpty()
                StudentResultDetailsScreen(examId, submissionId, resultsVm, documentsVm, nav)
            }
            composable(Routes.ITEM_ANALYSIS,
                arguments = listOf(navArgument("examId") { type = NavType.StringType })) {
                val examId = it.arguments?.getString("examId").orEmpty()
                ItemAnalysisScreen(examId, resultsVm, documentsVm, nav)
            }
            composable(Routes.ARCHIVES) {
                ScannedAnswerArchivesScreen(documentsVm, nav)
            }
            composable(Routes.PDF_VIEWER,
                arguments = listOf(navArgument("path") { type = NavType.StringType })) {
                val path = Uri.decode(it.arguments?.getString("path").orEmpty())
                PdfDocumentViewerScreen(path, documentsVm, nav)
            }
            composable(Routes.CSV_DATASETS,
                arguments = listOf(navArgument("examId") { type = NavType.StringType })) {
                val examId = it.arguments?.getString("examId").orEmpty()
                CsvDatasetsManagerScreen(examId, documentsVm, nav)
            }
            composable(Routes.CSV_PREVIEW,
                arguments = listOf(navArgument("path") { type = NavType.StringType })) {
                val path = Uri.decode(it.arguments?.getString("path").orEmpty())
                CsvDatasetPreviewScreen(path, documentsVm, nav)
            }
            composable(Routes.EXPORT_HUB,
                arguments = listOf(navArgument("examId") { type = NavType.StringType })) {
                val examId = it.arguments?.getString("examId").orEmpty()
                ExportSharingHubScreen(examId, examVm, documentsVm, nav)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(settingsVm, nav)
            }
            composable(Routes.BACKUP,
                arguments = listOf(navArgument("examId") { type = NavType.StringType })) {
                val examId = it.arguments?.getString("examId").orEmpty()
                BackupRestoreScreen(examId, documentsVm, nav)
            }
        }
    }
}
