package ly.count.sdk.java.backend.module;

import ly.count.sdk.java.backend.controller.RequestController;
import ly.count.sdk.java.backend.helper.ClyLogger;

public abstract class BaseModule {

    protected final ClyLogger logger;
    protected final RequestController requestController;

    protected BaseModule(RequestController requestController, ClyLogger logger){
        this.logger = logger;
        this.requestController = requestController;
    }
}
