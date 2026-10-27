package com.fishese.moneyfly.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fishese.moneyfly.capture.SourcePreference
import com.fishese.moneyfly.ledger.LedgerCommand
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class SourceSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun capturedAppsComeFirstAndFiltersKeepParsingSeparateFromCapture() {
        val commands = mutableListOf<LedgerCommand>()
        val sources = listOf(SourcePreference("synthetic.ignored", ignored = true, revision = 4), SourcePreference("synthetic.active", parsingEnabled = false, revision = 2))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { SourceSettings(sources, { if (it == "synthetic.active") "Example Active" else "Example Ignored" }) { commands += it } } } }
        compose.onNodeWithTag("source_settings").performClick()
        val active = compose.onNodeWithText("Example Active [synthetic.active]").fetchSemanticsNode()
        val ignored = compose.onNodeWithText("Example Ignored [synthetic.ignored]").fetchSemanticsNode()
        assertTrue(active.positionInRoot.y < ignored.positionInRoot.y)
        compose.onNodeWithTag("parse_source_synthetic.active").performScrollTo().assertIsOff()
        compose.onNodeWithTag("restore_source_synthetic.active").assertIsOn()
        compose.onNodeWithTag("source_filter_ignored").performScrollTo().performClick()
        compose.onNodeWithText("Example Active [synthetic.active]").assertDoesNotExist()
        compose.onNodeWithTag("restore_source_synthetic.ignored").performScrollTo().performClick()
        val command = commands.single() as LedgerCommand.SetIgnoredSource
        assertFalse(command.ignored); assertEquals(4L, command.expectedRevision)
    }
}
