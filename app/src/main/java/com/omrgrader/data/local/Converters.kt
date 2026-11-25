package com.omrgrader.data.local

import androidx.room.TypeConverter
import com.omrgrader.domain.DomainJson
import com.omrgrader.domain.OmrThresholds
import com.omrgrader.domain.ScoringConfiguration

class Converters {
    @TypeConverter fun scoringToText(value: ScoringConfiguration): String = DomainJson.scoring(value)
    @TypeConverter fun scoringFromText(value: String): ScoringConfiguration = DomainJson.scoring(value)
    @TypeConverter fun answersToText(value: Map<Int, String>): String = DomainJson.answers(value)
    @TypeConverter fun answersFromText(value: String): Map<Int, String> = DomainJson.answers(value)
    @TypeConverter fun labelsToText(value: List<String>): String = DomainJson.labels(value)
    @TypeConverter fun labelsFromText(value: String): List<String> = DomainJson.labels(value)
    @TypeConverter fun thresholdsToText(value: OmrThresholds): String = DomainJson.thresholds(value)
    @TypeConverter fun thresholdsFromText(value: String): OmrThresholds = DomainJson.thresholds(value)
}
