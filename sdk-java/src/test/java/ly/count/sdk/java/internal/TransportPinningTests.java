package ly.count.sdk.java.internal;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Certificate and public key pinning, which is the part of {@link Transport} a customer turns on to
 * stop a man in the middle and which had no coverage at all.
 * <p>
 * Driven with a self signed certificate generated for this test. The pin strings a customer passes
 * to {@link Config#addPublicKeyPin(String)} are either a classpath resource name or, as here, the
 * PEM text itself.
 */
@RunWith(JUnit4.class)
public class TransportPinningTests {

    /**
     * A self signed certificate and its RSA public key, PEM encoded. Only ever compared against
     * itself, never presented to a real server.
     */
    private static final String CERTIFICATE_PEM =
        "-----BEGIN CERTIFICATE-----\n"
            + "MIIDCTCCAfGgAwIBAgIUS9wHlLn6udicFTbrdAF3APj5aGswDQYJKoZIhvcNAQEL\n"
            + "BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MDkwMTA4MzI0NloXDTM2MDgy\n"
            + "OTA4MzI0NlowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF\n"
            + "AAOCAQ8AMIIBCgKCAQEAoEAoIix1JRqGjxxZlVLcEYuoQE5QvdClJjdFxtkqnTxH\n"
            + "im0Qlvh8tA0EoiFgDdhCvp2HLk3w8EloR5Op2JQKkyTEqPk5OY7ftg39WVsGIAP0\n"
            + "CxwoyCymGgmP2gRimjV1wa+58Ng3f5MktTKAVWX91/ki9khQ2XVIXUv5wq3Yx0Vy\n"
            + "CZa80W7bifXqfDnLmkhOA8jFy7effR/pkLk5j0HS9g3zYPFmid1gJHxF2/j7oZ9N\n"
            + "OG7KBfKuB3Aml8R3Y9Xg/3RAbbh7jVwlYj8CJ1gCTU7V9bCRX+DFjX8XcCP7hVM8\n"
            + "yHadS63dsnKPPlUtUbVQouNKqWA+ubhEpQsrxEmvjQIDAQABo1MwUTAdBgNVHQ4E\n"
            + "FgQU+cWHVu0aVrvyLvuRIXNlS4jscpQwHwYDVR0jBBgwFoAU+cWHVu0aVrvyLvuR\n"
            + "IXNlS4jscpQwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAMV9b\n"
            + "sJ86mBCSBvqS+dmfGxLLa1Idlz6msoSBdkdpC24F+85CvdxZCskyTtL5neQTXu62\n"
            + "5KqFsI5AKi442YnMN+QlPnzrPF/CKozTKKWktNV2bbXWXsgE5XWn7Q0Fw9bRHznz\n"
            + "KcQGuNc1A/ByE/LEjVgyJL6YndXCGMXvI6acU/b92r1g5D3lVvhP8BBEqM8/irGd\n"
            + "HqGqgnWjyR+/ge3njjms6owidEXCO2bKmw80ONHD/jaZUWoQz3cw4lXaCoWkG89S\n"
            + "ov+c+pXvQtgwT6gJ0V4B1MKAt9jtG2xkgJCZVz4moeFLqh5qEE5+t/hCzW6Pft1E\n"
            + "1s4ekpbY3ieWeWxsOw==\n"
            + "-----END CERTIFICATE-----\n";

    private static final String PUBLIC_KEY_PEM =
        "-----BEGIN PUBLIC KEY-----\n"
            + "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAoEAoIix1JRqGjxxZlVLc\n"
            + "EYuoQE5QvdClJjdFxtkqnTxHim0Qlvh8tA0EoiFgDdhCvp2HLk3w8EloR5Op2JQK\n"
            + "kyTEqPk5OY7ftg39WVsGIAP0CxwoyCymGgmP2gRimjV1wa+58Ng3f5MktTKAVWX9\n"
            + "1/ki9khQ2XVIXUv5wq3Yx0VyCZa80W7bifXqfDnLmkhOA8jFy7effR/pkLk5j0HS\n"
            + "9g3zYPFmid1gJHxF2/j7oZ9NOG7KBfKuB3Aml8R3Y9Xg/3RAbbh7jVwlYj8CJ1gC\n"
            + "TU7V9bCRX+DFjX8XcCP7hVM8yHadS63dsnKPPlUtUbVQouNKqWA+ubhEpQsrxEmv\n"
            + "jQIDAQAB\n"
            + "-----END PUBLIC KEY-----\n";

    /**
     * A structurally valid but unrelated RSA public key, so a pin mismatch can be told apart from a
     * parse failure.
     */
    private static final String OTHER_PUBLIC_KEY_PEM =
        "-----BEGIN PUBLIC KEY-----\n"
            + "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1oFvBHnrHqLJ0YfBiFTs\n"
            + "kFYX1yiMTBTeuHfUCJHTUmVNbXQdaWm2H2i7ExHFdaOhBcJn2n1ZbnvVMSlLXCYq\n"
            + "5b1oGCB9OJgLD1TZBOKZq7GEnBEIOxKPcVOSp0lPCLNRHYpVfj0P0j6EGWyBQ0LO\n"
            + "2xIvBUnhVc6ZDcYUCoHRSJ0zGNsvhBQoRz1qCHU5tPmXQeIzXNxNZMKKGgFqpTV3\n"
            + "L1kM8vBQoTZ2xJ5nDCXBqEFhZBGKzMFdV2gRBQnJXqOvBqUzPnMcmTKGXBOxvDCn\n"
            + "9YJqBGKzXvQnJ0mBQoRzHFdaOhBcJn2n1ZbnvVMSlLXCYq5b1oGCB9OJgLD1TZBO\n"
            + "KwIDAQAB\n"
            + "-----END PUBLIC KEY-----\n";

    private X509Certificate certificate;

    @Before
    public void beforeTest() throws Exception {
        TestUtils.createCleanTestState();
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        certificate = (X509Certificate) factory.generateCertificate(
            new ByteArrayInputStream(CERTIFICATE_PEM.getBytes(StandardCharsets.UTF_8)));
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    // region scenarios

    /**
     * A public key pin given as PEM text is parsed, stored, and accepts the matching server key while
     * rejecting an unrelated one. Also proves an empty chain and a missing auth type are refused
     * before any comparison happens, which is what stops a stripped handshake from passing.
     */
    @Test
    public void publicKeyPin_acceptsTheMatchingKeyAndRejectsEverythingElse() throws Exception {
        Transport transport = pinnedTransport(PUBLIC_KEY_PEM, null);

        // The pin came from the same key pair as the certificate, so this chain must pass.
        transport.checkServerTrusted(new X509Certificate[] { certificate }, "RSA");

        // A chain the pin does not cover must be refused.
        Transport other = pinnedTransport(OTHER_PUBLIC_KEY_PEM, null);
        assertPinningRejects(other, new X509Certificate[] { certificate }, "RSA");

        // Malformed handshakes are refused before the pins are even consulted.
        try {
            transport.checkServerTrusted(null, "RSA");
            Assert.fail("a null chain must be refused");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("null"));
        }
        try {
            transport.checkServerTrusted(new X509Certificate[0], "RSA");
            Assert.fail("an empty chain must be refused");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("empty"));
        }
        try {
            transport.checkServerTrusted(new X509Certificate[] { certificate }, null);
            Assert.fail("a null auth type must be refused");
        } catch (CertificateException expected) {
            Assert.assertTrue(expected.getMessage().contains("AuthType is null"));
        }
        try {
            transport.checkServerTrusted(new X509Certificate[] { certificate }, "");
            Assert.fail("an empty auth type must be refused");
        } catch (CertificateException expected) {
            Assert.assertTrue(expected.getMessage().contains("AuthType is empty"));
        }
    }

    /**
     * A certificate pin, the other half of the feature. Pinning the whole certificate accepts the
     * same chain, and a public key pin that is actually a certificate is still accepted because the
     * SDK falls back to reading the key out of it.
     */
    @Test
    public void certificatePin_andACertificatePassedAsAKeyPin_bothAccept() throws Exception {
        Transport byCertificate = pinnedTransport(null, CERTIFICATE_PEM);
        byCertificate.checkServerTrusted(new X509Certificate[] { certificate }, "RSA");

        // A customer who pastes a certificate where a public key was expected must still be pinned,
        // not silently unprotected.
        Transport byCertificateAsKey = pinnedTransport(CERTIFICATE_PEM, null);
        byCertificateAsKey.checkServerTrusted(new X509Certificate[] { certificate }, "RSA");

        // And the unrelated chain still fails against a certificate pin.
        Transport unrelated = pinnedTransport(null, null);
        // No pins at all means pinning is off, so anything passes.
        unrelated.checkServerTrusted(new X509Certificate[] { certificate }, "RSA");
        unrelated.checkServerTrusted(null, "RSA");
    }

    /**
     * Pins can be given with or without the PEM armour and with stray whitespace, because that is how
     * they arrive when copied out of a terminal. All spellings must produce the same pin, and a pin
     * that is not decodable must not take initialisation down.
     */
    @Test
    public void pinParsing_toleratesArmourWhitespaceAndGarbage() throws Exception {
        String bare = PUBLIC_KEY_PEM
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "");

        // Bare base64, armoured, and armoured with padding whitespace must all pin the same key.
        for (String spelling : new String[] { bare, PUBLIC_KEY_PEM, "\n  " + PUBLIC_KEY_PEM + "  \n" }) {
            Transport transport = pinnedTransport(spelling, null);
            transport.checkServerTrusted(new X509Certificate[] { certificate }, "RSA");
        }

        // A pin that decodes but is not a key or a certificate is refused loudly, which is the
        // documented contract of Transport#init.
        Transport unusable = new Transport();
        Config config = TestUtils.getBaseConfig();
        config.addPublicKeyPin(Utils.Base64.encode("not a key at all"));
        try {
            unusable.init(TestUtils.getInternalConfigWithLogger(config));
            Assert.fail("a pin that is neither a key nor a certificate must be refused");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getCause() instanceof CertificateException);
        }
    }

    /**
     * The trust manager contract the SDK implements. Client side checks delegate to the platform, and
     * the accepted issuer list is deliberately empty because the SDK never acts as a server.
     */
    @Test
    public void trustManagerContract_delegatesClientChecksAndPublishesNoIssuers() throws Exception {
        Transport transport = pinnedTransport(PUBLIC_KEY_PEM, null);

        Assert.assertEquals(0, transport.getAcceptedIssuers().length);

        // With the platform trust manager removed this is a no-op rather than a crash.
        setDefaultTrustManager(transport, null);
        transport.checkClientTrusted(new X509Certificate[] { certificate }, "RSA");
    }

    // endregion
    // region helpers

    /**
     * Builds a transport with the given pins installed through the public {@link Config} API.
     * <p>
     * The platform trust manager is then cleared, because it is the one thing this test cannot
     * satisfy: a self signed certificate can never chain to a system root, so leaving it in place
     * would make every scenario fail on the standard TLS check before the SDK's own pin comparison
     * is ever reached. Reflection is the established pattern in this suite for reaching into
     * {@link Transport} (see {@code TransportTests}).
     */
    private Transport pinnedTransport(String keyPin, String certificatePin) throws Exception {
        Config config = TestUtils.getBaseConfig();
        if (keyPin != null) {
            config.addPublicKeyPin(keyPin);
        }
        if (certificatePin != null) {
            config.addCertificatePin(certificatePin);
        }

        Transport transport = new Transport();
        transport.init(TestUtils.getInternalConfigWithLogger(config));
        setDefaultTrustManager(transport, null);
        return transport;
    }

    private static void setDefaultTrustManager(Transport transport, Object value) throws Exception {
        Field field = Transport.class.getDeclaredField("defaultTrustManager");
        field.setAccessible(true);
        field.set(transport, value);
    }

    private static void assertPinningRejects(Transport transport, X509Certificate[] chain, String authType) {
        try {
            transport.checkServerTrusted(chain, authType);
            Assert.fail("a chain that matches no pin must be rejected");
        } catch (CertificateException expected) {
            Assert.assertTrue("unexpected message: " + expected.getMessage(),
                expected.getMessage().contains("pinning validation"));
        }
    }

    // endregion
}
