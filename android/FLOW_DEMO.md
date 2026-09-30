# Offline generation-flow demo

Separate package: `app.mascot3.flowdemo`, launcher name: `Маскот · Демо`.
The production package, characters and pending jobs are not modified.

This variant uses the actual production `GenerationScene`, `KnockHeroScreen`
and `AnimationLibraryScreen`, with local media from the bundled character Уна.
Three knocks simulate creation for five seconds. “Оставим” immediately opens
the home screen. One greeting is simulated in the background for five seconds;
a softly pulsing circular orbit loader occupies the character's slot, without a text
banner or a hidden character/player behind it. Once ready, the character appears
and the greeting plays on that same home screen, with no return to the knock scene.
The third knock switches to “Там кто-то есть” in the first rendering frame,
without resetting the visible dots or waiting for an asynchronous parent update.
Additional animations are revealed locally, one every two seconds; the normal
quantity picker defaults to four. “ДЕМО · 0 ₽ · начать заново” replays the flow.
Back from the library returns home; back from the flow resets the demo.

Safety: no INTERNET permission, empty OpenRouter key and client token,
ordinary `android.app.Application`, no production activity, providers,
services or broadcast receivers. No ViewModel, API, generation worker,
server connection or real provider requests are used. Production cost wording
in the reused screens does not represent any charge in this offline variant.
This tests the UI scenario, not real generation quality, latency or recovery.

Build:

```sh
./gradlew :app:assembleFlowdemo
```

Device UI test (targeting only the demo package):

```sh
./gradlew :app:connectedFlowdemoAndroidTest \
  -PMASCOT3_TEST_BUILD_TYPE=flowdemo \
  -Pandroid.testInstrumentationRunnerArguments.class=com.generativemascot.app.flowdemo.FlowDemoInstrumentedTest
```
