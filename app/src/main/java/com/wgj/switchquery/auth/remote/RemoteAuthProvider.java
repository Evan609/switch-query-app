package com.wgj.switchquery.auth.remote;

import android.content.Context;
import android.os.Build;

import com.wgj.switchquery.auth.Announcement;
import com.wgj.switchquery.auth.AuthConfig;
import com.wgj.switchquery.auth.AuthProvider;
import com.wgj.switchquery.auth.AuthResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * 服务端认证实现。
 *
 * 安全要点：
 *   1. 只信任内嵌的服务器证书（证书锁定），不受系统证书库影响，也不会被伪造证书骗过；
 *   2. 校验响应头 X-Signature（HMAC-SHA256），签名不匹配直接拒绝；
 *   3. 校验 X-Server-Time 与本地时间偏差，防止录制重放。
 *
 * 删除本包即可整体移除服务端认证，AuthManager 会自动回退到本地认证。
 */
public class RemoteAuthProvider implements AuthProvider {

    private static final String TAG = "RemoteAuth";

    private final Context ctx;
    private final TokenStore tokenStore;
    private SSLSocketFactory socketFactory;

    public RemoteAuthProvider(Context context) {
        this.ctx = context.getApplicationContext();
        this.tokenStore = new TokenStore(this.ctx);
    }

    @Override
    public String name() {
        return "remote";
    }

    @Override
    public boolean hasToken() {
        return !tokenStore.token().isEmpty() && !tokenStore.expired();
    }

    @Override
    public String username() {
        return tokenStore.username();
    }

    @Override
    public boolean supportsRegister() {
        return true;
    }

    @Override
    public AuthResult login(String username, String password) {
        try {
            JSONObject req = new JSONObject();
            req.put("username", username == null ? "" : username.trim());
            req.put("password", password == null ? "" : password);
            req.put("client", clientTag());
            req.put("app_version", AuthConfig.APP_VERSION);

            JSONObject r = post("/api/login", req);
            if (r.optBoolean("ok")) {
                tokenStore.save(r.optString("token"), r.optLong("expires_at"),
                        r.optString("username"));
                return AuthResult.ok(r.optString("username"));
            }
            return AuthResult.fail(r.optString("code", AuthResult.SERVER_ERROR),
                    r.optString("error", "登录失败"), r.optString("status"));
        } catch (Exception e) {
            return AuthResult.network(e.getMessage());
        }
    }

    @Override
    public AuthResult verify() {
        String token = tokenStore.token();
        if (token.isEmpty()) {
            return AuthResult.fail(AuthResult.TOKEN_INVALID, "尚未登录，请先登录", "");
        }
        if (tokenStore.expired()) {
            tokenStore.clear();
            return AuthResult.fail(AuthResult.TOKEN_EXPIRED, "登录状态已过期，请重新登录", "");
        }
        try {
            JSONObject req = new JSONObject();
            req.put("token", token);
            req.put("app_version", AuthConfig.APP_VERSION);
            JSONObject r = post("/api/verify", req);

            if (r.optBoolean("ok") && r.optBoolean("enabled", true)) {
                long exp = r.optLong("expires_at", 0L);
                if (exp > 0) {
                    tokenStore.save(token, exp, r.optString("username", tokenStore.username()));
                }
                return AuthResult.ok(r.optString("status", "active"));
            }
            String code = r.optString("code", AuthResult.SERVER_ERROR);
            if (AuthResult.TOKEN_EXPIRED.equals(code) || AuthResult.TOKEN_INVALID.equals(code)) {
                tokenStore.clear();
            }
            return AuthResult.fail(code, r.optString("error", "账号不可用"), r.optString("status"));
        } catch (Exception e) {
            return AuthResult.network(e.getMessage());
        }
    }

    @Override
    public AuthResult register(String username, String password) {
        try {
            JSONObject req = new JSONObject();
            req.put("username", username == null ? "" : username.trim());
            req.put("password", password == null ? "" : password);
            req.put("client", clientTag());
            req.put("app_version", AuthConfig.APP_VERSION);

            JSONObject r = post("/api/register", req);
            if (r.optBoolean("ok")) {
                return AuthResult.registerOk(r.optString("status"),
                        r.optString("message", "注册成功"));
            }
            return AuthResult.fail(r.optString("code", AuthResult.SERVER_ERROR),
                    r.optString("error", "注册失败"), r.optString("status"));
        } catch (Exception e) {
            return AuthResult.network(e.getMessage());
        }
    }

