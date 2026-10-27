package com.wgj.switchquery.auth;

/**
 * 公告。本地认证模式下永远为空列表。
 */
public class Announcement {

    public final int id;
    public final String title;
    public final String content;
    public final String target;
    public final long createdAt;

    public Announcement(int id, String title, String content, String target, long createdAt) {
        this.id = id;
        this.title = title == null ? "" : title;
        this.content = content == null ? "" : content;
        this.target = target == null ? "" : target;
        this.createdAt = createdAt;
    }
}
