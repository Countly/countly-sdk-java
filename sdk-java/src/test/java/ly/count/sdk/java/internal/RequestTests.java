package ly.count.sdk.java.internal;

import java.lang.reflect.Field;
import java.net.URL;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RequestTests {
    private static final String urlString = "http://www.google.com";
    private URL url;

    @Before
    public void setupEveryTest() throws Exception {
        url = new URL(urlString);
    }

    private static String getEOR() throws Exception {
        Field field = Request.class.getDeclaredField("EOR");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    @Test
    public void request_constructorString() {
        String paramVals = "a=1&b=2";
        Params params = new Params(paramVals);

        Request request = new Request(paramVals);
        Params requestParams = request.params;
        Assert.assertEquals(params.toString(), requestParams.toString());
    }

    @Test
    public void request_constructorObjectsNull() {
        String[] paramsVals = new String[] { "asd", "123" };
        Request request = new Request((Object[]) new Object[] { paramsVals[0], paramsVals[1] });
        Assert.assertEquals(paramsVals[0] + "=" + paramsVals[1], request.params.toString());
    }

    @Test
    public void request_constructorObjects() {
        String[] paramsParts = new String[] { "abc", "123", "qwe", "456" };
        String paramVals = paramsParts[0] + "=" + paramsParts[1] + "&" + paramsParts[2] + "=" + paramsParts[3];
        Params params = new Params(paramVals);

        Request request = new Request(paramsParts[0], paramsParts[1], paramsParts[2], paramsParts[3]);
        Params requestParams = request.params;
        Assert.assertEquals(params.toString(), requestParams.toString());
    }

    @Test
    public void request_build() {
        String[] paramsParts = new String[] { "abc", "123", "qwe", "456" };
        String paramVals = paramsParts[0] + "=" + paramsParts[1] + "&" + paramsParts[2] + "=" + paramsParts[3];
        Params params = new Params(paramVals);

        Request request = Request.build(paramsParts[0], paramsParts[1], paramsParts[2], paramsParts[3]);
        Params requestParams = request.params;
        Assert.assertEquals(params.toString(), requestParams.toString());
    }

    @Test
    public void request_serialize() throws Exception {
        String paramVals = "a=1&b=2";
        Request request = new Request(paramVals);

        String manualSerialization = paramVals + getEOR();
        String serializationRes = new String(request.store(null));
        Assert.assertEquals(manualSerialization, serializationRes);
    }

    @Test
    public void request_loadSimple() {
        String paramVals = "a=1&b=2";
        Request request = new Request(paramVals);

        byte[] serializationRes = request.store(null);
        Request requestNew = new Request();

        Assert.assertTrue(requestNew.restore(serializationRes, null));
        Assert.assertEquals(paramVals, requestNew.params.toString());
    }

    @Test
    public void request_loadEmpty() {
        Assert.assertFalse(new Request().restore("".getBytes(), null));
        Assert.assertFalse(new Request().restore("a=1&b=2".getBytes(), null));
    }

    @Test(expected = NullPointerException.class)
    public void request_loadNull() {
        new Request().restore(null, null);
    }

    @Test
    public void isGettable_ParamsEmptyUnderLimit() {
        Request request = new Request("");
        Assert.assertTrue(request.isGettable(url, 0));
    }

    @Test
    public void isGettable_ParamsFilledAboveLimitLarge() {
        StringBuilder sbParams = new StringBuilder();

        for (int a = 0; a < 1000; a++) {
            if (a != 0) {
                sbParams.append('&');
            }
            sbParams.append("qq").append(a);
            sbParams.append('=').append(a);
        }

        Request request = new Request(sbParams.toString());

        Assert.assertFalse(request.isGettable(url, 0));
    }
}
