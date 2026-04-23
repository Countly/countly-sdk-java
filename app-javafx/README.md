# Countly Java SDK · JavaFX Demo

A desktop reference demo for `sdk-java` that exercises every user-facing
module and presents Countly **feedback widgets (survey, NPS, rating)**
inside a JavaFX `WebView`, with the same URL interception protocol the
C++/Qt sample uses (see
[`countly-sdk-demos/cpp_demo`](../../countly-sdk-demos/cpp_demo) and its
`Countly_Feedback_Widget_Implementation_Guide.html`).

This module lives alongside `app-java` and builds against `sdk-java` via
a Gradle project dependency — no copied jars.

## Layout

```text
app-javafx/
├── build.gradle                    # JavaFX plugin + project(':sdk-java')
└── src/main/
    ├── java/ly/count/javafx/demo/
    │   ├── Main.java               # JavaFX entry point
    │   ├── AppContext.java         # defaults (server url, app key, storage dir)
    │   └── ui/
    │       ├── MainView.java
    │       ├── LogPanel.java       # bottom log panel, captures SDK log messages too
    │       ├── InitPane.java
    │       ├── EventsPane.java
    │       ├── ViewsPane.java
    │       ├── UserProfilePane.java
    │       ├── LocationPane.java
    │       ├── CrashesPane.java
    │       ├── DeviceIdPane.java
    │       ├── RemoteConfigPane.java
    │       ├── FeedbackWidgetsPane.java  # WebView + widget list
    │       ├── FeedbackHttp.java         # raw /o/sdk?method=feedback fetch (captures 'wv')
    │       ├── WidgetCard.java
    │       └── SdkUtil.java
    └── resources/styles/app.css
```

## Build & run

> **JVM requirement:** the `org.openjfx.javafxplugin` dependency requires
> Gradle to run on **Java 11+**. The root `settings.gradle` only adds
> `:app-javafx` to the build when `JavaVersion.current().isJava11Compatible()`,
> so on a Java 8 Gradle daemon this module is silently skipped and
> `sdk-java` / `app-java` keep building as before. Point your Gradle JVM
> at a JDK 11+ (IntelliJ → *Settings → Build, Execution, Deployment → Build
> Tools → Gradle → Gradle JVM*) to enable it.

From the `countly-sdk-java/` repo root:

```bash
./gradlew :app-javafx:run
```

or from this module:

```bash
cd app-javafx
gradle run
```

## SDK features demonstrated

| Tab | SDK surface |
| --- | --- |
| **Init** | `Countly.init / stop / halt`, `Config.*` toggles, session begin/update/end, consent grant/revoke |
| **Events** | `events().recordEvent(...)` in all overloads, timed `startEvent / endEvent / cancelEvent` |
| **Views** | `views().startView`, `startAutoStoppedView`, stop/pause/resume by name & id, global & per-view segmentation |
| **User Profile** | predefined keys (`PredefinedUserPropertyKeys`), custom set/setOnce/setProperties, push/pushUnique/pull, increment/incrementBy/multiply, saveMax/saveMin, save, clear |
| **Location** | `location().setLocation(country, city, gps, ip)`, `disableLocation()` |
| **Crashes** | `crashes().addCrashBreadcrumb`, `recordHandledException`, `recordUnhandledException` (± segmentation) |
| **Device ID** | `deviceId().getID/getType`, `changeWithMerge`, `changeWithoutMerge`, `login`, `logout` |
| **Remote Config** | `downloadAllKeys / downloadSpecificKeys / downloadOmittingKeys`, `getValues / getValue / getAllValuesAndEnroll / getValueAndEnroll`, enroll/exit AB tests, `clearAll` |
| **Feedback Widgets** | `feedback().getAvailableFeedbackWidgets`, `constructFeedbackWidgetUrl`, `getFeedbackWidgetData`, `reportFeedbackWidgetManually` |

## Feedback widgets via WebView

1. `Fetch widgets` calls `getAvailableFeedbackWidgets(...)` and — in
   parallel — a raw HTTP `GET /o/sdk?method=feedback` via
   `FeedbackHttp.fetchVersions()` so we can recover the widget's `wv`
   field (the SDK's typed `CountlyFeedbackWidget` drops it). Each card
   shows the widget type badge plus `legacy` / `v<wv>`.
2. **Open** builds the WebView URL via `FeedbackHttp.constructWebViewUrl(...)`,
   same formula as `cpp_demo/main.cpp`: `custom={"tc":1}` always, plus
   `xb=1 & rw=1` when `wv` is present. Those flags are what make the
   WebView render its own **X close button** for versioned widgets.
3. **Inspect data** calls `getFeedbackWidgetData(widget, ...)` and shows
   the JSON definition in a dialog (useful when you want to render a
   custom UI instead of the web widget).
4. **Report manually** sends a hand-crafted result via
   `reportFeedbackWidgetManually(widget, null, result)`.

### URL interception

JavaFX's `WebView` does not expose a navigation-approval callback like
Qt's `acceptNavigationRequest`. Instead we subscribe to
`engine.locationProperty()`; when the new URL's host is
`countly_action_event` we call `engine.getLoadWorker().cancel()` to
abort the navigation and handle the message ourselves:

| Query signal | Meaning | Action |
| --- | --- | --- |
| `cly_x_int=1` | external link inside a widget | open in the system browser via `Desktop.browse` |
| `cly_widget_command=1 & close=1` | widget close button | call `reportFeedbackWidgetManually(widget, null, null)` and reset the WebView |
| `cly_x_action_event=1 & action=link` | link action | open in the system browser |
| `cly_x_action_event=1 & close=1` | action + close | dismiss the WebView too |

`engine.setCreatePopupHandler` catches `target="_blank"` links (privacy
policy links, etc.) and forwards them to the system browser.
