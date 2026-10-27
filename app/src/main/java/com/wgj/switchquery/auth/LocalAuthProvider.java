package com.wgj.switchquery.auth;

import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

/**
 * 本地认证：密码固定为内置口令，不联网、无公告、不支持注册。
 * 删除 auth/remote 包后自动使用本实现，行为与改造前完全一致。
 */
public class LocalAuthProvider implements AuthProvider {

    // 密码 "APP68888978" 的 SHA-256 哈希（小写）
    private static final String PASSWORD_HASH =
            "48ff241e59a48344cc52b9c6665a758fe7ce85ae7432eded8aef7172b888c059";

    @Override
    public String name() {
        return "local";
    }

    @Override
    public boolean hasToken() {
        return false;
    }

    @Override
    public String username() {
        return "";
    }

    @Override
    public AuthResult login(String username, String password) {
        if (PASSWORD_HASH.equalsIgnoreCase(sha256(password))) {
            return AuthResult.ok("");
        }
        return AuthResult.fail(AuthResult.BAD_CREDENTIALS, "密码错误，请重新输入", "");
    }

    @Override
    public AuthResult verify() {
        return AuthResult.ok("");
    }

    @Override
    public AuthResult register(String username, String password) {
        return AuthResult.fail(AuthResult.REGISTER_CLOSED, "本地认证模式不支持注册", "");
    }

    @Override
    public List<Announcement> fetchAnnouncements() {
        return Collections.emptyList();
    }

    @Override
    public List<Announcement> fetchPublicAnnouncements() {
        return Collections.emptyList();
    }

    @Override
    public void logout() {
    }

    @Override
    public boolean supportsRegister() {
        return false;
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
