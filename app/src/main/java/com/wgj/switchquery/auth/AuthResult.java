package com.wgj.switchquery.auth;

/**
 * 认证结果。UI 只依赖本类，不关心是本地认证还是服务端认证。
 */
public class AuthResult {

    public static final String OK = "OK";
    public static final String BAD_CREDENTIALS = "BAD_CREDENTIALS";
    public static final String PENDING = "PENDING";
    public static final String REJECTED = "REJECTED";
    public static final String DISABLED = "DISABLED";
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String TOKEN_INVALID = "TOKEN_INVALID";
    public static final String REGISTER_CLOSED = "REGISTER_CLOSED";
    public static final String VERSION_BLOCKED = "VERSION_BLOCKED";
    public static final String USER_EXISTS = "USER_EXISTS";
    public static final String NETWORK_ERROR = "NETWORK_ERROR";
    public static final String SERVER_ERROR = "SERVER_ERROR";

    public final boolean ok;
    public final String code;
    public final String message;
    /** 服务端返回的账号状态（active/pending/rejected/disabled） */
    public final String status;
    /** 注册接口返回的注册结果状态 */
    public final String registerStatus;

    private AuthResult(boolean ok, String code, String message, String status, String registerStatus) {
        this.ok = ok;
        this.code = code == null ? "" : code;
        this.message = message == null ? "" : message;
        this.status = status == null ? "" : status;
        this.registerStatus = registerStatus == null ? "" : registerStatus;
    }

    public static AuthResult ok(String status) {
        return new AuthResult(true, OK, "", status, "");
    }

    public static AuthResult registerOk(String registerStatus, String message) {
        return new AuthResult(true, OK, message, "", registerStatus);
    }

    public static AuthResult fail(String code, String message, String status) {
        return new AuthResult(false, code, message, status, "");
    }

    public static AuthResult network(String message) {
        return new AuthResult(false, NETWORK_ERROR,
                message == null || message.isEmpty() ? "网络异常，无法连接认证服务器" : message, "", "");
    }

    /** 是否为“服务端明确拒绝使用”的终态，需要退回登录页 */
    public boolean isDenied() {
        return !ok && (PENDING.equals(code) || REJECTED.equals(code)
                || DISABLED.equals(code) || TOKEN_EXPIRED.equals(code)
                || TOKEN_INVALID.equals(code) || BAD_CREDENTIALS.equals(code)
                || VERSION_BLOCKED.equals(code));
    }
}
