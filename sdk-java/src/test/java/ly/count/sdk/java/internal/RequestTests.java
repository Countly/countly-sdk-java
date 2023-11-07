package ly.count.sdk.java.internal;

import java.net.URL;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.powermock.reflect.Whitebox;

@RunWith(JUnit4.class)
public class RequestTests {
    private final String urlString = "http://www.google.com";
    private URL url;

    @Before
    public void setupEveryTest() throws Exception {
        url = new URL(urlString);
    }

    @Test
    public void request_constructorString() throws Exception {
        String paramVals = "a=1&b=2";
        Params params = new Params(paramVals);

        Request request = Whitebox.invokeConstructor(Request.class, paramVals);
        Params requestParams = request.params;
        Assert.assertEquals(params.toString(), requestParams.toString());
    }

    @Test
    public void request_constructorObjectsNull() throws Exception {
        String[] paramsVals = new String[] { "asd", "123" };
        Object[] vals = new Object[] { new Object[] { paramsVals[0], paramsVals[1] } };
        Request request = Whitebox.invokeConstructor(Request.class, vals);
        Assert.assertEquals(paramsVals[0] + "=" + paramsVals[1], request.params.toString());
    }

    @Test
    public void request_constructorObjects() throws Exception {
        String[] paramsParts = new String[] { "abc", "123", "qwe", "456" };
        String paramVals = paramsParts[0] + "=" + paramsParts[1] + "&" + paramsParts[2] + "=" + paramsParts[3];
        Params params = new Params(paramVals);

        Request request = Whitebox.invokeConstructor(Request.class, paramsParts[0], paramsParts[1], paramsParts[2], paramsParts[3]);
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
        Request request = Whitebox.invokeConstructor(Request.class, paramVals);

        String manualSerialization = paramVals + Whitebox.<String>getInternalState(Request.class, "EOR");
        String serializationRes = new String(request.store(null));
        Assert.assertEquals(manualSerialization, serializationRes);
    }

    @Test
    public void request_loadSimple() throws Exception {
        String paramVals = "a=1&b=2";
        Request request = Whitebox.invokeConstructor(Request.class, paramVals);

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
    public void isGettable_ParamsEmptyUnderLimit() throws Exception {
        Request request = Whitebox.invokeConstructor(Request.class, "");
        Assert.assertTrue(request.isGettable(url, 0));
    }

    @Test
    public void isGettable_ParamsFilledAboveLimitLarge() throws Exception {
        StringBuilder sbParams = new StringBuilder();

        for (int a = 0; a < 1000; a++) {
            if (a != 0) sbParams.append('&');
            sbParams.append("qq").append(a);
            sbParams.append('=').append(a);
        }

        Request request = Whitebox.invokeConstructor(Request.class, sbParams.toString());

        Assert.assertFalse(request.isGettable(url, 0));
    }
}