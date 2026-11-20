package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * One request as {@link GzipStandInServer} received it: the body decoded and its parameters read
 * the way the Countly server reads them, which takes a body as gzip only when the compression
 * marker is the only query of the URL.
 */
final class GzipStandInRequest {

    static final String MARKER_QUERY = "content_encoding=gzip";

    final String method;
    final String path;
    final boolean compressed;
    final String body;
    final int bodyBytes;
    final String contentType;
    final String contentEncoding;
    final Map<String, String> params;
    final AtomicBoolean answered = new AtomicBoolean(false);

    /**
     * Reads a request from its exchange.
     *
     * @param exchange the exchange the request arrived in
     * @throws IOException when the body cannot be read or decoded
     */
    GzipStandInRequest(HttpExchange exchange) throws IOException {
        method = exchange.getRequestMethod();
        path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        compressed = MARKER_QUERY.equals(query);
        byte[] raw = readAll(exchange.getRequestBody());
        bodyBytes = raw.length;
        body = new String(compressed ? gunzip(raw) : raw, StandardCharsets.UTF_8);
        contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        contentEncoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
        params = TestUtils.parseQueryParams(compressed || !Utils.isNotEmpty(query) ? body : query);
        params.replaceAll((key, value) -> Utils.urldecode(value));
    }

    /**
     * Decodes gzip bytes.
     *
     * @param compressed gzip bytes
     * @return the decoded bytes
     * @throws IOException when the bytes are not gzip
     */
    static byte[] gunzip(byte[] compressed) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return readAll(in);
        }
    }

    /**
     * Reads a stream to its end.
     *
     * @param stream the stream to read
     * @return every byte of the stream
     * @throws IOException when the stream cannot be read
     */
    static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read = stream.read(buffer);
        while (read != -1) {
            out.write(buffer, 0, read);
            read = stream.read(buffer);
        }
        return out.toByteArray();
    }
}
