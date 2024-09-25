## XX.XX.XX

* Added a new function "setID(newDeviceId)" for managing device id changes according to the device ID Type.

* Mitigated an issue where json and junit dependencies had vulnerabilities.

## 24.1.0

* !! Major breaking change !! The following method and its functionality is deprecated from the "UserEditor" interface and will not function anymore:
  * "setLocale(String)"

* Added the user profiles feature interface, and it is accessible through "Countly::instance()::userProfile()" call.
* Added the location feature interface, and it is accessible through "Countly::instance()::location()" call.
* Added init time configuration for the location parameters:
  * "setLocation(String countryCode, String city, String location, String ipAddress)"
  * "setDisableLocation()"
* Crash Reporting interface added and accessible through "Countly::instance()::crash()" call.
* Added "disableUnhandledCrashReporting" function to the "Config" class to disable automatic uncaught crash reporting.
* Added "setMaxBreadcrumbCount(int)" function to the "Config" class to change allowed max breadcrumb count.
* Added the views feature interface, and it is accessible through "Countly::instance()::views()" call.
* Added a configuration function to set global view segmentation to the "Config" class:
  * "views.setGlobalViewSegmentation(Map<String, Object>)"

* Fixed a bug where setting custom user properties would not work.
* Fixed a bug where setting organization of the user would not work.
* Fixed a bug where sending a user profile picture with checksum was not possible.
* Fixed a bug where running time calculation was sent as a milliseconds but should have been in seconds.

* Deprecated "Countly::backendMode()" call, use "Countly::backendM" instead via "instance()" call.
* Deprecated "Usage::addLocation(double, double)" call, use "Countly::location::setLocation" instead via "instance()" call.
* Deprecated "Usage::addCrashReport()" call, use "Countly::crash" instead via "instance()" call.
* The following methods are deprecated from the "UserEditor" interface:
  * "commit()" instead use "Countly::userProfile::save" via "instance()" call
  * "pushUnique(String, Object)" instead use "Countly::userProfile::pushUnique" via "instance()" call
  * "pull(String, Object)" instead use "Countly::userProfile::pull" via "instance()" call
  * "push(String, Object)" instead use "Countly::userProfile::push" via "instance()" call
  * "setOnce(String, Object)" instead use "Countly::userProfile::setOnce" via "instance()" call
  * "max(String, double)" instead use "Countly::userProfile::saveMax" via "instance()" call
  * "min(String, double)" instead use "Countly::userProfile::saveMin" via "instance()" call
  * "mul(String, double)" instead use "Countly::userProfile::multiply" via "instance()" call
  * "inc(String, int)" instead use "Countly::userProfile::incrementBy" via "instance()" call
  * "optOutFromLocationServices()" instead use "Countly::location::disableLocation" via "instance()" call
  * "setLocation(double, double)" instead use "Countly::location::setLocation" via "instance()" call
  * "setLocation(String)" instead use "Countly::location::setLocation" via "instance()" call
  * "setCountry(String)" instead use "Countly::location::setLocation" via "instance()" call
  * "setCity(String)" instead use "Countly::location::setLocation" via "instance()" call
  * "setGender(String)" instead use "Countly::userProfile::setProperty" via "instance()" call
  * "setBirthyear(int)" instead use "Countly::userProfile::setProperty" via "instance()" call
  * "setBirthyear(String)" instead use "Countly::userProfile::setProperty" via "instance()" call
  * "setEmail(String)" instead use "Countly::userProfile::setProperty" via "instance()" call
  * "setName(String)" instead use "Countly::userProfile::setProperty" via "instance()" call
  * "setUsername(String)" instead use "Countly::userProfile::setProperty" via "instance()" call
  * "setPhone(String)" instead use "Countly::userProfile::setProperty" via "instance()" call
  * "setPicturePath(String)" instead use "Countly::userProfile::setProperty" via "instance()" call
  * "setOrg(String)" instead use "Countly::userProfile::setProperty" via "instance()" call
  * "setCustom(String, Object)" instead use "Countly::userProfile::setProperty" via "instance()" call
  * "set(String, Object)" instead use "Countly::userProfile::setProperty" via "instance()" call
  * "picture(byte[])" instead use "Countly::userProfile::setProperty" via "instance()" call
* Deprecated "View::start(bool)" call, use "Countly::views::startView" instead via "instance()" call.
* Deprecated "View::stop(bool)" call, use "Countly::views::stopViewWithName" or "Countly::views::stopViewWithID" instead via "instance()" call.
* Deprecated "Usage::view(String)" call, use "Countly::views::startView" instead via "instance()" call.
* Deprecated "Usage::view(String, bool)" call, use "Countly::views::startView" instead via "instance()" call.
* Deprecated "Countly::view(String)" call, use "Countly::views::startView" instead via "instance()" call.
* Deprecated "Countly::view(String, bool)" call, use "Countly::views::startView" instead via "instance()" call.

## 23.10.1

* Fixed a bug where getting the feedback widget list would fail if "salt" was enabled.

## 23.10.0

* ! Minor breaking change ! Calling "init" twice will now not reinitialize the SDK. The call will be ignored
* ! Minor breaking change ! 'bounce' and 'exit' segmentation values are now not sent from the SDK. They will be automatically applied on the server.

