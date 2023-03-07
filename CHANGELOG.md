22.09.0
* The "resetDeviceId", "login", and "logout" have been deprecated.
* ! Minor breaking change ! The following methods and their functionality are deprecated from the "Config" class and will not function anymore: 
	- "enableTestMode"
	- "disableTestMode"
	- "isTestModeEnabled"
	- "setLoggingTag"
  - "setSdkName"
  - "setSdkVersion"
  - "getSdkName"
  - "getSdkVersion"
  - "isDeviceIdFallbackAllowed"
  - "setDeviceIdFallbackAllowed"
  - "overrideModule"
  - "getModuleOverride"
  - "getCrashReportingANRCheckingPeriod"
  - "setCrashReportingANRCheckingPeriod"
  - "disableANRCrashReporting"

* ! Minor breaking change ! The following methods have been removed from the "Config" class:
  - "setAutoViewsTracking"
  - "setAutoSessionsTracking"
  - "setSessionAutoCloseAfter"
  - "isAutoViewsTrackingEnabled"
  - "isAutoSessionsTrackingEnabled"
  - "getSessionAutoCloseAfter"
  - "setSessionCooldownPeriod"

* ! Minor breaking change ! The "TestMode" functionality is being removed from the SDK.
* ! Minor breaking change ! The module override functionality is being removed from the SDK.
* ! Minor breaking change ! It is not possible to set the logging tag anymore.
* Fixed a bug where the wrong platform field value was being sent in the view request.
* Fixed a bug where view duration was reported in ms and not s.
* Updated JSON library version from "20180813" to "20230227". 

20.11.5
* Fixed a bug where the backend mode module produces "null pointer exceptions" in case not initialized.

20.11.4
* Adding mitigations to an issue that would surface when stopping a view that was not started.

20.11.3
* Fixed a threading issue in the backend mode feature.

20.11.2
* Added backend mode feature and a new configuration field to enable it.

20.11.1
* Fixed a bug related to server response handling.
* Fixed a potential issue with parameters tampering protection while adding checksum.

20.11.0
* Added a new method to retrieve the current device id.
* Added new methods to change device ID with and without server merge.
* "Countly::getSession" has been deprecated and this is going to be removed in the future.
* "resetDeviceId" in the SDK public methods has been deprecated and this is going to be removed in the future.

19.09-sdk2-rc
* initial SDK release
* MavenCentral rerelease 
