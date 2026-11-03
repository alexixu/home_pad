package com.alex.minimalhome;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** One bounded session handshake. Audio travels directly over WebRTC, never through this request. */
final class VoiceServiceRequest {
    private volatile boolean cancelled;
    private volatile HttpsURLConnection connection;

    void cancel() {
        cancelled = true;
        HttpsURLConnection active = connection;
        if (active != null) active.disconnect();
    }

    JSONObject create(VoiceServiceConfig config, String offer, String nonce) throws Exception {
        JSONObject body = new JSONObject().put("offer_sdp", offer).put("read_only", config.readOnly).put("nonce", nonce);
        JSONObject result = request(config, "/v1/voice/sessions", "POST", body, 60000);
        if (!"live".equals(result.optString("protocol")) || result.optString("sessionId").isEmpty()
                || !result.optString("answerSdp").startsWith("v=0")
                || (config.readOnly && !result.optBoolean("readOnly", false))) {
            throw new Failure("语音服务响应不完整，请检查服务配置。");
        }
        return new JSONObject().put("sessionId", result.getString("sessionId"))
                .put("answerSdp", result.getString("answerSdp")).put("protocol", "live")
                .put("backend", result.optString("backend", "live"))
                .put("readOnly", result.optBoolean("readOnly", true));
    }

    JSONObject poll(VoiceServiceConfig config, String session) throws Exception {
        return request(config, sessionPath(session) + "/events", "GET", null, 12000);
    }

    void toolResult(VoiceServiceConfig config, String session, String call, JSONObject result) throws Exception {
        request(config, sessionPath(session) + "/results", "POST",
                new JSONObject().put("call_id", call).put("result", result), 12000);
    }

    void close(VoiceServiceConfig config, String session) throws Exception {
        request(config, sessionPath(session) + "/close", "POST", new JSONObject(), 10000);
    }

    private String sessionPath(String session) {
        if (session == null || !session.matches("[A-Za-z0-9_-]{8,128}")) throw new IllegalArgumentException();
        return "/v1/voice/sessions/" + session;
    }

    private JSONObject request(VoiceServiceConfig config, String path, String method, JSONObject payload,
            int timeout) throws Exception {
        if (cancelled) throw new java.io.IOException("cancelled");
        byte[] body = payload == null ? null : payload.toString().getBytes(StandardCharsets.UTF_8);
        if (body != null && body.length > 65536) throw new java.io.IOException("request_too_large");
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                throw new CertificateException("client_trust_not_supported");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                try {
                    if (chain == null || chain.length == 0) throw new CertificateException("missing_certificate");
                    chain[0].checkValidity();
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    StringBuilder hex = new StringBuilder(64);
                    for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                    if (!config.certificateSha256.equals(hex.toString())) throw new CertificateException("certificate_changed");
                } catch (CertificateException e) { throw e; }
                catch (Exception e) { throw new CertificateException("certificate_check_failed"); }
            }
        }}, new SecureRandom());
        HttpsURLConnection request = (HttpsURLConnection) new URL(config.endpoint + path).openConnection();
        connection = request;
        try {
            request.setSSLSocketFactory(tls.getSocketFactory());
            // Keep the platform hostname verifier even with an explicitly pinned certificate.
            request.setInstanceFollowRedirects(false);
            request.setConnectTimeout(10000); request.setReadTimeout(timeout);
            request.setRequestMethod(method); request.setDoOutput(body != null);
            request.setRequestProperty("Authorization", "Bearer " + config.token);
            request.setRequestProperty("Content-Type", "application/json");
            if (body != null) request.setFixedLengthStreamingMode(body.length);
            if (cancelled) throw new java.io.IOException("cancelled");
            if (body != null) { try (java.io.OutputStream out = request.getOutputStream()) { out.write(body); } }
            int status = request.getResponseCode();
            if (cancelled) throw new java.io.IOException("cancelled");
            InputStream input = status >= 200 && status < 300 ? request.getInputStream() : request.getErrorStream();
            byte[] bytes = new byte[0];
            if (input != null) {
                try (InputStream stream = input; ByteArrayOutputStream result = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = stream.read(buffer)) >= 0) {
                        result.write(buffer, 0, count);
                        if (result.size() > 131072) throw new java.io.IOException("response_too_large");
                    }
                    bytes = result.toByteArray();
                }
            }
            if (status < 200 || status >= 300) {
                String code = "";
                try {
                    JSONObject error = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).optJSONObject("error");
                    if (error != null) code = error.optString("code");
                } catch (Exception ignored) {}
                if (status == 401 || status == 403) throw new Failure("语音服务配对已失效，请到控制中心检查连接。");
                if (code.equals("missing_api_key") || code.equals("not_configured") || code.equals("api_key_missing") || code.equals("api_not_configured"))
                    throw new Failure("语音服务尚未配置 OpenAI 访问凭据。");
                if (status == 429) throw new Failure("语音服务暂时繁忙，请稍后重新连接。");
                throw new Failure("暂时无法连接 ChatGPT 语音，请稍后重试。");
            }
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } finally {
            request.disconnect();
            if (connection == request) connection = null;
        }
    }

    static final class Failure extends Exception {
        Failure(String message) { super(message); }
    }
}
