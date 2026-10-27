package com.wgj.switchquery.auth.remote;

import android.content.Context;

import com.wgj.switchquery.auth.AuthConfig;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * 应用内升级的服务端客户端。
 *
 * 复用认证模块的证书锁定与响应签名校验；下载 APK 时通过 SHA256 校验完整性。
 * 与认证模块一起位于 remote 包内，删除该包即同时移除升级功能。
 */
public class AppUpdateClient {

    /** 下载进度回调，返回 false 表示取消下载 */
    public interface Progress {
        boolean onProgress(long done, long total);
    }

    public static class Result {
        public final AppRelease release;
        public final String error;

        private Result(AppRelease release, String error) {
            this.release = release;
            this.error = error;
        }

        public boolean ok() {
            return error == null;
        }
    }

    private final Context ctx;

    public AppUpdateClient(Context context) {
        this.ctx = context.getApplicationContext();
    }

    /** 查询服务端最新发布版本；出错时返回 error 信息 */
    public Result check() {
        HttpURLConnection conn = null;
        try {
            conn = open("/api/app/version");
            int status = conn.getResponseCode();
            String raw = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            RemoteAuthProvider.verifySigned(conn.getHeaderField("X-Server-Time"),
                    conn.getHeaderField("X-Signature"), raw);
            if (status != 200) {
                return new Result(null, "检查更新失败（HTTP " + status + "）");
            }
            JSONObject o = new JSONObject(raw);
            if (!o.optBoolean("ok")) {
                return new Result(null, o.optString("error", "检查更新失败"));
            }
            AppRelease release = new AppRelease(
                    o.optLong("version_code"),
                    o.optString("version_name"),
                    o.optLong("size"),
                    o.optString("sha256"),
                    o.optString("changelog"),
                    o.optLong("published_at"),
                    o.optString("url"));
            return new Result(release, null);
        } catch (Exception e) {
            return new Result(null, e.getMessage() == null ? "检查更新失败" : e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 下载 APK 到 target。成功返回 null，失败/取消返回错误信息。
     * 下载完成后校验大小与 SHA256，不一致会删除文件。
     */
    public String download(AppRelease release, File target, Progress progress) {
        HttpURLConnection conn = null;
        File tmp = new File(target.getAbsolutePath() + ".part");
        try {
            String path = release.url == null ? "" : release.url.trim();
            if (!path.startsWith("/")) {
                return "下载地址无效";
            }
            conn = open(path);
            conn.setReadTimeout(60 * 1000);
            int status = conn.getResponseCode();
            if (status != 200) {
                return "下载失败（HTTP " + status + "）";
            }
            long total = conn.getContentLengthLong();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long done = 0;
            byte[] buf = new byte[32 * 1024];
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(tmp)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    done += n;
                    if (progress != null && !progress.onProgress(done, total)) {
                        return "已取消下载";
                    }
                }
            }
            if (release.size > 0 && done != release.size) {
                return "文件大小不一致（" + done + " / " + release.size + "），已删除";
            }
            String sha = hex(md.digest());
            if (release.sha256 != null && !release.sha256.isEmpty()
                    && !release.sha256.equalsIgnoreCase(sha)) {
                return "文件校验失败，已删除";
            }
            if (target.exists() && !target.delete()) {
                return "无法写入安装包目录";
            }
            if (!tmp.renameTo(target)) {
                return "保存安装包失败";
            }
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? "下载失败" : e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
            if (tmp.exists()) tmp.delete();
        }
    }

    private HttpURLConnection open(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL(AuthConfig.BASE_URL + path).openConnection();
        if (conn instanceof HttpsURLConnection) {
            SSLSocketFactory f = RemoteAuthProvider.pinnedSocketFactory(ctx);
            ((HttpsURLConnection) conn).setSSLSocketFactory(f);
        }
        conn.setConnectTimeout(AuthConfig.CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(AuthConfig.READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

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

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
