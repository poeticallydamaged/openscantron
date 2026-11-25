#!/usr/bin/env python3
"""Generate Room schema JSON for v1 and v2 used by migration tests.

Room/KSP normally exports these files during a Gradle build. This script reproduces
the same schema metadata deterministically for offline source review and for
MigrationTestHelper until an Android SDK build can regenerate them.
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/schemas/com.omrgrader.data.local.OmrGraderDatabase"
OUT.mkdir(parents=True, exist_ok=True)


def field(path: str, col: str, affinity: str, not_null: bool, default: str | None = None):
    result = {
        "fieldPath": path,
        "columnName": col,
        "affinity": affinity,
        "notNull": not_null,
    }
    if default is not None:
        result["defaultValue"] = default
    return result


def column_sql(f):
    sql = f"`{f['columnName']}` {f['affinity']}"
    if f["notNull"]:
        sql += " NOT NULL"
    if "defaultValue" in f:
        sql += f" DEFAULT {f['defaultValue']}"
    return sql


def index(name: str, columns: list[str], unique: bool = False):
    word = "CREATE UNIQUE INDEX" if unique else "CREATE INDEX"
    cols = ", ".join(f"`{c}`" for c in columns)
    return {
        "name": name,
        "unique": unique,
        "columnNames": columns,
        "orders": [],
        "createSql": f"{word} IF NOT EXISTS `{name}` ON `${{TABLE_NAME}}` ({cols})",
    }


def fk(parent: str, child_fields: list[str], parent_fields: list[str], on_delete: str):
    return {
        "table": parent,
        "onDelete": on_delete,
        "onUpdate": "NO ACTION",
        "childFields": child_fields,
        "parentFields": parent_fields,
        "parentColumns": parent_fields,
    }


def entity(table: str, fields: list[dict], pk: list[str], indices: list[dict], fks: list[dict]):
    cols = ", ".join(column_sql(f) for f in fields)
    primary = ", ".join(f"`{c}`" for c in pk)
    return {
        "tableName": table,
        "createSql": f"CREATE TABLE IF NOT EXISTS `${{TABLE_NAME}}` ({cols}, PRIMARY KEY({primary}))",
        "fields": fields,
        "primaryKey": {"columnNames": pk, "autoGenerate": False},
        "indices": indices,
        "foreignKeys": fks,
    }


def schema(version: int):
    exams = [
        field("id", "id", "TEXT", True), field("title", "title", "TEXT", True),
        field("subject", "subject", "TEXT", True), field("description", "description", "TEXT", True),
        field("date", "date", "INTEGER", True), field("totalQuestions", "total_questions", "INTEGER", True),
        field("totalPoints", "total_points", "REAL", True),
        field("scoringConfiguration", "scoring_configuration", "TEXT", True),
        field("activeTemplateId", "active_template_id", "TEXT", False),
        field("activeAnswerKeyVersionId", "active_answer_key_version_id", "TEXT", False),
        field("isArchived", "is_archived", "INTEGER", True),
        field("createdAt", "created_at", "INTEGER", True),
        field("updatedAt", "updated_at", "INTEGER", True),
    ]
    students = [
        field("id", "id", "TEXT", True),
        field("studentNumber", "student_number", "TEXT", True),
        field("fullName", "full_name", "TEXT", True),
        field("section", "section", "TEXT", True),
        field("additionalInformation", "additional_information", "TEXT", True),
    ]
    templates = [
        field("id", "id", "TEXT", True),
        field("templateCode", "template_code", "TEXT", True),
        field("examId", "exam_id", "TEXT", True),
        field("pageSize", "page_size", "TEXT", True),
        field("questionCount", "question_count", "INTEGER", True),
        field("answerChoices", "answer_choices", "TEXT", True),
        field("layoutDefinition", "layout_definition", "TEXT", True),
        field("version", "version", "INTEGER", True),
        field("createdAt", "created_at", "INTEGER", True),
    ]
    keys = [
        field("id", "id", "TEXT", True), field("examId", "exam_id", "TEXT", True),
        field("version", "version", "INTEGER", True), field("answers", "answers", "TEXT", True),
        field("scoringRules", "scoring_rules", "TEXT", True),
        field("createdAt", "created_at", "INTEGER", True),
    ]
    batches = [
        field("id", "id", "TEXT", True), field("examId", "exam_id", "TEXT", True),
        field("status", "status", "TEXT", True),
        field("totalScanned", "total_scanned", "INTEGER", True),
        field("totalProcessed", "total_processed", "INTEGER", True),
        field("totalPending", "total_pending", "INTEGER", True),
        field("createdAt", "created_at", "INTEGER", True),
        field("updatedAt", "updated_at", "INTEGER", True),
    ]
    submissions = [
        field("id", "id", "TEXT", True), field("examId", "exam_id", "TEXT", True),
        field("studentId", "student_id", "TEXT", False),
        field("templateId", "template_id", "TEXT", True),
        field("answerKeyVersionId", "answer_key_version_id", "TEXT", True),
        field("batchId", "batch_id", "TEXT", True),
        field("recognizedAnswers", "recognized_answers", "TEXT", True),
        field("finalAnswers", "final_answers", "TEXT", True),
        field("score", "score", "REAL", False), field("percentage", "percentage", "REAL", False),
        field("gradingStatus", "grading_status", "TEXT", True),
        field("processingStatus", "processing_status", "TEXT", True),
        field("imageFingerprint", "image_fingerprint", "TEXT", True),
        field("scannedAt", "scanned_at", "INTEGER", True),
    ]
    recognitions = [
        field("id", "id", "TEXT", True), field("submissionId", "submission_id", "TEXT", True),
        field("questionNumber", "question_number", "INTEGER", True),
        field("recognizedAnswer", "recognized_answer", "TEXT", True),
        field("finalAnswer", "final_answer", "TEXT", True),
        field("confidence", "confidence", "REAL", True),
        field("classification", "classification", "TEXT", True),
        field("manuallyCorrected", "manually_corrected", "INTEGER", True),
    ]
    if version >= 2:
        recognitions += [
            field("correctionNote", "correction_note", "TEXT", True, "''"),
            field("correctedAt", "corrected_at", "INTEGER", False),
            field("alternatives", "alternatives", "TEXT", True, "'[]'"),
        ]
    else:
        recognitions += [field("correctedAt", "corrected_at", "INTEGER", False)]
    archives = [
        field("id", "id", "TEXT", True), field("submissionId", "submission_id", "TEXT", True),
        field("originalImagePath", "original_image_path", "TEXT", True),
        field("pdfFilePath", "pdf_file_path", "TEXT", True),
        field("pdfFileName", "pdf_file_name", "TEXT", True),
        field("pdfFileSize", "pdf_file_size", "INTEGER", True),
        field("pdfChecksum", "pdf_checksum", "TEXT", True),
    ]
    if version >= 2:
        archives.append(field("generationStatus", "generation_status", "TEXT", True, "'GENERATED'"))
    archives.append(field("generatedAt", "generated_at", "INTEGER", False))
    datasets = [
        field("id", "id", "TEXT", True), field("examId", "exam_id", "TEXT", True),
        field("datasetType", "dataset_type", "TEXT", True),
        field("filePath", "file_path", "TEXT", True),
        field("fileName", "file_name", "TEXT", True),
        field("fileSize", "file_size", "INTEGER", True),
        field("generationVersion", "generation_version", "INTEGER", True),
        field("synchronizationStatus", "synchronization_status", "TEXT", True),
        field("recordCount", "record_count", "INTEGER", True),
        field("lastGeneratedAt", "last_generated_at", "INTEGER", False),
    ]
    if version >= 2:
        datasets.append(field("lastError", "last_error", "TEXT", False))

    student_indices = [index("index_students_student_number", ["student_number"], True)]
    submission_indices = [
        index("index_submissions_exam_id", ["exam_id"]),
        index("index_submissions_student_id", ["student_id"]),
        index("index_submissions_batch_id", ["batch_id"]),
        index("index_submissions_image_fingerprint", ["image_fingerprint"]),
    ]
    if version >= 2:
        student_indices.append(index("index_students_section", ["section"]))
        submission_indices.append(index("index_submissions_exam_id_student_id", ["exam_id", "student_id"]))

    entities = [
        entity("examinations", exams, ["id"], [], []),
        entity("students", students, ["id"], student_indices, []),
        entity("answer_sheet_templates", templates, ["id"], [
            index("index_answer_sheet_templates_template_code", ["template_code"], True),
            index("index_answer_sheet_templates_exam_id", ["exam_id"]),
        ], [fk("examinations", ["exam_id"], ["id"], "CASCADE")]),
        entity("answer_keys", keys, ["id"], [
            index("index_answer_keys_exam_id_version", ["exam_id", "version"], True),
        ], [fk("examinations", ["exam_id"], ["id"], "CASCADE")]),
        entity("scan_batches", batches, ["id"], [index("index_scan_batches_exam_id", ["exam_id"])],
               [fk("examinations", ["exam_id"], ["id"], "CASCADE")]),
        entity("submissions", submissions, ["id"], submission_indices, [
            fk("examinations", ["exam_id"], ["id"], "CASCADE"),
            fk("students", ["student_id"], ["id"], "SET NULL"),
            fk("answer_sheet_templates", ["template_id"], ["id"], "RESTRICT"),
            fk("answer_keys", ["answer_key_version_id"], ["id"], "RESTRICT"),
            fk("scan_batches", ["batch_id"], ["id"], "RESTRICT"),
        ]),
        entity("question_recognitions", recognitions, ["id"], [
            index("index_question_recognitions_submission_id_question_number",
                  ["submission_id", "question_number"], True),
        ], [fk("submissions", ["submission_id"], ["id"], "CASCADE")]),
        entity("answer_sheet_archives", archives, ["id"], [
            index("index_answer_sheet_archives_submission_id", ["submission_id"], True),
        ], [fk("submissions", ["submission_id"], ["id"], "CASCADE")]),
        entity("dataset_archives", datasets, ["id"], [
            index("index_dataset_archives_exam_id_dataset_type", ["exam_id", "dataset_type"], True),
        ], [fk("examinations", ["exam_id"], ["id"], "CASCADE")]),
    ]
    digest = hashlib.sha256(json.dumps(entities, sort_keys=True).encode()).hexdigest()[:32]
    db = {
        "formatVersion": 1,
        "database": {
            "version": version,
            "identityHash": digest,
            "entities": entities,
            "views": [],
            "setupQueries": [
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                f"INSERT OR REPLACE INTO room_master_table(id,identity_hash) VALUES(42, \"{digest}\")",
            ],
        },
    }
    (OUT / f"{version}.json").write_text(json.dumps(db, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT / f'{version}.json'} identityHash={digest}")


for v in (1, 2):
    schema(v)
