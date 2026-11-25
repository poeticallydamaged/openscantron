package com.omrgrader.domain

import com.omrgrader.core.Csv
import com.omrgrader.core.Ids

data class RosterImportResult(
    val valid: List<Student>,
    val duplicates: List<String>,
    val invalidRows: List<Int>,
    val headerRecognized: Boolean
)

object RosterImport {
    private val header = listOf("student_number", "full_name", "section", "additional_information")

    fun template(): ByteArray = Csv.write(header, listOf(listOf("2026-0001", "Example", "Section A", "")))

    fun parse(csvBytes: ByteArray, existing: List<Student>): RosterImportResult {
        val rows = Csv.parseBytes(csvBytes)
        if (rows.isEmpty()) return RosterImportResult(emptyList(), emptyList(), emptyList(), false)
        val first = rows.first().map { it.trim().lowercase() }
        val headerRecognized = header.all { it in first }
        val dataRows = if (headerRecognized) rows.drop(1) else rows
        val indexOf: (String) -> Int = { name -> if (headerRecognized) first.indexOf(name) else header.indexOf(name) }
        val existingNumbers = existing.map { it.studentNumber.trim().lowercase() }.toMutableSet()
        val valid = ArrayList<Student>()
        val duplicates = ArrayList<String>()
        val invalid = ArrayList<Int>()
        dataRows.forEachIndexed { index, row ->
            val rowNumber = index + if (headerRecognized) 2 else 1
            val number = row.getOrNull(indexOf("student_number"))?.trim().orEmpty()
            val name = row.getOrNull(indexOf("full_name"))?.trim().orEmpty()
            if (number.isEmpty() || name.isEmpty()) { invalid.add(rowNumber); return@forEachIndexed }
            if (!existingNumbers.add(number.lowercase())) { duplicates.add(number); return@forEachIndexed }
            valid += Student(
                id = Ids.newId("student"),
                studentNumber = number,
                fullName = name,
                section = row.getOrNull(indexOf("section"))?.trim().orEmpty(),
                additionalInformation = row.getOrNull(indexOf("additional_information"))?.trim().orEmpty()
            )
        }
        return RosterImportResult(valid, duplicates.distinct(), invalid, headerRecognized)
    }
}
