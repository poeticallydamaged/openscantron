package com.omrgrader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omrgrader.presentation.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun dashboardRenders() {
        compose.onNodeWithText("OpenScantron Dashboard").assertIsDisplayed()
        compose.onNodeWithText("Offline workflow").assertIsDisplayed()
    }

    @Test fun examinationListOpens() {
        compose.onNodeWithText("Exams").performClick()
        compose.onNodeWithText("Examinations").assertIsDisplayed()
    }

    @Test fun createExaminationFormOpens() {
        compose.onNodeWithText("Create exam").performClick()
        compose.onNodeWithText("Create Examination").assertIsDisplayed()
        compose.onNodeWithText("Create and edit answer key").assertIsDisplayed()
    }
}
