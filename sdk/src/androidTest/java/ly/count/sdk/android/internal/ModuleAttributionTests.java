package ly.count.sdk.android.internal;

import android.content.BroadcastReceiver;
import android.content.Intent;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.powermock.reflect.Whitebox;

import ly.count.sdk.ConfigCore;
import ly.count.sdk.android.Config;
import ly.count.sdk.internal.Request;
import ly.count.sdk.internal.Tasks;

import static android.support.test.InstrumentationRegistry.getContext;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.times;

@RunWith(MockitoJUnitRunner.class)
public class ModuleAttributionTests extends BaseTestsAndroid {

    private static final String TEST_CID = "cb14e5f33b528334715f1809e4572842c74686df";
    private static final String TEST_UID = "ecf125107e4e27e6bcaacb3ae10ddba66459e6ae";
    private static final String TEST_REFERRER = "countly_cid%3D" + TEST_CID + "%26countly_cuid%3D" + TEST_UID;
    private static final String INVALID_REFERRER = "some=param";
    private ModuleAttribution moduleAttribution = null;
    private ModuleDeviceId moduleDeviceId = null;

    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected Config defaultConfig() throws Exception {
        return super.defaultConfig().enableFeatures(Config.Feature.Attribution);
    }

    @Test
    public void checkOpenUDID() throws Exception {
        BaseTestsAndroid.AdvIdInfo.deviceId = "123adv";
        doReturn(Boolean.TRUE).when(utils)._reflectiveClassExists(ModuleDeviceId.ADVERTISING_ID_CLIENT_CLASS_NAME);
        doReturn(new BaseTestsAndroid.AdvIdInfo()).when(utils)._reflectiveCallStrict(eq(ModuleDeviceId.ADVERTISING_ID_CLIENT_CLASS_NAME), ArgumentMatchers.isNull(), eq("getAdvertisingIdInfo"), eq(android.content.Context.class), isA(android.content.Context.class));

        setUpApplication(defaultConfig().setDeviceIdStrategy(Config.DeviceIdStrategy.ANDROID_ID));
        moduleDeviceId = module(ModuleDeviceId.class, false);

        Tasks tasks = Utils.reflectiveGetField(moduleDeviceId, "tasks");
        Whitebox.invokeMethod(tasks, "await");

        ConfigCore.DID did = config.getDeviceId();
        ConfigCore.DID aid = config.getDeviceId(Config.DeviceIdRealm.ADVERTISING_ID.getIndex());
        Assert.assertNotNull(did);
        Assert.assertNotNull(did.id);
        Assert.assertNotNull(aid);
        Assert.assertNotNull(aid.id);
        Assert.assertFalse(aid.id.equals(did.id));
        Assert.assertEquals(did.strategy, Config.DeviceIdStrategy.ANDROID_ID.getIndex());
        Assert.assertEquals(aid.strategy, Config.DeviceIdStrategy.ADVERTISING_ID.getIndex());
        Mockito.verify(dummy, times(1)).onDeviceId(isA(ctx.getClass()), eq(did), isNull(ConfigCore.DID.class));
        Mockito.verify(dummy, times(1)).onDeviceId(isA(ctx.getClass()), eq(aid), isNull(ConfigCore.DID.class));
    }

    @Test
    public void checkAdvertisingId() throws Exception {
        BaseTestsAndroid.AdvIdInfo.deviceId = "123adv";
        doReturn(Boolean.TRUE).when(utils)._reflectiveClassExists(ModuleDeviceId.ADVERTISING_ID_CLIENT_CLASS_NAME);
        doReturn(new BaseTestsAndroid.AdvIdInfo()).when(utils)._reflectiveCallStrict(eq(ModuleDeviceId.ADVERTISING_ID_CLIENT_CLASS_NAME), ArgumentMatchers.isNull(), eq("getAdvertisingIdInfo"), eq(android.content.Context.class), isA(android.content.Context.class));

        setUpApplication(defaultConfig().setDeviceIdStrategy(Config.DeviceIdStrategy.ADVERTISING_ID));
        moduleDeviceId = module(ModuleDeviceId.class, false);

        Tasks tasks = Utils.reflectiveGetField(moduleDeviceId, "tasks");
        Whitebox.invokeMethod(tasks, "await");

        ConfigCore.DID did = config.getDeviceId();
        ConfigCore.DID aid = config.getDeviceId(Config.DeviceIdRealm.ADVERTISING_ID.getIndex());
        Assert.assertNotNull(did);
        Assert.assertNotNull(did.id);
        Assert.assertNotNull(aid);
        Assert.assertNotNull(aid.id);
        Assert.assertTrue(aid.id.equals(did.id));
        Assert.assertEquals(did.strategy, Config.DeviceIdStrategy.ADVERTISING_ID.getIndex());
        Assert.assertEquals(aid.strategy, Config.DeviceIdStrategy.ADVERTISING_ID.getIndex());
        Mockito.verify(dummy, times(1)).onDeviceId(isA(ctx.getClass()), eq(did), isNull(ConfigCore.DID.class));
        Mockito.verify(dummy, times(1)).onDeviceId(isA(ctx.getClass()), eq(aid), isNull(ConfigCore.DID.class));
    }

