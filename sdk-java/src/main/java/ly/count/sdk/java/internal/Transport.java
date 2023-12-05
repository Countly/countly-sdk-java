package ly.count.sdk.java.internal;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import ly.count.sdk.java.PredefinedUserPropertyKeys;
import org.json.JSONObject;

/**
 * Class managing all networking operations.
 * Contract:
 * <ul>
 *     <li>Instantiated once.</li>
 *     <li>Doesn't have any queues, sends one request at a time (guaranteed to have maximum one {@link #send(Request)} unresolved call at a time).</li>
 *     <li>Returns a {@link Future} which resolves to either success or a failure.</li>
 *     <li>Doesn't do any storage or configuration-related operations, doesn't call modules, etc.</li>
 * </ul>
 */

//class Network extends ModuleBase { - may be

public class Transport implements X509TrustManager {
    private Log L = null;
    private static final String PARAMETER_TAMPERING_DIGEST = "SHA-256";
    private static final String CHECKSUM = "checksum256";
    private InternalConfig config;

    private SSLContext sslContext;          // ssl context to use if pinning is enabled
    private List<byte[]> keyPins = null;    // list of parsed key pins
    private List<byte[]> certPins = null;   // list of parsed cert pins
    private X509TrustManager defaultTrustManager = null;    // default TrustManager to call along with Network one

    public Transport() {
    }

