package ly.count.sdk.java.internal;

public enum CoreFeature {
    Events(1 << 1, ModuleEvents::new),
    Sessions(1 << 2, ModuleSessions::new),
    Views(1 << 3, ModuleViews::new),
    CrashReporting(1 << 4, ModuleCrashes::new),
    Location(1 << 5, ModuleLocation::new),
    UserProfiles(1 << 6, ModuleUserProfile::new),
    /*
    THESE ARE ONLY HERE AS DOCUMENTATION
    THEY SHOW WHICH ID'S ARE USED IN ANDROID
    Push(1 << 10),
    Attribution(1 << 11),

    PerformanceMonitoring(1 << 14);
    */
    BackendMode(1 << 12, ModuleBackendMode::new),
    RemoteConfig(1 << 13, ModuleRemoteConfig::new),
    TestDummy(1 << 19),//used during testing
    DeviceId(1 << 20, ModuleDeviceIdCore::new),
    Requests(1 << 21, ModuleRequests::new),
    Logs(1 << 22),
    Feedback(1 << 23, ModuleFeedback::new);

    private final int index;

    private ModuleBaseCreator creator;

    CoreFeature(int index) {
        this.index = index;
    }

    CoreFeature(int index, ModuleBaseCreator creator) {
        this.creator = creator;
        this.index = index;
    }

    public ModuleBaseCreator getCreator() {
        return creator;
    }

    public int getIndex() {
        return index;
    }
}
