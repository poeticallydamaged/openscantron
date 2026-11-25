package com.omrgrader.domain

import com.omrgrader.core.Json
import com.omrgrader.core.JsonValue

/** JSON serialization for fields stored as TEXT in Room (no Android dependency). */
object DomainJson {
    fun scoring(value: ScoringConfiguration): String = Json.stringify(Json.obj(
        "pointsPerCorrect" to Json.num(value.pointsPerCorrect),
        "incorrectDeduction" to Json.num(value.incorrectDeduction),
        "unansweredPoints" to Json.num(value.unansweredPoints),
        "multipleMarkPolicy" to Json.str(value.multipleMarkPolicy.name),
        "weights" to Json.obj(*value.weights.map { (k, v) -> k.toString() to Json.num(v) }.toTypedArray()),
        "partialCredit" to Json.bool(value.partialCredit),
        "passingThreshold" to Json.num(value.passingThreshold),
        "passingThresholdIsPercentage" to Json.bool(value.passingThresholdIsPercentage),
        "clampScoreAtZero" to Json.bool(value.clampScoreAtZero)
    ))

    fun scoring(text: String): ScoringConfiguration {
        val o = Json.parse(text) as JsonValue.Obj
        val weights = (o["weights"] as? JsonValue.Obj)?.values?.entries?.associate { (k, v) ->
            k.toInt() to (v as JsonValue.Num).value
        } ?: emptyMap()
        return ScoringConfiguration(
            pointsPerCorrect = o.double("pointsPerCorrect", 1.0),
            incorrectDeduction = o.double("incorrectDeduction", 0.0),
            unansweredPoints = o.double("unansweredPoints", 0.0),
            multipleMarkPolicy = runCatching { MultipleMarkPolicy.valueOf(o.string("multipleMarkPolicy")) }
                .getOrDefault(MultipleMarkPolicy.INVALID_AND_REVIEW),
            weights = weights,
            partialCredit = o.bool("partialCredit", false),
            passingThreshold = o.double("passingThreshold", 60.0),
            passingThresholdIsPercentage = o.bool("passingThresholdIsPercentage", true),
            clampScoreAtZero = o.bool("clampScoreAtZero", false)
        )
    }

    fun answers(value: Map<Int, String>): String = Json.stringify(Json.obj(
        *value.map { (k, v) -> k.toString() to Json.str(v) }.toTypedArray()
    ))

    fun answers(text: String): Map<Int, String> = try {
        (Json.parse(text) as JsonValue.Obj).values.entries.associate { (k, v) ->
            k.toInt() to (v as JsonValue.Str).value
        }
    } catch (_: Exception) { emptyMap() }

    fun labels(value: List<String>): String = Json.stringify(Json.arr(value.map { Json.str(it) }))
    fun labels(text: String): List<String> = try {
        (Json.parse(text) as JsonValue.Arr).values.map { (it as JsonValue.Str).value }
    } catch (_: Exception) { emptyList() }

    fun thresholds(value: OmrThresholds): String = Json.stringify(Json.obj(
        "blankFillRatio" to Json.num(value.blankFillRatio),
        "markFillRatio" to Json.num(value.markFillRatio),
        "minimumMargin" to Json.num(value.minimumMargin),
        "ambiguousMargin" to Json.num(value.ambiguousMargin),
        "relativeContrastMinimum" to Json.num(value.relativeContrastMinimum),
        "minimumRegistrationScore" to Json.num(value.minimumRegistrationScore),
        "minimumAlignmentScore" to Json.num(value.minimumAlignmentScore),
        "reviewConfidenceBelow" to Json.num(value.reviewConfidenceBelow),
        "minimumLaplacianVariance" to Json.num(value.minimumLaplacianVariance),
        "minimumShortSidePixels" to Json.num(value.minimumShortSidePixels),
        "studentIdColumns" to Json.num(value.studentIdColumns),
        "maxDarknessRatio" to Json.num(value.maxDarknessRatio)
    ))

    fun thresholds(text: String): OmrThresholds = try {
        val o = Json.parse(text) as JsonValue.Obj
        OmrThresholds(
            blankFillRatio = o.double("blankFillRatio", 0.20),
            markFillRatio = o.double("markFillRatio", 0.46),
            minimumMargin = o.double("minimumMargin", 0.10),
            ambiguousMargin = o.double("ambiguousMargin", 0.16),
            relativeContrastMinimum = o.double("relativeContrastMinimum", 0.12),
            minimumRegistrationScore = o.double("minimumRegistrationScore", 0.60),
            minimumAlignmentScore = o.double("minimumAlignmentScore", 0.55),
            reviewConfidenceBelow = o.double("reviewConfidenceBelow", 0.72),
            minimumLaplacianVariance = o.double("minimumLaplacianVariance", 45.0),
            minimumShortSidePixels = o.int("minimumShortSidePixels", 720),
            studentIdColumns = o.int("studentIdColumns", 8),
            maxDarknessRatio = o.double("maxDarknessRatio", 0.35)
        )
    } catch (_: Exception) { OmrThresholds() }
}
