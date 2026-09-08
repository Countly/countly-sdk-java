# Countly Java SDK UI

JavaFX user interface for the [Countly Java SDK](https://github.com/Countly/countly-sdk-java). It
renders **Feedback Widgets** (Surveys, NPS, Ratings) and the **Content** feature on the desktop. It
is a thin companion to the core `java` artifact: the core drives analytics, this artifact only
displays.

Published separately, so an application that does not show anything on screen, a backend service for
example, never pulls JavaFX in.

## Requirements

- Java 17 or newer. The core SDK still runs on Java 8; JavaFX is what raises the floor here.
- JavaFX 21 or newer, with the `javafx.controls` and `javafx.web` modules. JavaFX 20 is the first
  release with a public API for a transparent web view background (`WebView.setPageFill`), which the
  content overlay needs in order to float over your application rather than sit in an opaque box.
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

The quick calls fetch the widget list, pick the widget you asked for and show it:

```java
CountlyWebView.presentNPS(stage);
CountlyWebView.presentSurvey(stage, "onboarding");
CountlyWebView.presentRating(stage, "", () -> System.out.println("dismissed"));
```

The second argument is a widget ID, a widget name or one of the widget's tags. Leave it out, or pass
an empty string, to take the first available widget of that type. These are safe to call from any
thread; the card is shown on the JavaFX application thread once the list arrives.

To pick the widget yourself:

```java
Countly.instance().feedback().getAvailableFeedbackWidgets((widgets, error) -> {
    if (error != null || widgets.isEmpty()) {
        return;
    }
    // Must run on the JavaFX application thread.
    Platform.runLater(() -> CountlyWebView.presentFeedbackWidget(stage, widgets.get(0), null));
});
```

The card takes the size the widget asks for, and is anchored the way that widget type is anchored on
the web: an NPS at the bottom centre, a survey at the bottom left or bottom right per its
`appearance.position`, and a rating as a centred card.

By default that happens on the work area of the screen the application is on. To keep both widget
cards and content blocks inside the application window instead, set this once before showing
anything:

```java
CountlyWebView.setShowWidgetsWithinApp(true);
```

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

Content is placed on the screen your application window is on, and follows it if the window is
dragged to another monitor. Pass a window explicitly when your application has more than one and you
want content pinned to a particular one:

```java
CountlyWebView.enableContentZone(myWindow);
```

The content window is shown only once its page has painted, so nothing flashes as an empty
rectangle. A page that fails to load, or does not load within 20 seconds, is abandoned and the zone
resumes fetching.

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
