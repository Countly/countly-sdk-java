package ly.count.sdk.java.internal;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import ly.count.sdk.java.User;
import ly.count.sdk.java.UserEditor;

/**
 * Class for user profile data access & manipulation
 */

public class UserImpl extends User {
    private Log L = null;

    String id;
    String name;
    String username;
    String email;
    String org;
    String phone;
    String picturePath;
    String locale;
    String country;
    String city;
    String location;
    byte[] picture;
    Gender gender;
    Integer birthyear;
    Map<String, Object> custom;

    public UserImpl(InternalConfig config) {
        this.L = config.getLogger();
        this.custom = new HashMap<>();
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String username() {
        return username;
    }

    public String email() {
        return email;
    }

    public String org() {
        return org;
    }

    public String phone() {
        return phone;
    }

    public byte[] picture() {
        return picture;
    }

    public String picturePath() {
        return picturePath;
    }

    public Gender gender() {
        return gender;
    }

    public String locale() {
        return locale;
    }

    public Integer birthyear() {
        return birthyear;
    }

    public String country() {
        return country;
    }

    public String city() {
        return city;
    }

    public String location() {
        return location;
    }

    public Map<String, Object> custom() {
        return custom;
    }

    public UserEditor edit() {
        return new UserEditorImpl(this, L);
    }

    @Override
    public String toString() {
        return "UserImpl{" +
            "id='" + id + '\'' +
            ", name='" + name + '\'' +
            ", username='" + username + '\'' +
            " + email='" + email + '\'' +
            ", org='" + org + '\'' +
            ", phone='" + phone + '\'' +
            ", picturePath='" + picturePath + '\'' +
            ", locale='" + locale + '\'' +
            ", country='" + country + '\'' +
            ", city='" + city + '\'' +
            ", location='" + location + '\'' +
            ", picture=" + Arrays.toString(picture) +
            ", gender=" + gender +
            ", birthyear=" + birthyear +
            ", custom=" + custom +
            '}';
    }
}
