package ly.count.sdk.java.internal;

import java.util.HashMap;
import java.util.Map;

public enum CoreFeature {
    Sessions(1 << 1, new ModuleBaseCreator() {
        @Override
        public ModuleBase create() {
            return new ModuleSessions();
        }
    }),
    Events(1 << 2),
    Views(1 << 3, new ModuleBaseCreator() {
        @Override
        public ModuleBase create() {
            return new ModuleViews();
        }
    }),
    CrashReporting(1 << 4, new ModuleBaseCreator() {
        @Override
        public ModuleBase create() {
            return new ModuleCrash();
        }
    }),
    Location(1 << 5),
    UserProfiles(1 << 6),
    StarRating(1 << 7, new ModuleBaseCreator() {
        @Override
        public ModuleBase create() {
            return new ModuleRatingCore();
        }
    }),

    /*
    THESE ARE ONLY HERE AS DOCUMENTATION
    THEY SHOW WHICH ID'S ARE USED IN ANDROID
    Push(1 << 10),
    Attribution(1 << 11),

    PerformanceMonitoring(1 << 14);
    */
    BackendMode(1 << 12, new ModuleBaseCreator() {
        @Override
        public ModuleBase create() {
            return new ModuleBackendMode();
        }
    }),
    RemoteConfig(1 << 13, new ModuleBaseCreator() {
        @Override
        public ModuleBase create() {
            return new ModuleRemoteConfig();
        }
    }),
    TestDummy(1 << 19),//used during testing
    DeviceId(1 << 20, new ModuleBaseCreator() {
        @Override
        public ModuleBase create() {
            return new ModuleDeviceIdCore();
        }
    }),
    Requests(1 << 21, new ModuleBaseCreator() {
        @Override
        public ModuleBase create() {
            return new ModuleRequests();
        }
    }),
    Logs(1 << 22);

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

    private static Map<Integer, CoreFeature> featureMap = new HashMap<Integer, CoreFeature>() {{
        put(Sessions.index, Sessions);
        put(Events.index, Events);
        put(Views.index, Views);
        put(CrashReporting.index, CrashReporting);
        put(Location.index, Location);
        put(UserProfiles.index, UserProfiles);
        put(StarRating.index, StarRating);
        put(BackendMode.index, BackendMode);
        put(RemoteConfig.index, RemoteConfig);
        put(TestDummy.index, TestDummy);
        put(DeviceId.index, DeviceId);
        put(Requests.index, Requests);
        put(Logs.index, Logs);
    }};

    protected static CoreFeature byIndex(int index) {
        return featureMap.get(index);
    }
}
