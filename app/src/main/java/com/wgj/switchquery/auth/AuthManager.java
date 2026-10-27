package com.wgj.switchquery.auth;

import android.content.Context;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * 认证统一入口。UI 只调用本类，不直接依赖任何一种实现。
 *
 * 【如何关闭服务端认证，回到本地认证】
 *   删除整个 com.wgj.switchquery.auth.remote 包（连同 res/raw/switch_auth_cert.pem）即可。
 *   本类通过反射加载远端实现，加载失败会自动回退到 LocalAuthProvider，
 *   UI、项目数据、交换机连接等本体代码均无需改动，也不会编译失败。
 *
 * 【远端认证独立性的保证】
 *   - 认证模块不引用 ProjectStore / ConnectionManager / 任何业务代码；
 *   - 业务代码只通过本类获取“能不能用”的结论，不感知账号、令牌、签名等细节；
 *   - 公告为空时（本地模式）UI 不会弹出任何内容。
 */
public final class AuthManager {

    private static final String TAG = "AuthManager";
    private static final String REMOTE_CLASS =
            "com.wgj.switchquery.auth.remote.RemoteAuthProvider";

    private static volatile AuthManager instance;

    private AuthProvider provider;

    private AuthManager() {
    }

    public static AuthManager get() {
        if (instance == null) {
            synchronized (AuthManager.class) {
                if (instance == null) instance = new AuthManager();
            }
        }
        return instance;
    }

    /** 在 Application 启动时调用一次。 */
    public synchronized void init(Context context) {
        if (provider != null) return;
        Context appCtx = context.getApplicationContext();
        try {
            AuthConfig.APP_VERSION = appCtx.getPackageManager()
                    .getPackageInfo(appCtx.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        if (AuthConfig.isReady()) {
            try {
                Class<?> clazz = Class.forName(REMOTE_CLASS);
                provider = (AuthProvider) clazz.getConstructor(Context.class)
                        .newInstance(appCtx);
                Log.i(TAG, "认证模式：服务端");
            } catch (Throwable t) {
                // 远端模块不存在或初始化失败，直接回退本地认证
                provider = null;
            }
        }
        if (provider == null) {
            provider = new LocalAuthProvider();
            Log.i(TAG, "认证模式：本地");
        }
    }

    /** 当前是否为服务端认证模式（登录页据此决定是否显示账号输入框）。 */
    public boolean isRemote() {
        return provider != null && "remote".equals(provider.name());
    }

    public boolean hasToken() {
        return provider != null && provider.hasToken();
    }

    public boolean supportsRegister() {
        return provider != null && provider.supportsRegister();
    }

    public AuthResult login(String username, String password) {
        return provider == null
                ? AuthResult.fail(AuthResult.SERVER_ERROR, "认证模块未初始化", "")
                : provider.login(username, password);
    }

    public AuthResult verify() {
        return provider == null
                ? AuthResult.ok("")
                : provider.verify();
    }

    public AuthResult register(String username, String password) {
        return provider == null
                ? AuthResult.fail(AuthResult.REGISTER_CLOSED, "不支持注册", "")
                : provider.register(username, password);
    }

    public List<Announcement> announcements() {
        if (provider == null) return Collections.emptyList();
        return provider.fetchAnnouncements();
    }

    /** 登录页公告（无需登录，本地模式为空） */
    public List<Announcement> publicAnnouncements() {
        if (provider == null) return Collections.emptyList();
        return provider.fetchPublicAnnouncements();
    }

    public void logout() {
        if (provider != null) provider.logout();
    }

    /** 当前登录用户名（本地模式为空串） */
    public String username() {
        return provider == null ? "" : provider.username();
    }
}
