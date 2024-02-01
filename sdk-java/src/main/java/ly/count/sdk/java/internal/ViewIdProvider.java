package ly.count.sdk.java.internal;

import javax.annotation.Nonnull;

public interface ViewIdProvider {
    @Nonnull String getCurrentViewId();

    @Nonnull String getPreviousViewId();
}
