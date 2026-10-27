package com.fishese.moneyfly.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fishese.moneyfly.capture.SourcePreference
import com.fishese.moneyfly.ledger.LedgerCommand
import com.fishese.moneyfly.recognition.TemplateDefinition
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

    @Test fun switchesKeepTheirAppAndPositionAndShowTemplateStatus() {
        val commands = mutableListOf<LedgerCommand>()
        val sources = mutableStateListOf(SourcePreference("synthetic.ignored", ignored = true, revision = 4),
            SourcePreference("synthetic.active", parsingEnabled = false, revision = 2), SourcePreference("synthetic.disabled", revision = 1))
        val templates = listOf(TemplateDefinition("enabled", "Enabled", "synthetic.active", emptyList(), enabled = true),
            TemplateDefinition("disabled", "Disabled", "synthetic.disabled", emptyList(), enabled = false))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            SourceSettings(sources, templates, sourceName = { when (it) { "synthetic.active" -> "Example Active"; "synthetic.disabled" -> "Example Disabled"; else -> "Example Ignored" } }) { command ->
                commands += command
                if (command is LedgerCommand.SetIgnoredSource) {
                    val index = sources.indexOfFirst { it.sourcePackage == command.sourcePackage }
                    sources[index] = sources[index].copy(ignored = command.ignored, revision = command.expectedRevision + 1)
                }
            }
        } } }
        compose.onNodeWithTag("source_settings").performClick()
        val active = compose.onNodeWithText("Example Active [synthetic.active]").fetchSemanticsNode()
        val ignored = compose.onNodeWithText("Example Ignored [synthetic.ignored]").fetchSemanticsNode()
        assertTrue(active.positionInRoot.y < ignored.positionInRoot.y)
        compose.onNode(hasText("Template available") and hasAnyAncestor(hasTestTag("source_row_synthetic.active")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Templates disabled") and hasAnyAncestor(hasTestTag("source_row_synthetic.disabled")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("No template") and hasAnyAncestor(hasTestTag("source_row_synthetic.ignored")), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("parse_source_synthetic.active").assertDoesNotExist()
        compose.onNodeWithTag("restore_source_synthetic.active").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("restore_source_synthetic.active").assertIsOff()
        compose.onNodeWithTag("restore_source_synthetic.disabled").assertIsOn()
        val newActive = compose.onNodeWithText("Example Active [synthetic.active]").fetchSemanticsNode()
        val newIgnored = compose.onNodeWithText("Example Ignored [synthetic.ignored]").fetchSemanticsNode()
        assertTrue(newActive.positionInRoot.y < newIgnored.positionInRoot.y)
        val command = commands.single() as LedgerCommand.SetIgnoredSource
        assertEquals("synthetic.active", command.sourcePackage); assertTrue(command.ignored); assertEquals(2L, command.expectedRevision)
        compose.onNodeWithTag("source_filter_ignored").performScrollTo().performClick()
        compose.onNodeWithTag("restore_source_synthetic.disabled").assertDoesNotExist()
        compose.onNodeWithTag("restore_source_synthetic.ignored").performScrollTo().performClick()
        assertEquals("synthetic.ignored", (commands.last() as LedgerCommand.SetIgnoredSource).sourcePackage)
    }

    @Test fun searchFindsAppNameAndPackageWithoutChangingSwitches() {
        val commands = mutableListOf<LedgerCommand>()
        val sources = listOf(SourcePreference("synthetic.one"), SourcePreference("synthetic.two"))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            SourceSettings(sources, sourceName = { if (it.endsWith("one")) "Camera" else "Weather" }) { commands += it }
        } } }
        compose.onNodeWithTag("source_settings").performClick()
        compose.onNodeWithTag("source_search").performTextInput("camera")
        compose.onNodeWithTag("restore_source_synthetic.one").assertExists()
        compose.onNodeWithTag("restore_source_synthetic.two").assertDoesNotExist()
        compose.onNodeWithTag("source_search").performTextClearance()
        compose.onNodeWithTag("source_search").performTextInput("TWO")
        compose.onNodeWithTag("restore_source_synthetic.one").assertDoesNotExist()
        compose.onNodeWithTag("restore_source_synthetic.two").assertExists()
        assertTrue(commands.isEmpty())
    }
}