* Session update time duration increased to 60 seconds from 30 seconds.
* Adding remaining request queue size information to every request.
* Adding application version information to every request.
* Added the remote config feature.
* Added the Remote Config module with A/B testing. It is accessible through "Countly::instance()::remoteConfig()" call.
* Added configuration functions to configure Remote Config module on init:
  * 'enableRemoteConfigValueCaching' to enable caching of remote config values
  * 'enrollABOnRCDownload' to enroll A/B tests when remote config values downloaded
  * 'enableRemoteConfigAutomaticTriggers' to automatically download remote config values on init
  * 'remoteConfigRegisterGlobalCallback(RCDownloadCallback callback)' to register a remote config callback
* Added the ability to set the user profile picture with a URL
* Added the DeviceId interface. It is accessible through "Countly::instance()::deviceId()" call.
* Added a way to get device id type by calling "Countly::deviceId::getType" via "instance()" call
* The SDK now uses a different file for internal configuration. Old file will be deleted.

* Fixed a bug where it was not possible to send a profile picture with binary data

* Deprecated following functions from "Usage" interface and respective implementations:
  * "changeDeviceIdWithoutMerge" instead use "Countly::deviceId::changeWithoutMerge" via "instance()" call
  * "changeDeviceIdWithMerge" instead use "Countly::deviceId::changeWithMerge" via "instance()" call
  * "getDeviceId" instead use "Countly::deviceId::getID" via "instance()" call

## 23.8.0

* !! Major breaking change !! The following methods and their functionality are deprecated from the "UserEditor" interface and will not function anymore:
  * "addToCohort(key)"
  * "removeFromCohort(key)"

* Added the feedback widget feature. Added consent for it "Config.Feature.Feedback".
* Feedback module is accessible through "Countly::instance()::feedback()" call.

* Deprecated call "Countly::getSession" is removed
* Deprecated call "resetDeviceId" is removed

* Deprecated the init time configuration of 'setEventsBufferSize(eventsBufferSize)'. Introduced replacement 'setEventQueueSizeToSend(eventsQueueSize)'
* Deprecated the init time configuration of 'setSendUpdateEachSeconds(sendUpdateEachSeconds)'. Introduced replacement 'setUpdateSessionTimerDelay(delay)'
* In Countly class, the old "init(directory,config)" method is deprecated, use "init(config)" instead via "instance()" call.
* Deprecated "Countly::stop(boolean)" call, use "Countly::halt" or "Countly::stop" instead via "instance()" call.
* Deprecated "Countly::event" call, deprecated builder pattern. Use "Countly::events" instead via "instance()" call.
* Deprecated "Countly::timedEvent(String)" call, use "Countly::events::startEvent" instead via "instance()" call.
* Deprecated "Config::setUsePOST" and "Config::enableUsePOST" calls, use "Config::enableForcedHTTPPost" instead.
* The following methods are deprecated from the "Event" interface:
  * "record"
  * "endAndRecord"
  * "addSegment"
  * "addSegments"
  * "setSegmentation"
  * "setSum"
  * "setCount"
  * "setDuration"
  * "isInvalid"

## 22.09.2

* Fixed internal log calls that did not respect the configured log level and did not work with the log listener.

## 22.09.1

* Adding a way to override metrics sent by "begin session" requests.
* Fixed bug where "setApplicationVersion" would not set the application version in metrics
* ! Minor breaking change ! The following methods and their functionality are deprecated from the "Config" class and will not function anymore:
  * "getApplicationName"
  * "setApplicationName"

## 22.09.0

* The "resetDeviceId", "login", and "logout" have been deprecated.
* ! Minor breaking change ! The following methods and their functionality are deprecated from the "Config" class and will not function anymore:
  * "enableTestMode"
  * "disableTestMode"
  * "isTestModeEnabled"
  * "setLoggingTag"
  * "setSdkName"
  * "setSdkVersion"
  * "getSdkName"
  * "getSdkVersion"
  * "isDeviceIdFallbackAllowed"
  * "setDeviceIdFallbackAllowed"
  * "overrideModule"
  * "getModuleOverride"
  * "getCrashReportingANRCheckingPeriod"
  * "setCrashReportingANRCheckingPeriod"
  * "disableANRCrashReporting"

* ! Minor breaking change ! The following methods have been removed from the "Config" class:
  * "setAutoViewsTracking"
  * "setAutoSessionsTracking"
  * "setSessionAutoCloseAfter"
  * "isAutoViewsTrackingEnabled"
  * "isAutoSessionsTrackingEnabled"
  * "getSessionAutoCloseAfter"
  * "setSessionCooldownPeriod"

* ! Minor breaking change ! The "TestMode" functionality is being removed from the SDK.
* ! Minor breaking change ! The module override functionality is being removed from the SDK.
* ! Minor breaking change ! It is not possible to set the logging tag anymore.
* Fixed a bug where the wrong platform field value was being sent in the view request.
* Fixed a bug where view duration was reported in ms and not s.
* Updated JSON library version from "20180813" to "20230227".

## 20.11.5

* Fixed a bug where the backend mode module produces "null pointer exceptions" in case not initialized.

## 20.11.4

* Adding mitigations to an issue that would surface when stopping a view that was not started.

## 20.11.3

* Fixed a threading issue in the backend mode feature.

## 20.11.2

* Added backend mode feature and a new configuration field to enable it.

## 20.11.1

* Fixed a bug related to server response handling.
* Fixed a potential issue with parameters tampering protection while adding checksum.

## 20.11.0

* Added a new method to retrieve the current device id.
* Added new methods to change device ID with and without server merge.
* "Countly::getSession" has been deprecated and this is going to be removed in the future.
* "resetDeviceId" in the SDK public methods has been deprecated and this is going to be removed in the future.

## 19.09-sdk2-rc

* initial SDK release
* MavenCentral rerelease 
