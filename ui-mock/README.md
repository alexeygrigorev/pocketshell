# PocketShell UI Mock — terminal/browser visual loop

**Status: implemented browser-renderer slice; Android execution not verified in the authoring session. This is NOT the complete UI extraction requested in issue #2636.**

This tool displays PocketShell's existing real-screen Roborazzi fixtures in a browser and rerenders the selected fixture after source changes. It does not create a second HTML/React implementation of the app. Edit the same Kotlin composables that production uses: there is no visual-code copy-back step.

What it does:

- Discovers literal `@Test fun name() = render("label") { ... }` cases under `app2/src/test/java/com/pocketshell/next/render/`.
- Starts a Python-standard-library HTTP server on **127.0.0.1 only**.
- Runs **one selected render test**, keeps Gradle's daemon and build cache, and never requests APK assembly/install, Docker or an emulator.
- Watches `app2/`, `shared/`, build configuration and resources, excluding generated `build/` trees. Edits are debounced; builds are serialized and superseded results are not published as current.
- Shows fresh images, test-source location, measured duration and bounded Gradle logs. Build failures keep the last image visibly stale; an image for a different selected fixture is hidden.
- Requires both a newly produced successful JUnit report for the selected test and fresh PNG output. A green/no-op Gradle invocation is not accepted as a fresh render.

What it **does not** do:

- It is **not a clickable Android emulator** and not Vite-style hot module replacement. The browser shows PNGs. Select scenarios to inspect states; clicks inside an image do nothing.
- It **does not yet extract all screens/models into an independent presentation module**. `app2` and its test compilation dependencies still participate. Its mock data are existing test fixtures, not a new independent application data layer.
- It does not guarantee complete destination/state coverage. Unsupported fixture shapes are reported under catalog warnings, not silently counted as supported. A fixture can intentionally render only a part of a screen.
- It cannot validate real keyboard/IME policy, terminal behavior, Android permission flows or platform file pickers. Keep device/emulator acceptance for these.
- It does not change production code, publish APKs, alter the release workflow, or bypass release gates.

## DevBox: Linux server, Windows/Linux browser client

Use a separate working copy/branch. Do not switch the maintainer's daily-use checkout or interfere with an existing emulator.

The DevBox needs Python **3.10+**, the repository's **JDK 17**, Android SDK, and Gradle-wrapper dependencies. An existing working PocketShell build environment is sufficient; no Android Studio or emulator is needed for this visual mode. First compilation and dependency downloads remain real costs; no latency promise has been benchmarked.

From the repository root on the DevBox:

```bash
python3 ui-mock/serve.py --list
python3 ui-mock/serve.py --port 4173
```

On the maintainer's usual Linux box, the existing resource scope can wrap the **whole session** rather than replacing the per-render incremental build profile:

```bash
scripts/cgroup-run.sh --unit pocketshell-ui-mock -- \
  python3 ui-mock/serve.py --port 4173
```

Use that wrapper only where the project's cgroup/systemd prerequisites already work. On another Linux machine, use the plain Python command above and size Gradle/JVM limits for that machine. The renderer uses one Gradle worker and one test fork.

The server prints a URL similar to:

```text
http://127.0.0.1:4173/#token=RANDOM_SESSION_TOKEN
```

On Windows PowerShell or a Linux client, open an SSH tunnel in another terminal:

```bash
ssh -N -L 4173:127.0.0.1:4173 USER@DEVBOX
```

Replace `USER@DEVBOX` with your existing SSH alias/login. In the client's browser, open the **exact URL printed by the server, including the token fragment**. The Windows client needs only SSH and a browser, not Java, Android SDK, Python or Android Studio.

Keep the server and SSH tunnel running while iterating. Ctrl+C stops each foreground process. This tool does not stop a global Gradle daemon, kill any emulator, touch a production app installation or clear app data.

