package ly.count.sdk.java.internal;

public class RCData {
    public Object value;
    public boolean isCurrentUsersData;

    public RCData(Object givenValue, boolean givenUserState) {
        this.value = givenValue;
        this.isCurrentUsersData = givenUserState;
    }
}