    @Test
    public void requestNoAdId() throws Exception {
        BaseTestsAndroid.AdvIdInfo.deviceId = "123adv";
        doReturn(Boolean.FALSE).when(utils)._reflectiveClassExists(ModuleDeviceId.ADVERTISING_ID_CLIENT_CLASS_NAME);

        //test mode is disabled so that the exception, from failing to get the attribution id, is not thrown
        setUpApplication(defaultConfig().setDeviceIdStrategy(Config.DeviceIdStrategy.ANDROID_ID).disableTestMode());
        moduleDeviceId = module(ModuleDeviceId.class, false);
        moduleAttribution = module(ModuleAttribution.class, false);

        Tasks tasks = Utils.reflectiveGetField(moduleDeviceId, "tasks");
        Whitebox.invokeMethod(tasks, "await");

        Thread.sleep(3000);
        Boolean requestRes = moduleAttribution.onRequest(new Request().own(ModuleAttribution.class));
        Assert.assertFalse(requestRes);
    }

    @Test
    public void requestAdId() throws Exception {
        BaseTestsAndroid.AdvIdInfo.deviceId = "123adv";
        doReturn(Boolean.TRUE).when(utils)._reflectiveClassExists(ModuleDeviceId.ADVERTISING_ID_CLIENT_CLASS_NAME);
        doReturn(new BaseTestsAndroid.AdvIdInfo()).when(utils)._reflectiveCallStrict(eq(ModuleDeviceId.ADVERTISING_ID_CLIENT_CLASS_NAME), ArgumentMatchers.isNull(), eq("getAdvertisingIdInfo"), eq(android.content.Context.class), isA(android.content.Context.class));

        setUpApplication(defaultConfig().setDeviceIdStrategy(Config.DeviceIdStrategy.ANDROID_ID));
        moduleDeviceId = module(ModuleDeviceId.class, false);
        moduleAttribution = module(ModuleAttribution.class, false);

        Request request = new Request().own(ModuleAttribution.class);
        Assert.assertNull(moduleAttribution.onRequest(request));
        Assert.assertFalse(request.params.toString().contains(ModuleAttribution.CLY_AID));

        Tasks tasks = Utils.reflectiveGetField(moduleDeviceId, "tasks");
        Whitebox.invokeMethod(tasks, "await");

        Assert.assertTrue(moduleAttribution.onRequest(request));
        Assert.assertTrue(request.params.toString().contains(ModuleAttribution.CLY_AID + "=" + BaseTestsAndroid.AdvIdInfo.deviceId));
    }

    @Test
    public void receiver_action_bad() throws Exception {
        setUpApplication(null);
        //Core.deinit();

        ModuleAttribution.AttributionReferrerReceiver receiver = spy(new ModuleAttribution.AttributionReferrerReceiver());
        Intent intent = new Intent("bad action");
        receiver.onReceive(getContext(), intent);
        verify(receiver, never()).extractReferrer(isA(android.content.Context.class), isA(BroadcastReceiver.PendingResult[].class), anyString());
    }

    @Test
    public void receiver_action_ok_nourl() throws Exception {
        setUpApplication(null);
        //Core.deinit();

        ModuleAttribution.AttributionReferrerReceiver receiver = spy(new ModuleAttribution.AttributionReferrerReceiver());
        Intent intent = new Intent(ModuleAttribution.ACTION);
        receiver.onReceive(getContext(), intent);
        verify(receiver, never()).extractReferrer(isA(android.content.Context.class), isA(BroadcastReceiver.PendingResult[].class), anyString());
    }

    @Test
    public void receiver_action_ok_url_ok_nocly() throws Exception {
        setUpApplication(null);

        ModuleAttribution.AttributionReferrerReceiver receiver = spy(new ModuleAttribution.AttributionReferrerReceiver());
        Intent intent = new Intent(ModuleAttribution.ACTION);
        intent.putExtra(ModuleAttribution.EXTRA, INVALID_REFERRER);
        receiver.onReceive(getContext(), intent);
        verify(receiver, times(1)).extractReferrer(isA(android.content.Context.class), any(BroadcastReceiver.PendingResult[].class), eq(Utils.urldecode(INVALID_REFERRER)));
        verify(receiver, never()).recordRequest(anyString(), anyString());
    }

    @Test
    public void receiver_action_ok_url_cly_ok() throws Exception {
        setUpApplication(null);

        ModuleAttribution.AttributionReferrerReceiver receiver = spy(new ModuleAttribution.AttributionReferrerReceiver());
        Intent intent = new Intent(ModuleAttribution.ACTION);
        intent.putExtra(ModuleAttribution.EXTRA, TEST_REFERRER);
        receiver.onReceive(getContext(), intent);
        verify(receiver, times(1)).extractReferrer(isA(android.content.Context.class), any(BroadcastReceiver.PendingResult[].class), eq(Utils.urldecode(TEST_REFERRER)));
    }

    @Test
    public void receiver_action_ok_url_cly_ok_request() throws Exception {
        setUpApplication(null);

        ModuleAttribution.AttributionReferrerReceiver receiver = spy(new ModuleAttribution.AttributionReferrerReceiver());
        Intent intent = new Intent(ModuleAttribution.ACTION);
        intent.putExtra(ModuleAttribution.EXTRA, TEST_REFERRER);
        receiver.onReceive(getContext(), intent);
        verify(receiver, times(1)).extractReferrer(isA(android.content.Context.class), any(BroadcastReceiver.PendingResult[].class), eq(Utils.urldecode(TEST_REFERRER)));
        verify(receiver, times(1)).recordRequest(eq(TEST_CID), eq(TEST_UID));
    }
}
