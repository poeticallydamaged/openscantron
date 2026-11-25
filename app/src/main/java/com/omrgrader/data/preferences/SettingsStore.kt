package com.omrgrader.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omrgrader.domain.DomainJson
import com.omrgrader.domain.OmrThresholds
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val thresholds: OmrThresholds = OmrThresholds(),
    val compressionQuality: Int = 88,
    val includeProcessingPage: Boolean = true,
    val autoCapture: Boolean = false,
    val sortBatchByStudentId: Boolean = false
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("open_scantron_settings")

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val THRESHOLDS = stringPreferencesKey("omr_thresholds")
        val COMPRESSION = intPreferencesKey("compression_quality")
        val PROCESSING_PAGE = booleanPreferencesKey("include_processing_page")
        val AUTO_CAPTURE = booleanPreferencesKey("auto_capture")
        val SORT_BATCH = booleanPreferencesKey("sort_batch_by_student_id")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            theme = runCatching { ThemeMode.valueOf(prefs[Keys.THEME] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            thresholds = DomainJson.thresholds(prefs[Keys.THRESHOLDS] ?: DomainJson.thresholds(OmrThresholds())),
            compressionQuality = (prefs[Keys.COMPRESSION] ?: 88).coerceIn(40, 100),
            includeProcessingPage = prefs[Keys.PROCESSING_PAGE] ?: true,
            autoCapture = prefs[Keys.AUTO_CAPTURE] ?: false,
            sortBatchByStudentId = prefs[Keys.SORT_BATCH] ?: false
        )
    }

    suspend fun setTheme(mode: ThemeMode) { context.settingsDataStore.edit { it[Keys.THEME] = mode.name } }
    suspend fun setThresholds(value: OmrThresholds) {
        context.settingsDataStore.edit { it[Keys.THRESHOLDS] = DomainJson.thresholds(value) }
    }
    suspend fun setCompression(quality: Int) {
        context.settingsDataStore.edit { it[Keys.COMPRESSION] = quality.coerceIn(40, 100) }
    }
    suspend fun setIncludeProcessingPage(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.PROCESSING_PAGE] = value }
    }
    suspend fun setAutoCapture(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_CAPTURE] = value }
    }
    suspend fun setSortBatch(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.SORT_BATCH] = value }
    }
}