    @Override
    public List<Announcement> fetchAnnouncements() {
        String token = tokenStore.token();
        if (token.isEmpty()) return Collections.emptyList();
        try {
            JSONObject req = new JSONObject();
            req.put("token", token);
            JSONObject r = post("/api/announcements", req);
            if (!r.optBoolean("ok")) return Collections.emptyList();
            JSONArray items = r.optJSONArray("items");
            if (items == null) return Collections.emptyList();
            List<Announcement> list = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) continue;
                list.add(new Announcement(
                        o.optInt("id"),
                        o.optString("title"),
                        o.optString("content"),
                        o.optString("target"),
                        o.optLong("created_at")));
            }
            return list;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public List<Announcement> fetchPublicAnnouncements() {
        // 登录页使用：无需令牌，仅返回面向全体用户的公告
        try {
            JSONObject r = get("/api/public/announcements");
            if (!r.optBoolean("ok")) return Collections.emptyList();
            JSONArray items = r.optJSONArray("items");
            if (items == null) return Collections.emptyList();
            List<Announcement> list = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) continue;
                list.add(new Announcement(
                        o.optInt("id"),
                        o.optString("title"),
                        o.optString("content"),
                        o.optString("target"),
                        o.optLong("created_at")));
            }
            return list;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public void logout() {
        String token = tokenStore.token();
        if (!token.isEmpty()) {
            try {
                JSONObject req = new JSONObject();
                req.put("token", token);
                post("/api/logout", req);
            } catch (Exception ignored) {
            }
        }
        tokenStore.clear();
    }

    // ---------------- 网络与安全 ----------------

    private JSONObject post(String path, JSONObject body) throws Exception {
        return request("POST", path, body);
    }

    private JSONObject get(String path) throws Exception {
        return request("GET", path, null);
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        if (AuthConfig.APP_SECRET == null || AuthConfig.APP_SECRET.length() < 32) {
            throw new Exception("APP 未配置 app_secret，无法校验服务端响应");
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(AuthConfig.BASE_URL + path);
            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(socketFactory());
            }
            conn.setRequestMethod(method);
            conn.setConnectTimeout(AuthConfig.CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(AuthConfig.READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }

            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String raw = readAll(is);

            String ts = conn.getHeaderField("X-Server-Time");
            String sig = conn.getHeaderField("X-Signature");
            verifySigned(ts, sig, raw);
            return new JSONObject(raw);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 校验服务端时间与签名，失败即拒绝（防伪造 / 防重放）。升级模块复用同一套校验。 */
    static void verifySigned(String ts, String sig, String raw) throws Exception {
        if (ts == null || sig == null) {
            throw new Exception("服务端响应未签名，已拒绝");
        }
        long serverTime;
        try {
            serverTime = Long.parseLong(ts.trim());
        } catch (NumberFormatException e) {
            throw new Exception("服务端时间戳格式异常，已拒绝");
        }
        long skew = Math.abs(System.currentTimeMillis() / 1000L - serverTime);
        if (skew > AuthConfig.MAX_CLOCK_SKEW_SECONDS) {
            throw new Exception("服务器时间偏差过大（" + skew + "秒），请检查服务器校时");
        }
        String expected = hmacHex(AuthConfig.APP_SECRET, ts + "\n" + raw);
        if (!MessageDigest.isEqual(
                expected.toUpperCase().getBytes(StandardCharsets.UTF_8),
                sig.toUpperCase().getBytes(StandardCharsets.UTF_8))) {
            throw new Exception("服务端响应签名校验失败");
        }
    }

    private SSLSocketFactory socketFactory() throws Exception {
        if (socketFactory != null) return socketFactory;
        socketFactory = pinnedSocketFactory(ctx);
        return socketFactory;
    }

    /** 只信任内嵌服务器证书的 SSLSocketFactory（证书锁定）。 */
    static SSLSocketFactory pinnedSocketFactory(Context ctx) throws Exception {
        int resId = ctx.getResources().getIdentifier(
                AuthConfig.CERT_RES_NAME, "raw", ctx.getPackageName());
        if (resId == 0) {
            throw new Exception("未找到内置服务器证书 res/raw/switch_auth_cert.pem");
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert;
        try (InputStream in = ctx.getResources().openRawResource(resId)) {
            cert = (X509Certificate) cf.generateCertificate(in);
        } catch (Exception e) {
            throw new Exception("内置服务器证书无效，请用服务器 cert/server.crt 的内容替换 res/raw/switch_auth_cert.pem");
        }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("switch-auth", cert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        return ssl.getSocketFactory();
    }

    /** 必须按字节原样读取：签名校验基于服务器返回的原始 body，任何增删字符都会导致校验失败 */
    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String hmacHex(String key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] out = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(out.length * 2);
        for (byte b : out) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String clientTag() {
        String model = Build.MODEL == null ? "" : Build.MODEL;
        return "android/" + model;
    }
}
