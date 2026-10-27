package com.wgj.switchquery.auth;

import java.util.List;

/**
 * 认证实现的统一接口。所有方法均为阻塞调用，必须在后台线程执行。
 */
public interface AuthProvider {

    /** 实现名称：remote / local */
    String name();

    /** 当前是否已持有可用的登录令牌 */
    boolean hasToken();

    /** 当前登录用户名（本地模式返回空串） */
    String username();

    AuthResult login(String username, String password);

    /** 校验当前登录状态是否仍被允许使用 */
    AuthResult verify();

    AuthResult register(String username, String password);

    List<Announcement> fetchAnnouncements();

    /** 登录前可见的公告（仅面向全体的公告，本地模式为空） */
    List<Announcement> fetchPublicAnnouncements();

    void logout();

    /** 是否支持自助注册 */
    boolean supportsRegister();
}
