package com.omrgrader.di

import android.content.Context
import androidx.room.Room
import com.omrgrader.core.AppDispatchers
import com.omrgrader.core.AppLogger
import com.omrgrader.core.Clock
import com.omrgrader.data.local.*
import com.omrgrader.data.repository.OmrRepository
import com.omrgrader.data.repository.OmrRepositoryImpl
import com.omrgrader.export.PdfRenderer
import com.omrgrader.export.QrCodec
import com.omrgrader.omr.ImageOps
import com.omrgrader.platform.AndroidAppLogger
import com.omrgrader.platform.AndroidClock
import com.omrgrader.platform.AndroidDispatchers
import com.omrgrader.platform.AndroidPdfRenderer
import com.omrgrader.platform.AppDirectories
import com.omrgrader.platform.ImageOpsFactory
import com.omrgrader.platform.SafExporter
import com.omrgrader.platform.ZxingQrCodec
import com.omrgrader.data.preferences.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): OmrGraderDatabase {
        // A restore stages the database here; it is installed before Room opens the file,
        // then the marker is cleared. This makes Backup & Restore restart-safe.
        val pendingDir = java.io.File(context.filesDir, "backups/restore_pending")
        val pending = java.io.File(pendingDir, "omrgrader.db")
        val marker = java.io.File(pendingDir, "READY")
        if (marker.isFile && pending.isFile) {
            val target = java.io.File(context.filesDir.parentFile, "databases/omrgrader.db")
            target.parentFile?.mkdirs()
            pending.copyTo(target, overwrite = true)
            marker.delete()
        }
        return Room.databaseBuilder(context, OmrGraderDatabase::class.java, "omrgrader.db")
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }

    @Provides fun exams(db: OmrGraderDatabase) = db.examinationDao()
    @Provides fun students(db: OmrGraderDatabase) = db.studentDao()
    @Provides fun templates(db: OmrGraderDatabase) = db.templateDao()
    @Provides fun keys(db: OmrGraderDatabase) = db.answerKeyDao()
    @Provides fun batches(db: OmrGraderDatabase) = db.scanBatchDao()
    @Provides fun submissions(db: OmrGraderDatabase) = db.submissionDao()
    @Provides fun recognitions(db: OmrGraderDatabase) = db.questionRecognitionDao()
    @Provides fun archives(db: OmrGraderDatabase) = db.archiveDao()
    @Provides fun datasets(db: OmrGraderDatabase) = db.datasetDao()
    @Provides fun examCreation(db: OmrGraderDatabase) = db.examCreationDao()
    @Provides fun submissionWrite(db: OmrGraderDatabase) = db.submissionWriteDao()

    @Provides @Singleton fun dispatchers(): AppDispatchers = AndroidDispatchers()
    @Provides @Singleton fun logger(): AppLogger = AndroidAppLogger()
    @Provides @Singleton fun clock(): Clock = AndroidClock()
    @Provides @Singleton fun imageOps(): ImageOps = ImageOpsFactory.create()
    @Provides @Singleton fun qrCodec(impl: ZxingQrCodec): QrCodec = impl
    @Provides @Singleton fun pdfRenderer(impl: AndroidPdfRenderer): PdfRenderer = impl
    @Provides @Singleton fun directories(@ApplicationContext context: Context) = AppDirectories(context)
    @Provides @Singleton fun safExporter(impl: SafExporter) = impl
    @Provides @Singleton fun settings(impl: SettingsStore) = impl

    @Provides @Singleton
    fun repository(impl: OmrRepositoryImpl): OmrRepository = impl
}