    /**
     * @param config configuration to use
     * @throws IllegalArgumentException if certificate exception happens
     * @see ModuleBase#init(InternalConfig)
     */
    public void init(InternalConfig config) throws IllegalArgumentException {
        // ssl config (cert & public key pinning)
        // sha1 signing
        // 301/302 handling, probably configurable (like allowFollowingRedirects) and with response
        //      validation because public WiFi APs return 30X to login page which returns 200
        // exponential back off - not sure where to have it: either some
        //      sleeping delay in Future thread or in Service thread, probably better having it here
        // network status callbacks - may be
        // APM stuff - later

        L = config.getLogger();
        L.i("[network] Server: " + config.getServerURL());
        this.config = config;

        try {
            setPins(config.getPublicKeyPins(), config.getCertificatePins());
        } catch (CertificateException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * For testing purposes
     */
    public HttpURLConnection openConnection(String url, String params, boolean usingGET) throws IOException {
        URL u;
        if (usingGET) {
            u = new URL(url + params);
        } else {
            u = new URL(url);
        }

        HttpURLConnection connection = (HttpURLConnection) u.openConnection();

        connection.setUseCaches(false);
        connection.setDoInput(true);
        connection.setDoOutput(!usingGET);
        connection.setRequestMethod(usingGET ? "GET" : "POST");

        return connection;
    }

    /**
     * Open connection for particular request: choose GET or POST, choose multipart or urlencoded if POST,
     * set SSL context, calculate and add checksum, load and send user picture if needed.
     *
     * @param request request to send
     * @return connection, not {@link HttpURLConnection} yet
     * @throws IOException from {@link HttpURLConnection} in case of error
     */
    HttpURLConnection connection(final Request request) throws IOException {
        String endpoint = request.params.remove(Request.ENDPOINT);

        if (!request.params.has("device_id") && config.getDeviceId() != null) {
            //fallback if request does not have any device id
            request.params.add("device_id", config.getDeviceId().id);
        }

        if (endpoint == null) {
            endpoint = "/i?";
        }

        String path = config.getServerURL().toString() + endpoint;
        String picturePathValue = request.params.remove(PredefinedUserPropertyKeys.PICTURE_PATH);
        boolean usingGET = !config.isHTTPPostForced() && request.isGettable(config.getServerURL()) && Utils.isEmptyOrNull(picturePathValue);

        if (!usingGET && !Utils.isEmptyOrNull(picturePathValue)) {
            path = setProfilePicturePathRequestParams(path, request.params);
        }

        if (usingGET && config.getParameterTamperingProtectionSalt() != null) {
            request.params.add(CHECKSUM, Utils.digestHex(PARAMETER_TAMPERING_DIGEST, request.params + config.getParameterTamperingProtectionSalt(), L));
        }

        HttpURLConnection connection = openConnection(path, request.params.toString(), usingGET);
        connection.setConnectTimeout(1000 * config.getNetworkConnectionTimeout());
        connection.setReadTimeout(1000 * config.getNetworkReadTimeout());

        if (connection instanceof HttpsURLConnection && sslContext != null) {
            HttpsURLConnection https = (HttpsURLConnection) connection;
            https.setSSLSocketFactory(sslContext.getSocketFactory());
        }

        if (!usingGET) {
            OutputStream output = null;
            PrintWriter writer = null;
            try {
                L.d("[network] Picture path value " + picturePathValue);
                byte[] pictureByteData = picturePathValue == null ? null : getPictureDataFromGivenValue(picturePathValue);

                if (pictureByteData != null) {
                    String boundary = Long.toHexString(System.currentTimeMillis());

                    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                    output = connection.getOutputStream();
                    writer = new PrintWriter(new OutputStreamWriter(output, Utils.UTF8), true);

                    addMultipart(output, writer, boundary, "image/jpeg", "binaryFile", "image", pictureByteData);

                    StringBuilder salting = new StringBuilder();
                    Map<String, String> map = request.params.map();
                    for (String key : map.keySet()) {
                        String value = Utils.urldecode(map.get(key));
                        salting.append(key).append('=').append(value).append('&');
                        addMultipart(output, writer, boundary, "text/plain", key, value, null);
                    }

                    if (config.getParameterTamperingProtectionSalt() != null) {
                        addMultipart(output, writer, boundary, "text/plain", CHECKSUM, Utils.digestHex(PARAMETER_TAMPERING_DIGEST, salting.substring(0, salting.length() - 1) + config.getParameterTamperingProtectionSalt(), L), null);
                    }

                    writer.append(Utils.CRLF).append("--").append(boundary).append("--").append(Utils.CRLF).flush();
                } else {
                    //picture data is "null". If it was sent, we send "null" to server to clear the image there
                    //we send a normal request in HTTP POST
                    if (config.getParameterTamperingProtectionSalt() != null) {
                        request.params.add(CHECKSUM, Utils.digestHex(PARAMETER_TAMPERING_DIGEST, request.params.toString() + config.getParameterTamperingProtectionSalt(), L));
                    }
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                    output = connection.getOutputStream();
                    writer = new PrintWriter(new OutputStreamWriter(output, Utils.UTF8), true);

                    writer.write(request.params.toString());
                    writer.flush();
                }
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (output != null) {
                    try {
                        output.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        return connection;
    }

    void addMultipart(OutputStream output, PrintWriter writer, String boundary, String contentType, String name, String value, Object file) throws IOException {
        writer.append("--").append(boundary).append(Utils.CRLF);
        if (file != null) {
            writer.append("Content-Disposition: form-data; name=\"").append(name).append("\"; filename=\"").append(value).append("\"").append(Utils.CRLF);
            writer.append("Content-Type: ").append(contentType).append(Utils.CRLF);
            writer.append("Content-Transfer-Encoding: binary").append(Utils.CRLF);
            writer.append(Utils.CRLF).flush();
            output.write((byte[]) file);
            output.flush();
            writer.append(Utils.CRLF).flush();
        } else {
            writer.append("Content-Disposition: form-data; name=\"").append(name).append("\"").append(Utils.CRLF);
            writer.append("Content-Type: ").append(contentType).append("; charset=").append(Utils.UTF8).append(Utils.CRLF);
            writer.append(Utils.CRLF).append(value).append(Utils.CRLF).flush();
        }
    }

    /**
     * Returns valid picture information
     * If we have the bytes, give them
     * Otherwise load them from disk
     *
     * @param picture picture path or base64 encoded byte array
     * @return picture data
     */
    byte[] getPictureDataFromGivenValue(String picture) {
        byte[] data;
        //firstly, we assume it is a local path, and we try to read it from disk
        try {
            File file = new File(picture);
            if (!file.exists()) {
                return null;
            }
            data = Files.readAllBytes(file.toPath());
        } catch (Throwable t) {
            //if we can't read it from disk, we assume it is a base64 encoded byte array
            data = readBase64String(picture);
        }

        return data;
    }

    private byte[] readBase64String(String string) {
        try {
            return Base64.getDecoder().decode(string);
        } catch (IllegalArgumentException e) {
            L.w("[Transport] readBase64String, Error while reading base64 string " + e);
            return null;
        }
    }

    String response(HttpURLConnection connection) {
        BufferedReader reader = null;
        try {
            InputStream responseInputStream;

            try {
                //assume there will be no error
                responseInputStream = connection.getInputStream();
            } catch (Exception ex) {
                //in case of exception, assume there was a error in the request
                //and change streams
                responseInputStream = connection.getErrorStream();
            }

            reader = new BufferedReader(new InputStreamReader(responseInputStream));
            StringBuilder total = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                total.append(line).append('\n');
            }
            return total.toString();
        } catch (IOException e) {
            L.w("[network] Error while reading server response " + e);
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public Tasks.Task<Boolean> send(final Request request) {
        return new Tasks.Task<Boolean>(request.storageId()) {
            @Override
            public Boolean call() {
                return send();
            }

            public Boolean send() {
                L.i("[network] [send] Sending request: " + request);

                HttpURLConnection connection = null;
                try {
                    Class requestOwner = request.owner();
                    request.params.remove(Request.MODULE);

                    connection = connection(request);
                    connection.connect();

                    int code = connection.getResponseCode();

                    String response = response(connection);

                    try {
                        if (request.params.has(Params.PARAM_OLD_DEVICE_ID) || request.params.has("token_session")) {
                            if (config.getNetworkImportantRequestCooldown() > 0) {
                                Thread.sleep(config.getNetworkImportantRequestCooldown());
                            }
                        } else {
                            if (config.getNetworkRequestCooldown() > 0) {
                                Thread.sleep(config.getNetworkRequestCooldown());
                            }
                        }
                    } catch (InterruptedException ie) {
                        L.w("[network] Interrupted while waiting for did change request cooldown " + ie);
                    }

                    SDKCore.instance.onRequestCompleted(request, response, code, requestOwner);

                    return processResponse(code, response, request.storageId());
                } catch (IOException e) {
                    L.w("[network] Error while sending request " + request + " " + e);
                    return false;
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        };
    }

    Boolean processResponse(int code, String response, Long requestId) {
        L.i("[network] [processResponse] Code [" + code + "] response [" + response + "] for request[" + requestId + "]");

        JSONObject jsonObject = new JSONObject(response);
        if (code >= 200 && code < 300 && jsonObject.has("result")) {
            L.d("[network] Success");
            return true;
        } else {
            L.w("[network] Fail: code :" + code + ", result: " + response);
            return false;
        }
    }

    private static String trimPem(String pem) {
        pem = pem.trim();

        final String beginPK = "-----BEGIN PUBLIC KEY-----";
        if (pem.startsWith(beginPK)) {
            pem = pem.substring(pem.indexOf(beginPK) + beginPK.length());
        }

        final String beginCert = "-----BEGIN CERTIFICATE-----";
        if (pem.startsWith(beginCert)) {
            pem = pem.substring(pem.indexOf(beginCert) + beginCert.length());
        }

        if (pem.contains("-----END ")) {
            pem = pem.substring(0, pem.indexOf("-----END"));
        }
        String res = pem.replaceAll("\n", "");
        return res;
    }

    private void setPins(Set<String> keys, Set<String> certs) throws CertificateException {
        keyPins = new ArrayList<>();
        certPins = new ArrayList<>();

        if (keys != null) {
            for (String key : keys) {
                try {
                    byte[] data = Utils.readStream(Transport.class.getClassLoader().getResourceAsStream(key), L);
                    if (data != null) {
                        String string = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(data)).toString();
                        if (string.contains("--BEGIN")) {
                            data = Utils.Base64.decode(trimPem(string), L);
                        }
                    } else {
                        data = Utils.Base64.decode(trimPem(key), L);
                    }

                    try {
                        X509EncodedKeySpec spec = new X509EncodedKeySpec(data);
                        KeyFactory kf = KeyFactory.getInstance("RSA");
                        PublicKey k = kf.generatePublic(spec);

                        keyPins.add(k.getEncoded());
                    } catch (InvalidKeySpecException e) {
                        L.d("[network] Certificate in instead of public key it seems " + e);
                        CertificateFactory cf = CertificateFactory.getInstance("X.509");
                        Certificate cert = cf.generateCertificate(new ByteArrayInputStream(data));
                        keyPins.add(cert.getPublicKey().getEncoded());
                    }
                } catch (NoSuchAlgorithmException e) {
                    L.d("[network] Shouldn't happen " + key);
                }
            }
        }

        if (certs != null) {
            for (String cert : certs) {
                byte[] data = Utils.readStream(Transport.class.getClassLoader().getResourceAsStream(cert), L);
                if (data != null) {
                    String string = new String(data);
                    if (string.contains("--BEGIN")) {
                        data = Utils.Base64.decode(trimPem(string), L);
                    }
                } else {
                    data = Utils.Base64.decode(trimPem(cert), L);
                }

                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                Certificate certificate = cf.generateCertificate(new ByteArrayInputStream(data));
                certPins.add(certificate.getEncoded());
            }
        }

        try {
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("X509");
            trustManagerFactory.init((KeyStore) null);

            for (TrustManager trustManager : trustManagerFactory.getTrustManagers()) {
                if (trustManager instanceof X509TrustManager) {
                    defaultTrustManager = (X509TrustManager) trustManager;
                }
            }

            if (!keyPins.isEmpty() || !certPins.isEmpty()) {
                sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[] { this }, null);
            }
        } catch (Throwable t) {
            throw new CertificateException(t);
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (defaultTrustManager != null) {
            defaultTrustManager.checkClientTrusted(chain, authType);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (keyPins.isEmpty() && certPins.isEmpty()) {
            return;
        }

        if (chain == null) {
            throw new IllegalArgumentException("PublicKeyManager: X509Certificate array is null");
        }

        if (chain.length == 0) {
            throw new IllegalArgumentException("PublicKeyManager: X509Certificate is empty");
        }

        if (!(null != authType && authType.contains("RSA"))) {
            throw new CertificateException("PublicKeyManager: AuthType is not RSA");
        }

        // Perform standard SSL/TLS checks
        if (defaultTrustManager != null) {
            defaultTrustManager.checkServerTrusted(chain, authType);
        }

        byte[] serverPublicKey = chain[0].getPublicKey().getEncoded();
        byte[] serverCertificate = chain[0].getEncoded();

        for (byte[] key : keyPins) {
            if (Arrays.equals(key, serverPublicKey)) {
                return;
            }
        }

        for (byte[] key : certPins) {
            if (Arrays.equals(key, serverCertificate)) {
                return;
            }
        }

        throw new CertificateException("Neither certificate nor public key passed pinning validation");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    private String setProfilePicturePathRequestParams(String path, Params params) {
        Params tempParams = new Params();

        tempParams.add("device_id", params.get("device_id"));
        tempParams.add("app_key", params.get("app_key"));
        tempParams.add("timestamp", params.get("timestamp"));
        tempParams.add("sdk_name", params.get("sdk_name"));
        tempParams.add("sdk_version", params.get("sdk_version"));
        tempParams.add("tz", params.get("tz"));
        tempParams.add("hour", params.get("hour"));
        tempParams.add("dow", params.get("dow"));
        tempParams.add("rr", params.get("rr"));

        if (params.has("av")) {
            tempParams.add("av", params.get("av"));
        }
        //if no user details, add empty user details to indicate that we are sending a picture
        if (!params.has("user_details")) {
            tempParams.add("user_details", "{}");
        }

        return path + tempParams;
    }
}