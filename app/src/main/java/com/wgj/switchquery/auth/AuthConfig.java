package com.wgj.switchquery.auth;

/**
 * 服务端认证模块的配置。
 *
 * 关闭服务端认证（回到本地认证）只需两步：
 *   1. 删除整个 com.wgj.switchquery.auth.remote 包；
 *   2. 把 PREFER_REMOTE 改为 false（不改也可以，模块找不到会自动回退）。
 */
public final class AuthConfig {

    /** 是否启用服务端认证。删除 remote 包后即使这里保持 true，也会自动回退到本地认证。 */
    public static final boolean PREFER_REMOTE = true;

    /** 认证服务器地址（必须带 https 前缀） */
    public static final String BASE_URL = "https://39.107.26.67:8443";

    /** 与服务端 config.json 中 app_secret 完全一致，用于校验响应签名 */
    public static final String APP_SECRET =
            "5caae1f59e5402bd6e794abbc456c8edfc9bc98572d834d65f2a68d7198bfcb3";

    /**
     * 内嵌的服务器证书资源名（res/raw/switch_auth_cert.pem）。
     * 用名称而非 R.raw.xxx 引用，删除该资源时不会导致编译失败。
     */
    public static final String CERT_RES_NAME = "switch_auth_cert";

    /** 当前 APP 版本号，启动时由 AuthManager 自动从安装包读取（如 1.1） */
    public static String APP_VERSION = "";

    public static final int CONNECT_TIMEOUT_MS = 8000;
    public static final int READ_TIMEOUT_MS = 10000;

    /** 允许的服务器时间偏差（秒），用于防重放 */
    public static final long MAX_CLOCK_SKEW_SECONDS = 300;

    /**
     * 配置是否就绪。未填写 app_secret 时自动使用本地认证，
     * 避免配置不全导致 APP 完全无法登录。
     */
    public static boolean isReady() {
        return PREFER_REMOTE
                && APP_SECRET != null && APP_SECRET.length() >= 32
                && BASE_URL != null && BASE_URL.startsWith("https://");
    }

    private AuthConfig() {
    }
}
