package ly.count.sdk.java.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.*;

import ly.count.sdk.java.User;
import ly.count.sdk.java.UserEditor;

/**
 * Class for user profile data access & manipulation
 */

public class UserImpl extends User implements Storable {
    // private static final Log.Module L = Log.module("UserImpl");

    private Log L = null;

    String id, name, username, email, org, phone, picturePath, locale, country, city, location;
    byte[] picture;
    Gender gender;
    Integer birthyear;
    Map<String, Object> custom;
    CtxCore ctx;

    public UserImpl(CtxCore ctx) {
        this.L = ctx.getLogger();
        this.ctx = ctx;
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
    public byte[] store(Log L) {
        ByteArrayOutputStream bytes = null;
        ObjectOutputStream stream = null;
        try {
            bytes = new ByteArrayOutputStream();
            stream = new ObjectOutputStream(bytes);
            stream.writeObject(name);
            stream.writeObject(username);
            stream.writeObject(email);
            stream.writeObject(org);
            stream.writeObject(phone);
            stream.writeInt(picture == null ? 0 : picture.length);
            if (picture != null) {
                stream.write(picture);
            }
            stream.writeObject(picturePath);
            stream.writeObject(gender == null ? null : gender.toString());
            stream.writeInt(birthyear == null ? -1 : birthyear);
            stream.writeObject(locale);
            stream.writeObject(country);
            stream.writeObject(city);
            stream.writeObject(location);
            stream.writeObject(null);//this is for the removed "cohorts" functionality. just to keep the correct order. Throw away in the future
            stream.writeObject(custom);
            stream.close();
            return bytes.toByteArray();
        } catch (IOException e) {
            if (L != null) {
                L.e("[UserImpl Cannot serialize session" + e);
            }
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[UserImpl Cannot happen" + e);
                    }
                }
            }
            if (bytes != null) {
                try {
                    bytes.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[UserImpl Cannot happen" + e);
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public boolean restore(byte[] data, Log L) {
        ByteArrayInputStream bytes = null;
        ObjectInputStream stream = null;

        try {
            bytes = new ByteArrayInputStream(data);
            stream = new ObjectInputStream(bytes);
            name = (String) stream.readObject();
            username = (String) stream.readObject();
            email = (String) stream.readObject();
            org = (String) stream.readObject();
            phone = (String) stream.readObject();

            int picLength = stream.readInt();
            if (picLength != 0) {
                picture = new byte[picLength];
                stream.readFully(picture);
            }
            picturePath = (String) stream.readObject();

            String g = (String) stream.readObject();
            if (g != null) {
                gender = Gender.fromString(g);
            }

            int y = stream.readInt();
            if (y != -1) {
                birthyear = y;
            }
            locale = (String) stream.readObject();
            country = (String) stream.readObject();
            city = (String) stream.readObject();
            location = (String) stream.readObject();

            Set<String> throwawayCohorts = (Set<String>) stream.readObject();//this is for keeping backwards compatibility. Throw away in the future

            custom = (Map<String, Object>) stream.readObject();
            if (custom == null) {
                custom = new HashMap<>();
            }

            return true;
        } catch (IOException | ClassNotFoundException e) {
            if (L != null) {
                L.e("[UserImpl Cannot deserialize session" + e);
            }
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[UserImpl Cannot happen" + e);
                    }
                }
            }
            if (bytes != null) {
                try {
                    bytes.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[UserImpl Cannot happen" + e);
                    }
                }
            }
        }

        return false;
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
            ", ctx=" + ctx +
            '}';
    }

    @Override
    public Long storageId() {
        return 0L;
    }

    @Override
    public String storagePrefix() {
        return "user";
    }

    @Override
    public void setId(Long id) {
        this.id = id.toString();
    }
}
