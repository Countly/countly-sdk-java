package ly.count.sdk.java.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class UserEditorTests {

    private final static String imgFileName = "test.jpeg";
    private final static String imgFileWebUrl = TestUtils.SERVER_URL + "/" + imgFileName;

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    /**
     * "setPicturePath" with web url
     * A test web url is given to the method, session manually began and end to create a request
     * 'picturePath' in user should be set and, 'picture' parameter in the 'user_details' should be same,
     * 'picturePath' parameter in the request should not exist
     */
    @Test
    public void setPicturePath_webUrl() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Countly.session().begin();
        //set profile picture url and commit it
        Countly.instance().user().edit().setPicturePath(imgFileWebUrl).commit();
        validatePictureAndPath(imgFileWebUrl, null);

        Countly.session().end();
        validatePictureInRQ("{\"picture\":\"" + imgFileWebUrl + "\"}", null);
    }

    /**
     * "setPicturePath" with local path
     * A test local path is given to the method, session manually began and end to create a request
     * 'picturePath' in user should be set and, 'picturePath' parameter in the user_details should be null,
     * and the request should be equal to file name
     */
    @Test
    public void setPicturePath_localPath() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Countly.session().begin();
        File imgFile = TestUtils.createFile(imgFileName);
        //set profile picture url and commit it
        Countly.instance().user().edit().setPicturePath(imgFile.getAbsolutePath()).commit();
        validatePictureAndPath(imgFile.getAbsolutePath(), null);

        Countly.session().end();
        validatePictureInRQ("{}", imgFile.getAbsolutePath());
    }

    /**
     * "setPicturePath" with null,
     * null value is given to the method, session manually began and end to create a request
     * 'picturePath' in user should be set to null, 'picturePath' parameter in request should not exist
     * and 'picture' parameter in the 'user_details' should be null
     */
    @Test
    public void setPicturePath_null() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Countly.session().begin();
        //set profile picture url and commit it
        Countly.instance().user().edit().setPicturePath(null).commit();
        validatePictureAndPath(null, null);

        Countly.session().end();
        validatePictureInRQ("{\"picture\":null}", null);
    }

    /**
     * "setPicturePath" with garbage local path/web url,
     * garbage value is given to the method, session manually began and end to create a request
     * 'picturePath' in user should not be set, 'picturePath' parameter in request should not exist
     * and 'picturePath' parameter in the 'user_details' should not exist
     */
    @Test
    public void setPicturePath_garbage() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Countly.session().begin();
        //set profile picture url and commit it
        Countly.instance().user().edit().setPicturePath("garbage_thing/.txt").commit();
        validatePictureAndPath(null, null);

        Countly.session().end();
        validatePictureInRQ("{}", null);
    }

    /**
     * "setPicture" with binary data,
     * Binary data is given to the method, session manually began and end to create a request
     * 'picturePath' in user should be null and picture should be defined binary data,
     * 'picturePath' parameter in the user_details should be null and the request 'picturePath'
     * parameter should equal to '[CLY]_USER_PROFILE_PICTURE'
     */
    @Test
    public void setPicture_binaryData() throws IOException {
        Countly.instance().init(TestUtils.getBaseConfig());
        Countly.session().begin();
        File imgFile = TestUtils.createFile(imgFileName);
        //set profile picture url and commit it
        byte[] imgData = Files.readAllBytes(imgFile.toPath());
        Countly.instance().user().edit().setPicture(imgData).commit();
        validatePictureAndPath(null, imgData);

        Countly.session().end();
        validatePictureInRQ("{}", UserEditorImpl.PICTURE_IN_USER_PROFILE);
    }

    /**
     * "setPicture" with null,
     * null value is given to the method, session manually began and end to create a request
     * 'picture' in user should be set to null, 'picturePath' parameter in request should not exist
     * and 'picturePath' parameter in the 'user_details' should be null
     */
    @Test
    public void setPicture_null() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Countly.session().begin();
        //set profile picture url and commit it
        Countly.instance().user().edit().setPicture(null).commit();
        validatePictureAndPath(null, null);

        Countly.session().end();
        validatePictureInRQ("{\"picture\":null}", null);
    }

    private void validatePictureAndPath(String picturePath, byte[] picture) {
        Assert.assertEquals(picturePath, Countly.instance().user().picturePath());
        Assert.assertEquals(picture, Countly.instance().user().picture());
    }

    private void validatePictureInRQ(String expectedUserDetails, String expectedPicturePath) {
        Map<String, String>[] requestsInQ = TestUtils.getCurrentRQ();
        Assert.assertEquals(1, requestsInQ.length);
        if (expectedPicturePath == null) {
            Assert.assertFalse(requestsInQ[0].containsKey("picturePath"));
        }
        Assert.assertEquals(expectedPicturePath, requestsInQ[0].get("picturePath"));
        Assert.assertEquals(expectedUserDetails, requestsInQ[0].get("user_details"));
    }
}
