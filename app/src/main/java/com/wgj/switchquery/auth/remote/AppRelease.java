package com.wgj.switchquery.auth.remote;

/** 服务端发布的一个 APP 版本（用于应用内升级）。 */
public class AppRelease {

    public final long versionCode;
    public final String versionName;
    public final long size;
    public final String sha256;
    public final String changelog;
    public final long publishedAt;
    /** 相对路径，如 /download/app.apk */
    public final String url;

    public AppRelease(long versionCode, String versionName, long size,
                      String sha256, String changelog, long publishedAt, String url) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.size = size;
        this.sha256 = sha256;
        this.changelog = changelog;
        this.publishedAt = publishedAt;
        this.url = url;
    }

    public boolean isAvailable() {
        return versionCode > 0 && url != null && !url.isEmpty();
    }
}
