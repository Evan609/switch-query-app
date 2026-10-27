package com.wgj.switchquery.auth.remote;

import android.content.Context;
import android.content.SharedPreferences;

import com.wgj.switchquery.crypto.CryptoHelper;

/**
 * 登录令牌的本地存储，令牌经 AES 加密后写入 SharedPreferences。
 */
class TokenStore {

    private static final String PREF = "switch_auth_state";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_EXPIRES = "expires_at";
    private static final String KEY_USER = "username";

    private final SharedPreferences sp;

    TokenStore(Context context) {
        sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    void save(String token, long expiresAt, String username) {
        String stored;
        try {
            stored = CryptoHelper.encrypt(token);
        } catch (Exception e) {
            stored = token;
        }
        sp.edit()
                .putString(KEY_TOKEN, stored)
                .putLong(KEY_EXPIRES, expiresAt)
                .putString(KEY_USER, username == null ? "" : username)
                .apply();
    }

    String token() {
        String stored = sp.getString(KEY_TOKEN, "");
        if (stored == null || stored.isEmpty()) return "";
        try {
            return CryptoHelper.decrypt(stored);
        } catch (Exception e) {
            return "";
        }
    }

    long expiresAt() {
        return sp.getLong(KEY_EXPIRES, 0L);
    }

    String username() {
        return sp.getString(KEY_USER, "");
    }

    /** 本地预判是否已过期（提前 30 秒判定，避免临界时刻请求失败） */
    boolean expired() {
        long exp = expiresAt();
        return exp <= 0 || System.currentTimeMillis() / 1000L >= exp - 30;
    }

    void clear() {
        sp.edit().remove(KEY_TOKEN).remove(KEY_EXPIRES).remove(KEY_USER).apply();
    }
}
