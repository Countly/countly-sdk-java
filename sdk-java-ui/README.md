# Countly Java SDK UI

JavaFX user interface for the [Countly Java SDK](https://github.com/Countly/countly-sdk-java). It
renders **Feedback Widgets** (Surveys, NPS, Ratings) and the **Content** feature on the desktop. It
is a thin companion to the core `java` artifact: the core drives analytics, this artifact only
displays.

Published separately, so an application that does not show anything on screen, a backend service for
example, never pulls JavaFX in.

## Requirements

- Java 11 or newer. The core SDK still runs on Java 8; JavaFX is what raises the floor here.
- JavaFX 17 or newer, with the `javafx.controls` and `javafx.web` modules.
- The `ly.count.sdk:java` core artifact, which comes in as a dependency of this one.

## Installation

Replace `LATEST_VERSION` with the version published on
[Maven Central](https://central.sonatype.com/artifact/ly.count.sdk/java-ui).

Gradle:

```groovy
dependencies {
  implementation 'ly.count.sdk:java-ui:LATEST_VERSION'
}
```

Maven:

```xml
<dependency>
  <groupId>ly.count.sdk</groupId>
  <artifactId>java-ui</artifactId>
  <version>LATEST_VERSION</version>
</dependency>
```

## Usage

Initialize the core SDK as usual, then reach for `CountlyWebView`.

### Feedback widgets

```java
Countly.instance().feedback().getAvailableFeedbackWidgets((widgets, error) -> {
    if (error != null || widgets.isEmpty()) {
        return;
    }
    // Must run on the JavaFX application thread.
    Platform.runLater(() -> CountlyWebView.presentFeedbackWidget(stage, widgets.get(0), null));
});
```

The card sizes and positions itself where the widget asks, on the primary screen's work area by
default, or inside the application window when you call
`CountlyWebView.setShowWidgetsWithinApp(true)`.

### Content

```java
Config config = new Config(serverUrl, appKey, storageDir)
    .enableFeatures(Config.Feature.Content);
config.content.setZoneTimerInterval(30);
config.content.setGlobalContentCallback((status, data) -> System.out.println(status));

Countly.instance().init(config);

CountlyWebView.enableContentZone();
// ...
CountlyWebView.disableContentZone();
```

Content interactions, the events it records, external links, resizes and closes, are handled for
you. Recorded events are pushed to the server straight away, so it can react to them.

Content is an **experimental** feature and its API can change.

### Your own display

`CountlyWebView` is a convenience. To render content with a different toolkit, implement
`ly.count.sdk.java.internal.ContentDisplay` and register it yourself:

```java
Countly.instance().content().setContentDisplay(myDisplay);
Countly.instance().content().enterContentZone();
```

A display must call the `onClosed` callback it is handed exactly once, including when it fails to
show anything, otherwise the content zone never resumes fetching.

## License

MIT, see the [LICENSE](https://github.com/Countly/countly-sdk-java/blob/master/LICENSE).
