package com.jingying.watch;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 一个很小的 HTTP/1.1 客户端。
 *
 * Android 自带的 HttpURLConnection 只允许标准方法，PROPFIND 会直接抛
 * ProtocolException；WebDAV 必须能发自定义动词，所以这里直接用 Socket 说话。
 * 支持 Basic 鉴权、Range、chunked 响应、重定向和自签名 HTTPS。
 */
final class HttpLite {

    static final class Response implements Closeable {
        final int status;
        final String reason;
        final Map<String, String> headers;
        final InputStream body;

        Response(int status, String reason, Map<String, String> headers, InputStream body) {
            this.status = status;
            this.reason = reason;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }

        long contentLength() {
            String value = header("Content-Length");
            if (value == null) {
                return -1;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException exc) {
                return -1;
            }
        }

        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
            }
        }
    }

    private HttpLite() {
    }

    static Response request(String method, URL url, Map<String, String> extraHeaders,
                            byte[] payload, int connectTimeout, int readTimeout,
                            SSLContext sslContext, boolean insecure) throws IOException {
        URL current = url;
        String currentMethod = method;
        byte[] currentPayload = payload;
        for (int redirect = 0; redirect < 6; redirect++) {
            Response response = requestOnce(currentMethod, current, extraHeaders,
                    currentPayload, connectTimeout, readTimeout, sslContext, insecure);
            int status = response.status;
            if (status == 301 || status == 302 || status == 303
                    || status == 307 || status == 308) {
                String location = response.header("Location");
                response.close();
                if (location == null || location.isEmpty()) {
                    throw new IOException("服务器重定向却没有 Location");
                }
                current = new URL(current, location);
                if (status == 303) {
                    currentMethod = "GET";
                    currentPayload = null;
                }
                continue;
            }
            return response;
        }
        throw new IOException("重定向次数过多");
    }

    private static Response requestOnce(String method, URL url,
                                        Map<String, String> extraHeaders, byte[] payload,
                                        int connectTimeout, int readTimeout,
                                        SSLContext sslContext, boolean insecure)
            throws IOException {
        String scheme = url.getProtocol();
        boolean https = "https".equalsIgnoreCase(scheme);
        String host = url.getHost();
        int port = url.getPort() > 0 ? url.getPort() : (https ? 443 : 80);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeout);
            socket.setSoTimeout(readTimeout);
            socket.setTcpNoDelay(true);
            if (https) {
                SSLSocketFactory factory = sslContext != null
                        ? sslContext.getSocketFactory()
                        : (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ssl = (SSLSocket) factory.createSocket(socket, host, port, true);
                if (!insecure) {
                    SSLParameters parameters = ssl.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    ssl.setSSLParameters(parameters);
                }
                ssl.startHandshake();
                socket = ssl;
            }
            String path = url.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (url.getQuery() != null && !url.getQuery().isEmpty()) {
                path += "?" + url.getQuery();
            }
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(host);
            if ((https && port != 443) || (!https && port != 80)) {
                head.append(':').append(port);
            }
            head.append("\r\n");
            head.append("User-Agent: JingYingWatch/1.0 (Android; webdav)\r\n");
            head.append("Accept: */*\r\n");
            head.append("Accept-Encoding: identity\r\n");
            head.append("Connection: close\r\n");
            if (payload != null) {
                head.append("Content-Length: ").append(payload.length).append("\r\n");
            }
            if (extraHeaders != null) {
                for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                    head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
                }
            }
            head.append("\r\n");
            OutputStream output = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
            output.write(head.toString().getBytes("UTF-8"));
            if (payload != null) {
                output.write(payload);
            }
            output.flush();

            InputStream input = new BufferedInputStream(socket.getInputStream(), 32 * 1024);
            String statusLine;
            int status;
            String reason;
            while (true) {
                statusLine = readLine(input);
                if (statusLine == null) {
                    throw new IOException("服务器没有返回响应");
                }
                int firstSpace = statusLine.indexOf(' ');
                int secondSpace = statusLine.indexOf(' ', firstSpace + 1);
                if (firstSpace < 0 || secondSpace < 0) {
                    throw new IOException("响应格式不正确：" + statusLine);
                }
                status = Integer.parseInt(statusLine.substring(firstSpace + 1, secondSpace).trim());
                reason = statusLine.substring(secondSpace + 1).trim();
                if (status >= 200) {
                    break;
                }
                // 跳过 100 Continue 之类的中间响应
                readHeaders(input);
            }
            Map<String, String> headers = readHeaders(input);
            InputStream body;
            String encoding = headers.get("transfer-encoding");
            if ("HEAD".equals(method) || status == 204 || status == 304) {
                body = new EmptyStream(socket);
            } else if (encoding != null && encoding.toLowerCase(Locale.US).contains("chunked")) {
                body = new ChunkedStream(input, socket);
            } else {
                long length = -1;
                String rawLength = headers.get("content-length");
                if (rawLength != null) {
                    try {
                        length = Long.parseLong(rawLength.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                body = length >= 0 ? new LimitedStream(input, length, socket)
                        : new SocketStream(input, socket);
            }
            return new Response(status, reason, headers, body);
        } catch (IOException exc) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw exc;
        }
    }

    private static Map<String, String> readHeaders(InputStream input) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine(input);
            if (line == null || line.isEmpty()) {
                return headers;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            String previous = headers.get(name);
            headers.put(name, previous == null ? value : previous + ", " + value);
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                buffer.write(value);
            }
            if (buffer.size() > 16 * 1024) {
                throw new IOException("响应头过长");
            }
        }
        if (value == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString("UTF-8");
    }

    private abstract static class ClosingStream extends InputStream {
        final Socket socket;

        ClosingStream(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void close() throws IOException {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class EmptyStream extends ClosingStream {
        EmptyStream(Socket socket) {
            super(socket);
        }

        @Override
        public int read() {
            return -1;
        }
    }

    private static final class SocketStream extends ClosingStream {
        private final InputStream input;

        SocketStream(InputStream input, Socket socket) {
            super(socket);
            this.input = input;
        }

        @Override
        public int read() throws IOException {
            return input.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return input.read(buffer, offset, length);
        }
    }

    private static final class LimitedStream extends ClosingStream {
        private final InputStream input;
        private long remaining;

        LimitedStream(InputStream input, long length, Socket socket) {
            super(socket);
            this.input = input;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = input.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = input.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }

    /** chunked 传输编码解码。 */
    private static final class ChunkedStream extends ClosingStream {
        private final InputStream input;
        private long chunkRemaining;
        private boolean finished;

        ChunkedStream(InputStream input, Socket socket) {
            super(socket);
            this.input = input;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read <= 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (finished) {
                return -1;
            }
            if (chunkRemaining <= 0) {
                String line = readLine(input);
                if (line == null) {
                    finished = true;
                    return -1;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    return read(buffer, offset, length);
                }
                int semicolon = line.indexOf(';');
                if (semicolon > 0) {
                    line = line.substring(0, semicolon).trim();
                }
                try {
                    chunkRemaining = Long.parseLong(line, 16);
                } catch (NumberFormatException exc) {
                    throw new IOException("chunk 长度不正确：" + line);
                }
                if (chunkRemaining == 0) {
                    finished = true;
                    // 读掉 trailer
                    String trailer;
                    do {
                        trailer = readLine(input);
                    } while (trailer != null && !trailer.isEmpty());
                    return -1;
                }
            }
            int read = input.read(buffer, offset, (int) Math.min(length, chunkRemaining));
            if (read > 0) {
                chunkRemaining -= read;
            }
            return read;
        }
    }
}