The token stays in browser session storage after opening the URL. A new server session produces a new token: reopen its printed URL after a server restart. Do not expose port 4173, ADB, or a Gradle service directly to the internet. Do not run other render commands writing the same module's `build/renders` directory concurrently with this viewer.

## Native Windows host (optional)

Running **the build on Windows**, rather than only viewing a Linux DevBox, additionally requires Python 3.10+, JDK 17 and the Android SDK command-line tools. The renderer uses `gradlew.bat` there.

```powershell
# From a working PocketShell checkout whose Android SDK is configured:
py -3 ui-mock/serve.py --list
py -3 ui-mock/serve.py --port 4173
```

Configure `ANDROID_HOME` or `local.properties` as in an ordinary command-line PocketShell build. With `sdk.dir` on Windows, forward slashes avoid Java-properties escaping issues, for example `sdk.dir=C:/Android/sdk`. No SSH tunnel is needed when the browser is on the same Windows machine.

Android toolchain provisioning reference: <https://developer.android.com/tools/sdkmanager>.

## Editing workflow for a coding agent

1. Select a fixture in the browser. The source path shown points to its `*Renders.kt` file; its `render { ... }` body shows the production composable and mock data used.
2. Change the **production composable** for layout/type/color changes. Change the fixture for mock states, long names, empty/loading/error states, or fixture-only data.
3. Save. The watcher invokes the single selected test again. Wait for the browser's **current** render status; a dimmed image is explicitly out of date.
4. Inspect error details in **Build log** if the render fails. Do not turn off freshness checks or make a mirrored HTML/Kotlin copy to get a green result.
5. For a new scenario, add a literal case following the existing convention. The catalog rescans after edits:

```kotlin
@Test
fun composerLongDraft() = render("composer-long-draft") {
    // Call the production composable with a deterministic UI state.
}
```

Use existing fixtures for valid types, callbacks and imports; the snippet illustrates the discoverable declaration format, not a complete test.

`--include-ui-kit` additionally exposes shared UI-kit render examples. These are explicitly labeled: some compose primitives to **mirror** a screen rather than call the actual screen, and are not evidence that the production layout changed. Default mode favors `app2` fixtures for this reason.

```bash
python3 ui-mock/serve.py --include-ui-kit
python3 ui-mock/serve.py --no-watch
python3 ui-mock/serve.py --case CASE_ID_FROM_LIST
```

## Architecture and limits

```text
Browser on Windows/Linux
  └─ SSH-forwarded loopback HTTP + session token
       └─ Python UI Mock server / one debounced render queue
            └─ one app2 Roborazzi test on the DevBox JVM
                 └─ existing fixture data → production Compose screen → PNG
```

`render.init.gradle` opts **only the selected `testDebugUnitTest` task** out of cache/up-to-date reuse. Compilation/resource tasks remain incremental and cacheable. It does not use `--rerun-tasks`, `clean`, `--no-daemon` or `--no-build-cache`.

The existing `App.kt` has a Robolectric guard for its eager production side effects. This viewer relies on the existing fixture/test harness; it is not a proof that the complete production dependency graph or every initializer is absent. Full runtime/dependency isolation is the separate extraction task in [AGENT-HANDOFF.md](AGENT-HANDOFF.md).

## Validation

Run the server's local tests (no Android required):

```bash
python3 -m unittest discover -s ui-mock/tests -v
node --check ui-mock/web/app.js
```

The tests cover catalog discovery, unsupported cases, serialized/superseded builds, stale output rejection, successful selected-test report requirements, error recovery, bounded image validation, render locks, path/command allowlists, session-token checks, Origin checks and DNS-rebinding-style Host rejection.

**Still required on the DevBox before calling the loop verified:** execute a real fixture; edit a composable and observe a fresh PNG; introduce a compile error and observe explicit failure/staleness; restore the source and observe recovery; open the browser through a Windows SSH tunnel. Python tests use fake Gradle output and do not prove Android rendering or native Windows process behavior.
