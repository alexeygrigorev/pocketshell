package com.pocketshell.app.settings

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.SETTINGS_BUTTON_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings persistence end-to-end coverage (issue #149).
 *
 * The user journey under test is:
 *
 * 1. User opens Settings, picks a non-default Voice language (French) via
 *    the radio group.
 * 2. User picks a non-default Appearance theme (Light) via the radio group.
 * 3. User navigates back, the app is force-stopped (or its activity
 *    process state is dropped), and the user reopens it.
 * 4. The user opens Settings again. The previously selected values must
 *    still be the active ones.
 *
 * Why the user-facing settings under test are Voice → French and
 * Appearance → Light:
 *
 *  - Both are radio groups with deterministic non-default options. The
 *    `voice language` default is [AppSettings.VOICE_LANGUAGE_AUTO] (the
 *    sentinel "auto" string), so "French" (code `fr`) is a stable second
 *    value that cannot be reached by accident.
 *  - The `theme` default is [ThemePreference.System], so [ThemePreference.Light]
 *    is a stable second value (Dark is also valid, but Light is closer to
 *    what a user would realistically toggle on first install).
 *  - Both rows already carry test tags
 *    ([voiceLanguageOptionTestTag] / [themeOptionTestTag]) introduced
 *    when the Settings screen first shipped, so this test adds no
 *    production-side instrumentation.
 *
 * **Why we don't actually invoke `adb shell am force-stop com.pocketshell.app`:**
 *
 * In this project's androidTest setup, the instrumentation runs in the
 * **same OS process** as the target app (`com.pocketshell.app`); the test
 * runner package `com.pocketshell.app.test` is loaded as instrumentation
 * INTO that target process. Force-stopping the target package kills the
 * test process too, which surfaces as the cryptic Gradle error
 * `Instrumentation run failed due to Process crashed.`. We confirmed
 * this experimentally during the first implementation pass — even a
 * weaker `am kill com.pocketshell.app` is enough to kill the test
 * process once the activity has been finished (the process becomes
 * cached, and `am kill` is allowed to act on cached processes). The
 * [com.pocketshell.app.proof.ColdInstallE2eTest] kdoc documents the
 * same limitation in detail and avoids the same call for the same
 * reason.
 *
 * The faithful equivalent of "process death" that we can perform from
 * inside instrumentation:
 *
 *  - **Close** the current [ActivityScenario], which finishes
 *    [MainActivity] and lets any tear-down callbacks fire.
 *  - **Read** the persisted [AppSettings] through a freshly-constructed
 *    [SettingsRepository] instance. Because the constructor seeds its
 *    in-memory [kotlinx.coroutines.flow.MutableStateFlow] from
 *    `SharedPreferences.getString/getFloat/...`, the result reflects
 *    whatever was committed to disk — exactly the same data the OS would
 *    re-read after a real process kill. This is the ground-truth
 *    assertion: anything on this snapshot survived a process death.
 *  - **Relaunch** [MainActivity] via [ActivityScenario.launch] and open
 *    Settings again. This proves the production-side flow that mounts
 *    the screen from a clean activity state still reflects the persisted
 *    values without restart.
 *
 * **Production paths the test breaks against:**
 *
 *  - [SettingsRepository.setVoiceLanguage] /
 *    [SettingsRepository.setTheme] would regress if the prefs write was
 *    accidentally deferred, dropped, or routed to an in-memory-only
 *    store.
 *  - [SettingsRepository.readSnapshot] would regress if a future change
 *    silently re-introduced defaults for these keys (the constructor is
 *    the cold-read path that runs on every process restart).
 *  - The Settings → Voice / Appearance UI bindings would regress if the
 *    radio rows stopped emitting their `onSelect` callback or if the
 *    callback no longer routed to `setVoiceLanguage` / `setTheme`.
 *
 * The test does not depend on Docker, SSH, or any remote fixture; it
 * runs on the bare emulator and finishes in seconds.
 */
@RunWith(AndroidJUnit4::class)
class SettingsPersistenceE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @Before
    fun resetSettingsToDefaults() {
        // The shared preference file is per-app, not per-test, so a prior
        // run (or another test in the same suite) could have left it
        // non-default. Clear it synchronously via `commit()` so the
        // ActivityScenario we launch below seeds the singleton
        // [SettingsRepository] from a clean baseline.
        val ctx = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        ctx.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Sanity-check the baseline before any activity work so a flaky
        // wipe surfaces as a clear precondition failure rather than a
        // confusing toggle-didn't-take assertion later.
        val baseline = SettingsRepository(ctx).settings.value
        assertEquals(
            "expected voice language default after wipe",
            AppSettings.VOICE_LANGUAGE_AUTO,
            baseline.voiceLanguage,
        )
        assertEquals(
            "expected theme default after wipe",
            ThemePreference.System,
            baseline.theme,
        )
    }

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        // Clean up state so a follow-up test in the same suite isn't
        // contaminated by what this one wrote. We don't rely on
        // [resetSettingsToDefaults] alone because the next test may run
        // in a different class without that @Before.
        val ctx = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        ctx.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun changedSettings_persistAcrossActivityFinishAndRelaunch() {
        // ---------------------------------------------------------------
        // Phase 1 — first launch. The activity mounts on top of a wiped
        // prefs file; the singleton SettingsRepository's cold read yields
        // the documented defaults.
        // ---------------------------------------------------------------
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // The host-list screen mounts first; navigate into Settings via
        // its top-bar Settings gear.
        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            compose.onAllNodesWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            compose.onAllNodesWithTag(SETTINGS_TITLE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // ---------------------------------------------------------------
        // Phase 2 — toggle Appearance → Light. The Appearance section is
        // the first item in the LazyColumn so it's already on screen,
        // but we still scroll to it to keep the test resilient to layout
        // changes that move it below the fold on smaller viewports.
        // ---------------------------------------------------------------
        val lightTag = themeOptionTestTag(ThemePreference.Light)
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(lightTag))
        compose.onNodeWithTag(lightTag, useUnmergedTree = true).performClick()

        // ---------------------------------------------------------------
        // Phase 3 — toggle Voice → French. The Voice section lives below
        // the fold on a Pixel 7 viewport (~854dp) so we scroll the
        // language radio into view via the parent LazyColumn before
        // tapping, matching the pattern used by `UsageScreenE2eTest` for
        // the Usage row.
        // ---------------------------------------------------------------
        val frenchTag = voiceLanguageOptionTestTag(FRENCH_LANGUAGE_CODE)
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(frenchTag))
        compose.onNodeWithTag(frenchTag, useUnmergedTree = true).performClick()

        // Issue #397: the Voice silence control must make the aggressive
        // vs conservative trade-off explicit now that the default is a
        // long-dictation value.
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(VOICE_SILENCE_SLIDER_TAG))
        compose.onNodeWithText("Aggressive", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Conservative", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(
            "Default is conservative for long dictation; lower values stop more aggressively.",
            useUnmergedTree = true,
        ).assertExists()

        // ---------------------------------------------------------------
        // Phase 4 — ground-truth: the toggles wrote to SharedPreferences
        // synchronously. A fresh repository instance reads the same on-
        // disk state the OS would re-read after a process kill, so this
        // assertion is exactly the contract of "survived a process
        // restart".
        // ---------------------------------------------------------------
        val ctx = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        val afterWriteSnap = SettingsRepository(ctx).settings.value
        assertEquals(
            "voice language should be French after Settings UI toggle",
            FRENCH_LANGUAGE_CODE,
            afterWriteSnap.voiceLanguage,
        )
        assertEquals(
            "theme should be Light after Settings UI toggle",
            ThemePreference.Light,
            afterWriteSnap.theme,
        )
        // Sanity: the toggled values are genuinely non-default so a
        // future default change can't accidentally make the test pass
        // without driving the UI.
        assertNotEquals(
            "French should differ from the default voice language",
            AppSettings.VOICE_LANGUAGE_AUTO,
            afterWriteSnap.voiceLanguage,
        )
        assertNotEquals(
            "Light should differ from the default theme",
            ThemePreference.System,
            afterWriteSnap.theme,
        )

        // ---------------------------------------------------------------
        // Phase 5 — navigate back, then drop the activity. Closing the
        // ActivityScenario finishes MainActivity; any in-memory state
        // that was NOT persisted to disk would be lost here. The
        // singleton SettingsRepository survives in the same process, so
        // the relaunch in phase 6 also exercises the warm-process path;
        // the fresh-repository read in phase 4 covers the actual cold-
        // read-from-disk path that a real `am force-stop` would
        // exercise.
        // ---------------------------------------------------------------
        compose.onNodeWithTag(SETTINGS_BACK_TAG, useUnmergedTree = true).performClick()
        // Confirm we are back at the host-list tab bar.
        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            compose.onAllNodesWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        launchedActivity?.close()
        launchedActivity = null

        // Intentionally NOT invoking `am force-stop com.pocketshell.app`
        // or `am kill com.pocketshell.app` here. On this project's
        // single-process instrumentation setup, both kill the test
        // runner too — `am force-stop` always, and `am kill` whenever
        // the process is treated as cached (which it becomes the moment
        // the only foreground activity finishes), surfacing as the
        // generic Gradle error `Instrumentation run failed due to
        // Process crashed.`. We reproduced exactly that on a first
        // implementation attempt that called `am kill` here. The
        // persistence guarantee under test lives on disk anyway, and
        // the cold-read assertion below (and in Phase 4) is the
        // ground-truth equivalent of a process-restart re-read.

        // ---------------------------------------------------------------
        // Phase 6 — relaunch and reopen Settings. The activity remounts
        // against the same singleton repository, which itself reflects
        // the disk-persisted state. We re-assert from a freshly-built
        // repository (so even if the singleton somehow held a stale
        // value the disk read would expose the bug), and we drive the
        // Settings UI again to prove the user-visible flow still mounts
        // without restart.
        // ---------------------------------------------------------------
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            compose.onAllNodesWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            compose.onAllNodesWithTag(SETTINGS_TITLE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Scroll both rows into view so we know the screen rendered with
        // the new state mounted, not a stale cached version. The
        // existence of the LazyColumn item proves the screen recomposed
        // with the persisted [AppSettings] in hand; the radio's visual
        // selection lives in a hand-rolled `RadioMark` glyph rather than
        // a Compose `Modifier.selectable` semantics property, so we
        // cannot use `assertIsSelected()`. We instead rely on the disk
        // read below for the value assertion, and on the node existing
        // for the "screen mounted" assertion.
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(themeOptionTestTag(ThemePreference.Light)))
        compose.onNodeWithTag(
            themeOptionTestTag(ThemePreference.Light),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(voiceLanguageOptionTestTag(FRENCH_LANGUAGE_CODE)))
        compose.onNodeWithTag(
            voiceLanguageOptionTestTag(FRENCH_LANGUAGE_CODE),
            useUnmergedTree = true,
        ).assertExists()

        // Cold-read the prefs file from a fresh repository to assert the
        // values survived. This is the canonical disk-persistence check
        // and the strongest evidence that a real `am force-stop` +
        // relaunch would observe the same values.
        val afterRelaunchSnap = SettingsRepository(ctx).settings.value
        assertEquals(
            "voice language should remain French after activity close + relaunch",
            FRENCH_LANGUAGE_CODE,
            afterRelaunchSnap.voiceLanguage,
        )
        assertEquals(
            "theme should remain Light after activity close + relaunch",
            ThemePreference.Light,
            afterRelaunchSnap.theme,
        )

        // Touch the test-tag constants once so static analysis doesn't
        // flag them as unused from this test file. They are passive
        // references; the production code's constants are the canonical
        // copy.
        assertTrue(
            "voice French tag should be non-empty",
            voiceLanguageOptionTestTag(FRENCH_LANGUAGE_CODE).isNotEmpty(),
        )
        assertTrue(
            "theme Light tag should be non-empty",
            themeOptionTestTag(ThemePreference.Light).isNotEmpty(),
        )
    }

    private companion object {
        /**
         * Generous timeout for "UI element is in the tree" waits. The
         * Settings screen mounts in well under a second on a healthy
         * emulator; the deadline is sized for slow CI emulators and
         * cold-start jank, not for steady-state.
         */
        const val WAIT_TIMEOUT_MS: Long = 10_000

        /** ISO-639-1 code for French — matches the entry in
         * [AppSettings.VOICE_LANGUAGE_OPTIONS]. */
        const val FRENCH_LANGUAGE_CODE: String = "fr"

        /**
         * Mirrors the private `PREFS_NAME` in [SettingsRepository]. Keep
         * in sync with that constant; if the repository ever migrates to
         * DataStore, this needs to migrate too.
         */
        const val SETTINGS_PREFS_NAME: String = "app_settings"
    }
}
