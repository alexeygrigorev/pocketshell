package com.pocketshell.next.usage

import androidx.test.core.app.ApplicationProvider

/**
 * A real [UsageGlanceCache] over Robolectric's per-test application (issue
 * #2632).
 *
 * Deliberately the production class over a fresh in-memory `SharedPreferences`
 * rather than a fake: the whole point of the cache is that a landing screen
 * reads something a previous process wrote, and a fake would prove nothing
 * about that. Robolectric gives each test method its own application, so the
 * file starts empty every time.
 */
fun usageGlanceCache(): UsageGlanceCache =
    UsageGlanceCache(ApplicationProvider.getApplicationContext())
