#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
交换机信息查询 APP — 授权服务（纯 Python 标准库实现）

适用：Rocky Linux 8 / 9（Python 3.11）
依赖：无（仅标准库）

功能：
  - 用户注册 + 审核（手动审核 / 自动拒绝 / 自动通过）
  - 账号登录、令牌签发
  - 授权校验（实时查库，禁用立即生效），未授权用户禁止使用
  - 公告推送（全体 / 指定用户）
  - 网页后台：审核、启用禁用、新增、重置密码、删除、公告管理、审计
  - 响应 HMAC 签名（配合证书锁定，防伪造/防重放）
"""

import argparse
import binascii
import getpass
import hashlib
import hmac
import json
import os
import re
import secrets
import signal
import sqlite3
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

# 并发工作线程上限：避免大量慢连接（慢 SSL 握手 / 慢下载）耗尽线程导致服务挂起
MAX_CONCURRENT_THREADS = 200

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CONFIG = os.path.join(BASE_DIR, "config.json")

# 用户状态
ST_PENDING = "pending"      # 待审核
ST_ACTIVE = "active"        # 正常（已授权）
ST_REJECTED = "rejected"    # 审核拒绝
ST_DISABLED = "disabled"    # 管理员禁用

# 注册策略
RM_MANUAL = "manual"            # 手动审核
RM_AUTO_REJECT = "auto_reject"  # 默认拒绝（关闭注册）
RM_AUTO_APPROVE = "auto_approve"  # 自动通过

REGISTER_MODES = (RM_MANUAL, RM_AUTO_REJECT, RM_AUTO_APPROVE)

# 登录失败限流：同一 IP 在 WINDOW 秒内失败超过 MAX_FAILS 次则暂时拒绝
LOGIN_WINDOW = 300
LOGIN_MAX_FAILS = 10
REGISTER_WINDOW = 3600
REGISTER_MAX = 5
_login_fails = {}
_register_stamps = {}

# 管理后台会话：Cookie 会话，空闲超时 + 绝对有效期
ADMIN_IDLE_SECONDS = 30 * 60
ADMIN_MAX_SECONDS = 12 * 3600
_admin_sessions = {}      # token -> {"created": ts, "seen": ts}
_admin_login_fails = {}

# 限流字典的线程锁：setdefault+append / 列表原地修改等复合操作不是原子的，
# 多线程下可能丢数据，统一加锁保护。
_rate_lock = threading.Lock()
_session_lock = threading.Lock()

DEFAULT_CONFIG_DATA = {
    "listen_host": "0.0.0.0",
    "listen_port": 8443,
    "cert_file": "cert/server.crt",
    "key_file": "cert/server.key",
    "db_file": "data/app.db",
    "release_dir": "releases",
    "app_secret": "",
    "clock_skew_seconds": 300,
    "token_days": 7,
    "register_mode": RM_MANUAL,
    "blocked_versions": [],
    "admin_user": "admin",
    "admin_pass_hash": "",
    "admin_pass_salt": "",
    "admin_pass_iterations": 0,
    "log_requests": True,
}

CFG = {}
CFG_PATH = ""


# --------------------------------------------------------------------------
# 基础工具
# --------------------------------------------------------------------------

def now_ts():
    return int(time.time())


def rnd_hex(n=32):
    return secrets.token_hex(n)


def pbkdf2(password, salt_hex, iterations):
    return hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), bytes.fromhex(salt_hex), iterations
    ).hex()


def make_password_hash(password, iterations=200000):
    salt = rnd_hex(16)
    return pbkdf2(password, salt, iterations), salt, iterations


def check_password(password, hash_hex, salt_hex, iterations):
    try:
        calc = pbkdf2(password, salt_hex, int(iterations))
    except (ValueError, binascii.Error):
        return False
    return hmac.compare_digest(calc, hash_hex)


def sign(secret, message):
    return hmac.new(secret.encode("utf-8"), message.encode("utf-8"), hashlib.sha256).hexdigest()


def escape(s):
    return (str(s).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;").replace("'", "&#39;"))


def nl2br(s):
    """HTML 转义并把换行符转为 <br>，用于在表格单元格内显示多行文本。"""
    return escape(s).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>")


def load_config(path):
    global CFG, CFG_PATH
    CFG_PATH = os.path.abspath(path)
    if not os.path.exists(CFG_PATH):
        raise SystemExit("配置文件不存在：%s\n请先复制 config.example.json 为 config.json 并填写。" % CFG_PATH)
    with open(CFG_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)
    merged = dict(DEFAULT_CONFIG_DATA)
    merged.update(data or {})
    CFG = merged
    if not CFG.get("app_secret"):
        raise SystemExit("config.json 中 app_secret 为空，请先生成：python3.11 -c \"import secrets;print(secrets.token_hex(32))\"")
    if len(CFG["app_secret"]) < 32:
        raise SystemExit("app_secret 长度不足 32 位十六进制，请重新生成。")
    if CFG.get("register_mode") not in REGISTER_MODES:
        CFG["register_mode"] = RM_MANUAL
    return CFG


def save_config():
    tmp = CFG_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(CFG, f, ensure_ascii=False, indent=2)
    os.chmod(tmp, 0o600)
    os.replace(tmp, CFG_PATH)


def resolve_path(p):
    if os.path.isabs(p):
        return p
    return os.path.join(BASE_DIR, p)


# --------------------------------------------------------------------------
# 数据库
# --------------------------------------------------------------------------

def db_connect():
    path = resolve_path(CFG["db_file"])
    d = os.path.dirname(path)
    if d and not os.path.isdir(d):
        os.makedirs(d, exist_ok=True)
    conn = sqlite3.connect(path, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    username     TEXT NOT NULL UNIQUE,
    pass_hash    TEXT NOT NULL,
    salt         TEXT NOT NULL,
    iterations   INTEGER NOT NULL,
    status       TEXT NOT NULL DEFAULT 'active',
    created_at   INTEGER NOT NULL,
    reviewed_at  INTEGER,
    last_login   INTEGER,
    note         TEXT DEFAULT ''
);
CREATE TABLE IF NOT EXISTS tokens (
    token        TEXT PRIMARY KEY,
    user_id      INTEGER NOT NULL,
    issued_at    INTEGER NOT NULL,
    expires_at   INTEGER NOT NULL,
    revoked      INTEGER NOT NULL DEFAULT 0,
    client       TEXT DEFAULT '',
    client_version TEXT DEFAULT ''
);
CREATE TABLE IF NOT EXISTS announcements (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    title          TEXT NOT NULL,
    content        TEXT NOT NULL,
    target_user_id INTEGER,
    active         INTEGER NOT NULL DEFAULT 1,
    created_at     INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS audit (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    ts           INTEGER NOT NULL,
    event        TEXT NOT NULL,
    username     TEXT,
    ip           TEXT,
    detail       TEXT
);
CREATE TABLE IF NOT EXISTS app_releases (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    version_code INTEGER NOT NULL UNIQUE,
    version_name TEXT NOT NULL,
    filename     TEXT NOT NULL,
    size         INTEGER NOT NULL,
    sha256       TEXT NOT NULL,
    changelog    TEXT DEFAULT '',
    active       INTEGER NOT NULL DEFAULT 1,
    created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit(ts);
"""


def db_init():
    conn = db_connect()
    try:
        conn.executescript(SCHEMA)
        # 兼容早期版本数据库（enabled 布尔列 → status 状态机，并补齐新增列）
        cols = [r[1] for r in conn.execute("PRAGMA table_info(users)").fetchall()]
        if "status" not in cols:
            conn.execute("ALTER TABLE users ADD COLUMN status TEXT NOT NULL DEFAULT 'active'")
        if "reviewed_at" not in cols:
            conn.execute("ALTER TABLE users ADD COLUMN reviewed_at INTEGER")
        if "enabled" in cols:
            conn.execute("UPDATE users SET status='disabled' WHERE enabled=0 AND status='active'")
        tcols = [r[1] for r in conn.execute("PRAGMA table_info(tokens)").fetchall()]
        if "client_version" not in tcols:
            conn.execute("ALTER TABLE tokens ADD COLUMN client_version TEXT DEFAULT ''")
        conn.commit()
    finally:
        conn.close()


# audit 表惰性清理计数器：避免每次 INSERT 都执行 DELETE 子查询，
# 改为每 INSERT 50 条才清理一次旧记录，减少高频请求时的数据库压力。
_audit_counter = 0
_audit_lock = threading.Lock()
AUDIT_CLEAN_EVERY = 50
AUDIT_KEEP_ROWS = 5000


def audit(event, username="", ip="", detail=""):
    global _audit_counter
    try:
        conn = db_connect()
        try:
            conn.execute(
                "INSERT INTO audit(ts,event,username,ip,detail) VALUES(?,?,?,?,?)",
                (now_ts(), event, username or "", ip or "", (detail or "")[:500]),
            )
            # 惰性清理：每 AUDIT_CLEAN_EVERY 次插入才删除旧记录
            with _audit_lock:
                _audit_counter += 1
                need_clean = _audit_counter >= AUDIT_CLEAN_EVERY
                if need_clean:
                    _audit_counter = 0
            if need_clean:
                conn.execute(
                    "DELETE FROM audit WHERE id NOT IN "
                    "(SELECT id FROM audit ORDER BY id DESC LIMIT ?)",
                    (AUDIT_KEEP_ROWS,),
                )
            conn.commit()
        finally:
            conn.close()
    except Exception as e:
        sys.stderr.write("[switch-auth] audit failed: %s\n" % e)


# --------------------------------------------------------------------------
# 用户
# --------------------------------------------------------------------------

def user_find(username):
    conn = db_connect()
    try:
        return conn.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
    finally:
        conn.close()


def user_by_id(uid):
    conn = db_connect()
    try:
        return conn.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
    finally:
        conn.close()


def user_create(username, password, status=ST_ACTIVE):
    username = (username or "").strip()
    if not username or len(username) > 64:
        raise ValueError("账号不能为空且不超过 64 字符")
    if len(password or "") < 6:
        raise ValueError("密码至少 6 位")
    h, s, it = make_password_hash(password)
    conn = db_connect()
    try:
        conn.execute(
            "INSERT INTO users(username,pass_hash,salt,iterations,status,created_at,reviewed_at)"
            " VALUES(?,?,?,?,?,?,?)",
            (username, h, s, it, status, now_ts(),
             now_ts() if status in (ST_ACTIVE, ST_REJECTED) else None),
        )
        conn.commit()
    except sqlite3.IntegrityError:
        raise ValueError("账号已存在：" + username)
    finally:
        conn.close()


def user_set_status(uid, status):
    # 不吊销已签发令牌：每次校验都会重新读库判断状态，
    # 这样 APP 能收到明确的"已停用/已拒绝"提示，而不是含糊的登录失效。
    conn = db_connect()
    try:
        conn.execute("UPDATE users SET status=?, reviewed_at=? WHERE id=?",
                     (status, now_ts(), uid))
        conn.commit()
    finally:
        conn.close()


def user_reset_password(uid, password):
    if len(password or "") < 6:
        raise ValueError("密码至少 6 位")
    h, s, it = make_password_hash(password)
    conn = db_connect()
    try:
        conn.execute(
            "UPDATE users SET pass_hash=?, salt=?, iterations=? WHERE id=?", (h, s, it, uid)
        )
        conn.execute("UPDATE tokens SET revoked=1 WHERE user_id=?", (uid,))
        conn.commit()
    finally:
        conn.close()


def user_delete(uid):
    conn = db_connect()
    try:
        conn.execute("DELETE FROM tokens WHERE user_id=?", (uid,))
        conn.execute("DELETE FROM announcements WHERE target_user_id=?", (uid,))
        conn.execute("DELETE FROM users WHERE id=?", (uid,))
        conn.commit()
    finally:
        conn.close()


# --------------------------------------------------------------------------
# 版本控制
# --------------------------------------------------------------------------

VERSION_RE = re.compile(r"^\d+(\.\d+){0,3}$")


def norm_version(v):
    return str(v or "").strip().lstrip("vV")


def blocked_versions():
    raw = CFG.get("blocked_versions") or []
    if isinstance(raw, str):
        raw = re.split(r"[,\s;]+", raw)
    return [norm_version(x) for x in raw if norm_version(x)]


def version_blocked(version):
    """是否拒绝该版本。
    未配置任何黑名单时不限制；一旦配置了黑名单，无法识别版本的旧 APP 一律拒绝，
    否则旧版本不上报版本号即可绕过封禁。
    """
    v = norm_version(version)
    if not v:
        return bool(blocked_versions())
    return v in blocked_versions()


def parse_version_list(text):
    """把后台输入的逗号/换行分隔文本解析为版本号列表，并校验格式。"""
    parts = [norm_version(p) for p in re.split(r"[,\s;]+", text or "") if norm_version(p)]
    for p in parts:
        if not VERSION_RE.match(p):
            raise ValueError("版本号格式不正确：%s（示例：1.0 或 1.1）" % p)
    return sorted(set(parts))


# --------------------------------------------------------------------------
# APP 版本发布（应用内升级）
# --------------------------------------------------------------------------

MAX_APK_BYTES = 200 * 1024 * 1024   # 单个 APK 上限 200MB


def release_dir():
    d = resolve_path(CFG.get("release_dir") or "releases")
    os.makedirs(d, exist_ok=True)
    return d


def release_list(active_only=False):
    conn = db_connect()
    try:
        sql = "SELECT * FROM app_releases"
        if active_only:
            sql += " WHERE active=1"
        sql += " ORDER BY version_code DESC"
        return conn.execute(sql).fetchall()
    finally:
        conn.close()


def release_latest():
    """当前对外发布的最新版本（启用的记录中 version_code 最大者）。"""
    conn = db_connect()
    try:
        return conn.execute(
            "SELECT * FROM app_releases WHERE active=1"
            " ORDER BY version_code DESC LIMIT 1").fetchone()
    finally:
        conn.close()


def release_add(version_code, version_name, filename, size, sha256, changelog):
    conn = db_connect()
    try:
        conn.execute(
            "INSERT INTO app_releases(version_code,version_name,filename,size,sha256,"
            "changelog,active,created_at) VALUES(?,?,?,?,?,?,1,?)",
            (int(version_code), version_name, filename, int(size), sha256,
             changelog or "", now_ts()),
        )
        conn.commit()
    except sqlite3.IntegrityError:
        raise ValueError("版本序号 %s 已存在，请确认是否重复上传" % version_code)
    finally:
        conn.close()


def release_set_active(rid, active):
    conn = db_connect()
    try:
        conn.execute("UPDATE app_releases SET active=? WHERE id=?",
                     (1 if active else 0, int(rid)))
        conn.commit()
    finally:
        conn.close()


def release_delete(rid):
    """删除记录及对应 APK 文件，返回被删除的记录（不存在返回 None）。"""
    conn = db_connect()
    try:
        row = conn.execute("SELECT * FROM app_releases WHERE id=?", (int(rid),)).fetchone()
        if row is None:
            return None
        conn.execute("DELETE FROM app_releases WHERE id=?", (int(rid),))
        conn.commit()
    finally:
        conn.close()
    try:
        path = os.path.join(release_dir(), os.path.basename(row["filename"]))
        if os.path.isfile(path):
            os.remove(path)
    except OSError:
        pass
    return row


def fmt_size(n):
    n = float(n or 0)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return ("%.0f %s" % (n, unit)) if unit == "B" else ("%.1f %s" % (n, unit))
        n /= 1024.0
    return "%.1f GB" % n


def file_sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


class MultipartError(ValueError):
    pass


class MultipartReader:
    """流式解析 multipart/form-data（仅支持本项目上传表单的简单结构）。

    文件部分边收边写磁盘，避免大 APK 占用内存；字段部分限制在 64KB 内。
    """

    def __init__(self, rfile, boundary, total):
        self.rfile = rfile
        self.delim = b"--" + boundary
        self.left = total
        self.buf = b""

    def _fill(self):
        if self.left <= 0:
            return False
        chunk = self.rfile.read(min(65536, self.left))
        if not chunk:
            self.left = 0
            return False
        self.left -= len(chunk)
        self.buf += chunk
        return True

    def _ensure(self, n):
        while len(self.buf) < n:
            if not self._fill():
                raise MultipartError("表单数据不完整")

    def _readline(self, limit=8192):
        while b"\r\n" not in self.buf:
            if len(self.buf) > limit:
                raise MultipartError("表单头部过长")
            if not self._fill():
                raise MultipartError("表单数据不完整")
        line, self.buf = self.buf.split(b"\r\n", 1)
        return line

    def _until_boundary(self, write=None, limit=None):
        """读到分隔符为止。write 非空时写入磁盘并返回字节数，否则返回内容。"""
        parts = [] if write is None else None
        size = 0
        while True:
            idx = self.buf.find(self.delim)
            if idx >= 0:
                data = self.buf[:idx]
                if data.endswith(b"\r\n"):
                    data = data[:-2]
                if write is None:
                    parts.append(data)
                else:
                    write(data)
                size += len(data)
                self.buf = self.buf[idx + len(self.delim):]
                return b"".join(parts) if write is None else size
            keep = len(self.delim) - 1
            if len(self.buf) > keep:
                data = self.buf[:-keep]
                if write is None:
                    parts.append(data)
                else:
                    write(data)
                size += len(data)
                self.buf = self.buf[-keep:]
            if limit is not None and size > limit:
                raise MultipartError("表单字段过长")
            if not self._fill():
                raise MultipartError("表单数据不完整")

    def parse(self, file_saver):
        """返回 (fields, saved)；saved=(原始文件名, 临时路径, 字节数)。"""
        while True:
            idx = self.buf.find(self.delim)
            if idx >= 0:
                self.buf = self.buf[idx + len(self.delim):]
                break
            if len(self.buf) > 65536:
                raise MultipartError("表单格式错误")
            if not self._fill():
                raise MultipartError("表单数据不完整")
        fields = {}
        saved = None
        while True:
            self._ensure(2)
            if self.buf.startswith(b"--"):
                break
            if self.buf.startswith(b"\r\n"):
                self.buf = self.buf[2:]
            else:
                raise MultipartError("表单分隔符错误")
            name = ""
            filename = ""
            while True:
                line = self._readline()
                if not line:
                    break
                text = line.decode("utf-8", "ignore")
                if text.lower().startswith("content-disposition"):
                    m = re.search(r'name="([^"]*)"', text)
                    if m:
                        name = m.group(1)
                    m = re.search(r'filename="([^"]*)"', text)
                    if m:
                        filename = m.group(1)
            if filename:
                path = file_saver(filename)
                with open(path, "wb") as f:
                    size = self._until_boundary(write=f.write)
                saved = (filename, path, size)
            else:
                value = self._until_boundary(limit=65536)
                fields[name] = value.decode("utf-8", "ignore").strip()
        return fields, saved


# --------------------------------------------------------------------------
# 令牌
# --------------------------------------------------------------------------

def token_issue(uid, client="", client_version=""):
    token = rnd_hex(32)
    issued = now_ts()
    expires = issued + int(CFG["token_days"]) * 86400
    conn = db_connect()
    try:
        conn.execute(
            "INSERT INTO tokens(token,user_id,issued_at,expires_at,revoked,client,client_version)"
            " VALUES(?,?,?,?,0,?,?)",
            (token, uid, issued, expires, (client or "")[:120], norm_version(client_version)),
        )
        conn.execute("UPDATE users SET last_login=? WHERE id=?", (issued, uid))
        conn.execute("DELETE FROM tokens WHERE expires_at < ?", (issued - 86400,))
        conn.commit()
    finally:
        conn.close()
    return token, expires


def token_revoke(token):
    conn = db_connect()
    try:
        conn.execute("UPDATE tokens SET revoked=1 WHERE token=?", (token,))
        conn.commit()
    finally:
        conn.close()


def token_client_version(token):
    conn = db_connect()
    try:
        row = conn.execute("SELECT client_version FROM tokens WHERE token=?", (token,)).fetchone()
        return norm_version(row["client_version"]) if row else ""
    finally:
        conn.close()


def token_touch_version(token, version):
    """把令牌记录的使用版本同步为本次上报值。
    APP 升级后无需重新登录，后台即可看到真实版本；同时避免降级后仍按登录时版本判定封禁。
    """
    v = norm_version(version)
    if not token or not v:
        return
    conn = db_connect()
    try:
        conn.execute("UPDATE tokens SET client_version=? WHERE token=? AND client_version<>?",
                     (v, token, v))
        conn.commit()
    finally:
        conn.close()


def token_check(token):
    """返回 (code, user_row, expires_at)。
    code: OK / TOKEN_INVALID / TOKEN_EXPIRED / PENDING / REJECTED / DISABLED
    """
    if not token:
        return "TOKEN_INVALID", None, 0
    conn = db_connect()
    try:
        row = conn.execute("SELECT * FROM tokens WHERE token=?", (token,)).fetchone()
        if row is None or row["revoked"]:
            return "TOKEN_INVALID", None, 0
        if int(row["expires_at"]) < now_ts():
            return "TOKEN_EXPIRED", None, int(row["expires_at"])
        u = conn.execute("SELECT * FROM users WHERE id=?", (row["user_id"],)).fetchone()
        if u is None:
            return "TOKEN_INVALID", None, 0
        st = u["status"]
        if st == ST_PENDING:
            return "PENDING", u, int(row["expires_at"])
        if st == ST_REJECTED:
            return "REJECTED", u, int(row["expires_at"])
        if st != ST_ACTIVE:
            return "DISABLED", u, int(row["expires_at"])
        return "OK", u, int(row["expires_at"])
    finally:
        conn.close()


DENY_TEXT = {
    "PENDING": "账号待管理员审核，请稍后再试",
    "REJECTED": "注册申请已被拒绝，请联系管理员",
    "DISABLED": "该账号已被停用，请联系管理员",
    "TOKEN_EXPIRED": "登录已过期，请重新登录",
    "TOKEN_INVALID": "登录状态无效，请重新登录",
}


# --------------------------------------------------------------------------
# 公告
# --------------------------------------------------------------------------

def announce_create(title, content, target_username=""):
    title = (title or "").strip()
    content = (content or "").strip()
    if not title or len(title) > 120:
        raise ValueError("公告标题不能为空且不超过 120 字")
    if not content or len(content) > 3000:
        raise ValueError("公告内容不能为空且不超过 3000 字")
    target_id = None
    if target_username:
        u = user_find(target_username)
        if u is None:
            raise ValueError("目标账号不存在：" + target_username)
        target_id = int(u["id"])
    conn = db_connect()
    try:
        conn.execute(
            "INSERT INTO announcements(title,content,target_user_id,active,created_at)"
            " VALUES(?,?,?,1,?)",
            (title, content, target_id, now_ts()),
        )
        conn.commit()
    finally:
        conn.close()


def announce_public():
    """登录前可见的公告：仅面向全体用户的生效公告。"""
    conn = db_connect()
    try:
        return conn.execute(
            "SELECT * FROM announcements WHERE active=1 AND target_user_id IS NULL"
            " ORDER BY id DESC LIMIT 10"
        ).fetchall()
    finally:
        conn.close()


def announce_for_user(uid):
    conn = db_connect()
    try:
        rows = conn.execute(
            "SELECT * FROM announcements WHERE active=1"
            " AND (target_user_id IS NULL OR target_user_id=?)"
            " ORDER BY id DESC LIMIT 20",
            (uid,),
        ).fetchall()
        return rows
    finally:
        conn.close()


def announce_toggle(aid, active):
    conn = db_connect()
    try:
        conn.execute("UPDATE announcements SET active=? WHERE id=?", (1 if active else 0, aid))
        conn.commit()
    finally:
        conn.close()


def announce_delete(aid):
    conn = db_connect()
    try:
        conn.execute("DELETE FROM announcements WHERE id=?", (aid,))
        conn.commit()
    finally:
        conn.close()


# --------------------------------------------------------------------------
# 限流
# --------------------------------------------------------------------------

def _limit_hit(store, ip, window, maximum):
    """返回 True 表示该 IP 已达限流上限。

    同时清理窗口外的过期时间戳；若清理后列表为空则删除该 IP 键，
    防止大量不同 IP 导致字典无限增长（内存泄漏）。
    """
    with _rate_lock:
        ts = store.get(ip)
        if not ts:
            return False
        cutoff = now_ts() - window
        ts[:] = [t for t in ts if t >= cutoff]
        if not ts:
            store.pop(ip, None)
            return False
        return len(ts) >= maximum


def rate_limited(ip):
    return _limit_hit(_login_fails, ip, LOGIN_WINDOW, LOGIN_MAX_FAILS)


def rate_record_fail(ip):
    with _rate_lock:
        _login_fails.setdefault(ip, []).append(now_ts())


def rate_clear(ip):
    with _rate_lock:
        _login_fails.pop(ip, None)


def register_limited(ip):
    return _limit_hit(_register_stamps, ip, REGISTER_WINDOW, REGISTER_MAX)


def register_record(ip):
    with _rate_lock:
        _register_stamps.setdefault(ip, []).append(now_ts())


# --------------------------------------------------------------------------
# HTTP 处理
# --------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "switch-auth/1.1"
    sys_version = ""

    # 单连接读写超时（秒）：慢客户端超过此时长无数据则断开，释放工作线程
    timeout = 30

    def setup(self):
        """带超时的 SSL 握手，防止慢握手阻塞工作线程。"""
        super().setup()
        if isinstance(self.connection, ssl.SSLSocket):
            self.connection.settimeout(10)
            try:
                self.connection.do_handshake()
            except (ssl.SSLError, OSError):
                # 握手失败：直接关闭连接，不进入 handle()
                self.close_connection = True
                return
            # 握手成功后恢复读写超时
            self.connection.settimeout(self.timeout)

    # ---------------- 通用输出 ----------------

    def _client_ip(self):
        fwd = self.headers.get("X-Forwarded-For", "")
        if fwd:
            return fwd.split(",")[0].strip()
        return self.client_address[0] if self.client_address else ""

    def _json(self, obj, status=200):
        body = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        ts = str(now_ts())
        sig = sign(CFG["app_secret"], ts + "\n" + body.decode("utf-8"))
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Server-Time", ts)
        self.send_header("X-Signature", sig)
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def _html(self, html, status=200, cookies=None):
        body = html.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        for c in cookies or []:
            self.send_header("Set-Cookie", c)
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n <= 0 or n > 65536:
            return {}
        raw = self.rfile.read(n)
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    def _read_form(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n <= 0 or n > 65536:
            return {}
        raw = self.rfile.read(n).decode("utf-8", "ignore")
        data = {}
        for k, v in parse_qs(raw, keep_blank_values=True).items():
            data[k] = v[0]
        return data

    def log_message(self, fmt, *args):
        if CFG.get("log_requests", True):
            sys.stderr.write("[%s] %s %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), self._client_ip(), fmt % args))

    # ---------------- 路由 ----------------

    def do_GET(self):
        try:
            self._route_get()
        except Exception as e:
            self._safe_error(e)

    def do_POST(self):
        try:
            self._route_post()
        except Exception as e:
            self._safe_error(e)

    def do_HEAD(self):
        try:
            if urlparse(self.path).path == "/download/app.apk":
                self._serve_apk(True)
            else:
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
        except Exception as e:
            sys.stderr.write("[switch-auth] head error: %r\n" % (e,))

    def _safe_error(self, e):
        sys.stderr.write("[switch-auth] handler error: %r\n" % (e,))
        try:
            self._json({"ok": False, "code": "SERVER_ERROR", "error": "服务内部错误"}, 500)
        except Exception:
            pass

    def _route_get(self):
        path = urlparse(self.path).path
        # 去掉末尾斜杠（根路径保留），避免 /api/app/version/ 这类末尾斜杠导致 404
        if len(path) > 1 and path.endswith("/"):
            path = path.rstrip("/")
        if path == "/favicon.ico":
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if path == "/api/ping":
            self._json({
                "ok": True,
                "service": "switch-auth",
                "register_mode": CFG["register_mode"],
                "time": now_ts(),
            })
            return
        if path == "/api/public/announcements":
            self._api_public_announcements()
            return
        if path == "/api/app/version":
            self._api_app_version()
            return
        if path == "/download/app.apk":
            self._serve_apk(False)
            return
        if path == "/admin":
            self._admin_page()
            return
        if path == "/":
            self._html("<meta charset=utf-8><p>switch-auth 服务运行中。管理入口：<a href='/admin'>/admin</a></p>")
            return
        self._json({"ok": False, "code": "NOT_FOUND",
                    "error": "接口不存在：" + path}, 404)

    def _route_post(self):
        path = urlparse(self.path).path
        # 去掉末尾斜杠，避免 /api/login/ 这类末尾斜杠导致 404
        if len(path) > 1 and path.endswith("/"):
            path = path.rstrip("/")
        if path == "/api/register":
            self._api_register()
            return
        if path == "/api/login":
            self._api_login()
            return
        if path == "/api/verify":
            self._api_verify()
            return
        if path == "/api/announcements":
            self._api_announcements()
            return
        if path == "/api/logout":
            self._api_logout()
            return
        if path == "/admin/login":
            self._admin_login_submit()
            return
        if path == "/admin/logout":
            self._admin_logout()
            return
        if path == "/admin/action":
            self._admin_action()
            return
        if path == "/admin/upload":
            self._admin_upload()
            return
        self._json({"ok": False, "code": "NOT_FOUND",
                    "error": "接口不存在：" + path}, 404)

    # ---------------- 业务接口 ----------------

    def _api_register(self):
        ip = self._client_ip()
        mode = CFG["register_mode"]
        if mode == RM_AUTO_REJECT:
            self._json({
                "ok": False, "code": "REGISTER_CLOSED",
                "error": "当前未开放注册，请联系管理员开通账号",
            }, 403)
            return
        if register_limited(ip):
            self._json({"ok": False, "code": "TOO_MANY", "error": "注册请求过于频繁，请稍后再试"}, 429)
            return

        body = self._read_json()
        username = str(body.get("username") or "").strip()
        password = str(body.get("password") or "")
        client = str(body.get("client") or "")
        if not username or not password:
            self._json({"ok": False, "code": "BAD_REQUEST", "error": "账号和密码不能为空"}, 400)
            return

        status = ST_ACTIVE if mode == RM_AUTO_APPROVE else ST_PENDING
        try:
            user_create(username, password, status)
        except ValueError as e:
            msg = str(e)
            if "已存在" in msg:
                self._json({"ok": False, "code": "USER_EXISTS", "error": msg}, 409)
            else:
                self._json({"ok": False, "code": "BAD_REQUEST", "error": msg}, 400)
            return

        register_record(ip)
        audit("register", username, ip, "模式=%s 状态=%s" % (mode, status))
        if status == ST_PENDING:
            self._json({
                "ok": True, "status": ST_PENDING,
                "message": "注册申请已提交，待管理员审核通过后即可使用",
            })
        else:
            self._json({
                "ok": True, "status": ST_ACTIVE,
                "message": "注册成功，可直接登录使用",
            })

    def _api_login(self):
        ip = self._client_ip()
        if rate_limited(ip):
            self._json({"ok": False, "code": "TOO_MANY", "error": "尝试过于频繁，请稍后再试"}, 429)
            return
        body = self._read_json()
        username = str(body.get("username") or "").strip()
        password = str(body.get("password") or "")
        client = str(body.get("client") or "")
        app_version = norm_version(body.get("app_version"))
        if not username or not password:
            self._json({"ok": False, "code": "BAD_REQUEST", "error": "账号和密码不能为空"}, 400)
            return

        u = user_find(username)
        if u is None or not check_password(password, u["pass_hash"], u["salt"], u["iterations"]):
            rate_record_fail(ip)
            audit("login_fail", username, ip)
            self._json({"ok": False, "code": "BAD_CREDENTIALS", "error": "账号或密码错误"}, 401)
            return

        st = u["status"]
        if st != ST_ACTIVE:
            code = "PENDING" if st == ST_PENDING else ("REJECTED" if st == ST_REJECTED else "DISABLED")
            audit("login_denied", username, ip, DENY_TEXT.get(code, ""))
            self._json({
                "ok": False, "code": code,
                "error": DENY_TEXT.get(code, "账号不可用"),
                "status": st,
            }, 403)
            return

        if version_blocked(app_version):
            audit("login_version_blocked", username, ip, "版本=" + (app_version or "未知"))
            self._json({
                "ok": False, "code": "VERSION_BLOCKED",
                "app_version": app_version,
                "error": "当前 APP 版本（%s）无法使用，请安装最新版本" % (app_version or "未知"),
            }, 403)
            return

        rate_clear(ip)
        token, expires = token_issue(int(u["id"]), client, app_version)
        audit("login_ok", username, ip, client + " v" + app_version)
        self._json({
            "ok": True,
            "token": token,
            "username": u["username"],
            "expires_at": expires,
            "server_time": now_ts(),
        })

    def _api_public_announcements(self):
        """登录前展示的公告：无需令牌，只返回面向全体的生效公告。"""
        items = [{
            "id": int(a["id"]),
            "title": a["title"],
            "content": a["content"],
            "target": "all",
            "created_at": int(a["created_at"]),
        } for a in announce_public()]
        self._json({"ok": True, "items": items})

    def _api_app_version(self):
        """公开接口：APP 检查更新。无需令牌，保证被版本封禁的用户也能升级。"""
        row = release_latest()
        if row is None:
            self._json({
                "ok": True, "version_code": 0, "version_name": "",
                "size": 0, "sha256": "", "changelog": "",
                "published_at": 0, "url": "",
            })
            return
        self._json({
            "ok": True,
            "version_code": int(row["version_code"]),
            "version_name": row["version_name"],
            "size": int(row["size"]),
            "sha256": row["sha256"],
            "changelog": row["changelog"] or "",
            "published_at": int(row["created_at"]),
            "url": "/download/app.apk",
        })

    def _serve_apk(self, head_only=False):
        """下载最新启用版本的 APK。只服务当前发布，不暴露目录。"""
        row = release_latest()
        if row is None:
            self._json({"ok": False, "code": "NO_RELEASE", "error": "尚未发布 APP 版本"}, 404)
            return
        path = os.path.join(release_dir(), os.path.basename(row["filename"]))
        if not os.path.isfile(path):
            self._json({"ok": False, "code": "FILE_MISSING",
                        "error": "APK 文件缺失，请在后台重新上传"}, 404)
            return
        size = os.path.getsize(path)
        safe_name = re.sub(r"[^0-9A-Za-z._-]", "", norm_version(row["version_name"])) or "app"
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(size))
        self.send_header("Content-Disposition",
                         'attachment; filename="app-%s.apk"' % safe_name)
        self.send_header("X-APK-Version-Code", str(row["version_code"]))
        self.send_header("X-APK-SHA256", row["sha256"])
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if head_only:
            return
        # 大文件下载：把 socket 超时放宽到 120 秒，慢网络用户不会被误断；
        # 超时后仍会抛 socket.timeout 释放线程，防止恶意慢客户端永久占用。
        self.connection.settimeout(120)
        try:
            with open(path, "rb") as f:
                while True:
                    chunk = f.read(64 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        finally:
            self.connection.settimeout(self.timeout)

    def _api_verify(self):
        body = self._read_json()
        token = str(body.get("token") or "")
        req_version = norm_version(body.get("app_version"))
        code, u, expires = token_check(token)
        ip = self._client_ip()
        if code == "OK":
            # 版本封禁实时生效：优先按本次上报的版本判定，APP 升级/降级后立即生效
            ver = req_version or token_client_version(token)
            token_touch_version(token, req_version)
            if version_blocked(ver):
                audit("verify_version_blocked", u["username"] if u else "", ip, "版本=" + ver)
                self._json({
                    "ok": False, "code": "VERSION_BLOCKED",
                    "app_version": ver,
                    "error": "当前 APP 版本（%s）无法使用，请安装最新版本" % (ver or "未知"),
                }, 403)
                return
            self._json({
                "ok": True,
                "enabled": True,
                "username": u["username"],
                "expires_at": expires,
                "server_time": now_ts(),
            })
        elif code in ("PENDING", "REJECTED", "DISABLED"):
            audit("verify_denied", u["username"] if u else "", ip, DENY_TEXT.get(code, ""))
            self._json({
                "ok": True, "enabled": False, "code": code,
                "status": u["status"] if u else "",
                "error": DENY_TEXT.get(code, "账号不可用"),
                "server_time": now_ts(),
            })
        elif code == "TOKEN_EXPIRED":
            self._json({
                "ok": False, "code": "TOKEN_EXPIRED",
                "error": DENY_TEXT["TOKEN_EXPIRED"],
                "server_time": now_ts(),
            }, 401)
        else:
            self._json({
                "ok": False, "code": "TOKEN_INVALID",
                "error": DENY_TEXT["TOKEN_INVALID"],
                "server_time": now_ts(),
            }, 401)

    def _api_announcements(self):
        body = self._read_json()
        token = str(body.get("token") or "")
        code, u, _ = token_check(token)
        if code != "OK":
            self._json({
                "ok": False, "code": code,
                "error": DENY_TEXT.get(code, "登录状态无效，请重新登录"),
                "server_time": now_ts(),
            }, 401 if code in ("TOKEN_INVALID", "TOKEN_EXPIRED") else 403)
            return
        items = []
        for a in announce_for_user(int(u["id"])):
            items.append({
                "id": int(a["id"]),
                "title": a["title"],
                "content": a["content"],
                "target": "all" if a["target_user_id"] is None else "me",
                "created_at": int(a["created_at"]),
            })
        self._json({"ok": True, "items": items, "server_time": now_ts()})

    def _api_logout(self):
        body = self._read_json()
        token = str(body.get("token") or "")
        code, u, _ = token_check(token)
        if code in ("OK", "PENDING", "REJECTED", "DISABLED"):
            token_revoke(token)
            audit("logout", u["username"] if u else "", self._client_ip())
        self._json({"ok": True})

    # ---------------- 管理页 ----------------

    # ---------------- 管理后台会话（Cookie） ----------------
    # 不再使用 HTTP Basic：浏览器不会缓存凭据，支持真正的退出登录。

    def _cookies(self):
        out = {}
        for part in (self.headers.get("Cookie", "") or "").split(";"):
            k, _, v = part.strip().partition("=")
            if k:
                out[k] = v
        return out

    def _admin_session(self):
        token = self._cookies().get("admin_session", "")
        if not token:
            return None
        with _session_lock:
            sess = _admin_sessions.get(token)
            if not sess:
                return None
            now = now_ts()
            if (now - sess["seen"] > ADMIN_IDLE_SECONDS
                    or now - sess["created"] > ADMIN_MAX_SECONDS):
                _admin_sessions.pop(token, None)
                return None
            sess["seen"] = now
        return token

    def _admin_credentials_ok(self, username, password):
        if username != CFG.get("admin_user", "admin"):
            return False
        if not CFG.get("admin_pass_hash"):
            return False
        return check_password(password, CFG["admin_pass_hash"], CFG["admin_pass_salt"],
                              CFG["admin_pass_iterations"])

    def _session_cookie(self, token, max_age):
        secure = "; Secure" if CFG.get("_tls") else ""
        if not token:
            return "admin_session=; Path=/admin; HttpOnly; SameSite=Strict; Max-Age=0" + secure
        return ("admin_session=%s; Path=/admin; HttpOnly; SameSite=Strict; Max-Age=%d%s"
                % (token, max_age, secure))

    def _redirect(self, location, cookies=None):
        self.send_response(303)
        self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        for c in cookies or []:
            self.send_header("Set-Cookie", c)
        self.end_headers()

    def _csrf_token(self):
        ts = str(now_ts())
        return ts + "." + sign(CFG["app_secret"] + "|csrf", ts)

    def _csrf_ok(self, submitted):
        parts = (submitted or "").split(".")
        if len(parts) != 2:
            return False
        ts, sig = parts
        if not ts.isdigit():
            return False
        if abs(now_ts() - int(ts)) > 7200:
            return False
        return hmac.compare_digest(sig, sign(CFG["app_secret"] + "|csrf", ts))

    def _csrf_cookie(self, token):
        secure = "; Secure" if CFG.get("_tls") else ""
        return "csrf=%s; Path=/admin; HttpOnly; SameSite=Strict%s" % (token, secure)

    def _admin_login_page(self, err=""):
        err_html = '<div class="err">%s</div>' % escape(err) if err else ""
        self._html(ADMIN_LOGIN_HTML.replace("@@ERR@@", err_html))

    def _admin_login_submit(self):
        ip = self._client_ip()
        if _limit_hit(_admin_login_fails, ip, LOGIN_WINDOW, LOGIN_MAX_FAILS):
            self._admin_login_page("尝试次数过多，请 5 分钟后再试")
            return
        form = self._read_form()
        username = form.get("username", "")
        password = form.get("password", "")
        if not self._admin_credentials_ok(username, password):
            with _rate_lock:
                _admin_login_fails.setdefault(ip, []).append(now_ts())
            audit("admin_login_fail", username, ip, "管理后台登录失败")
            self._admin_login_page("账号或密码错误")
            return
        with _rate_lock:
            _admin_login_fails.pop(ip, None)

        now = now_ts()
        with _session_lock:
            # 顺带清理过期会话，避免长期运行后字典膨胀
            for t, s in list(_admin_sessions.items()):
                if (now - s["seen"] > ADMIN_IDLE_SECONDS
                        or now - s["created"] > ADMIN_MAX_SECONDS):
                    _admin_sessions.pop(t, None)

            token = secrets.token_urlsafe(32)
            _admin_sessions[token] = {"created": now, "seen": now}
        audit("admin_login", username, ip, "管理后台登录成功")
        self._redirect("/admin", [self._session_cookie(token, ADMIN_MAX_SECONDS)])

    def _admin_logout(self):
        token = self._cookies().get("admin_session", "")
        if token:
            with _session_lock:
                _admin_sessions.pop(token, None)
        audit("admin_logout", CFG.get("admin_user", ""), self._client_ip(), "退出管理后台")
        self._redirect("/admin", [self._session_cookie("", 0)])

    def _status_badge(self, st):
        if st == ST_ACTIVE:
            return '<span class="on">正常</span>'
        if st == ST_PENDING:
            return '<span class="pend">待审核</span>'
        if st == ST_REJECTED:
            return '<span class="rej">已拒绝</span>'
        return '<span class="off">已禁用</span>'

    def _admin_page(self, msg="", msg_kind="info"):
        if not self._admin_session():
            self._admin_login_page()
            return
        conn = db_connect()
        try:
            users = conn.execute(
                "SELECT u.*, (SELECT COUNT(*) FROM tokens t WHERE t.user_id=u.id"
                " AND t.revoked=0 AND t.expires_at>?) AS live_tokens,"
                " (SELECT t2.client_version FROM tokens t2 WHERE t2.user_id=u.id"
                " ORDER BY t2.issued_at DESC, t2.token DESC LIMIT 1) AS last_version"
                " FROM users u ORDER BY u.id", (now_ts(),)
            ).fetchall()
            logs = conn.execute("SELECT * FROM audit ORDER BY id DESC LIMIT 25").fetchall()
            anns = conn.execute("SELECT * FROM announcements ORDER BY id DESC LIMIT 30").fetchall()
        finally:
            conn.close()

        csrf = self._csrf_token()
        username_by_id = {}
        for u in users:
            username_by_id[int(u["id"])] = u["username"]
        pending = [u for u in users if u["status"] == ST_PENDING]

        # 待审核行
        pending_rows = []
        for u in pending:
            pending_rows.append("""
<tr>
  <td>{id}</td>
  <td class="mono">{name}</td>
  <td>{created}</td>
  <td class="ops">
    <form method="post" action="/admin/action" class="inline">
      <input type="hidden" name="csrf" value="{csrf}">
      <input type="hidden" name="action" value="approve">
      <input type="hidden" name="id" value="{id}">
      <button class="ok">通过</button>
    </form>
    <form method="post" action="/admin/action" class="inline">
      <input type="hidden" name="csrf" value="{csrf}">
      <input type="hidden" name="action" value="reject">
      <input type="hidden" name="id" value="{id}">
      <button class="danger">拒绝</button>
    </form>
  </td>
</tr>""".format(
                id=u["id"], name=escape(u["username"]),
                created=time.strftime("%Y-%m-%d %H:%M", time.localtime(u["created_at"])),
                csrf=csrf))

        # 用户行
        rows = []
        for u in users:
            st = u["status"]
            last = time.strftime("%Y-%m-%d %H:%M", time.localtime(u["last_login"])) if u["last_login"] else "-"
            ver = norm_version(u["last_version"]) if u["last_version"] else ""
            ver_txt = escape(ver) if ver else '<span style="color:#7b8a99">未上报</span>'
            if st == ST_ACTIVE:
                op_label, op_action, op_cls = "禁用", "disable", "danger"
            else:
                op_label, op_action, op_cls = "启用", "enable", "ok"
            rows.append("""
<tr>
  <td>{id}</td>
  <td class="mono">{name}</td>
  <td>{state}</td>
  <td class="mono">{ver}</td>
  <td>{live}</td>
  <td>{last}</td>
  <td>{created}</td>
  <td class="ops">
    <form method="post" action="/admin/action" class="inline">
      <input type="hidden" name="csrf" value="{csrf}">
      <input type="hidden" name="action" value="{opaction}">
      <input type="hidden" name="id" value="{id}">
      <button class="{opcls}">{oplabel}</button>
    </form>
    <form method="post" action="/admin/action" class="inline">
      <input type="hidden" name="csrf" value="{csrf}">
      <input type="hidden" name="action" value="reset">
      <input type="hidden" name="id" value="{id}">
      <input type="password" name="password" placeholder="新密码" class="pw">
      <button>重置密码</button>
    </form>
    <form method="post" action="/admin/action" class="inline" onsubmit="return confirm('确认删除该账号？');">
      <input type="hidden" name="csrf" value="{csrf}">
      <input type="hidden" name="action" value="delete">
      <input type="hidden" name="id" value="{id}">
      <button class="danger">删除</button>
    </form>
  </td>
</tr>""".format(
                id=u["id"], name=escape(u["username"]), state=self._status_badge(st),
                ver=ver_txt, live=u["live_tokens"], last=last,
                created=time.strftime("%Y-%m-%d", time.localtime(u["created_at"])),
                csrf=csrf, opaction=op_action, opcls=op_cls, oplabel=op_label))

        # 公告行
        ann_rows = []
        for a in anns:
            target = "全体用户" if a["target_user_id"] is None else ("仅 " + escape(
                username_by_id.get(int(a["target_user_id"]), "已删除账号")))
            state = '<span class="on">生效中</span>' if a["active"] else '<span class="off">已停用</span>'
            ann_rows.append("""
<tr>
  <td>{id}</td>
  <td>{title}</td>
  <td class="multiline">{content}</td>
  <td>{target}</td>
  <td>{state}</td>
  <td>{created}</td>
  <td class="ops">
    <form method="post" action="/admin/action" class="inline">
      <input type="hidden" name="csrf" value="{csrf}">
      <input type="hidden" name="action" value="ann_toggle">
      <input type="hidden" name="id" value="{id}">
      <input type="hidden" name="active" value="{next_active}">
      <button>{toggle_label}</button>
    </form>
    <form method="post" action="/admin/action" class="inline" onsubmit="return confirm('确认删除该公告？');">
      <input type="hidden" name="csrf" value="{csrf}">
      <input type="hidden" name="action" value="ann_delete">
      <input type="hidden" name="id" value="{id}">
      <button class="danger">删除</button>
    </form>
  </td>
</tr>""".format(
                id=a["id"], title=escape(a["title"]),
                content=nl2br(a["content"]), target=target, state=state,
                created=time.strftime("%Y-%m-%d %H:%M", time.localtime(a["created_at"])),
                csrf=csrf, next_active="0" if a["active"] else "1",
                toggle_label="停用" if a["active"] else "启用"))

        # 版本黑名单
        ver_rows = []
        for v in blocked_versions():
            ver_rows.append("""
<tr>
  <td>{v}</td>
  <td class="ops">
    <form method="post" action="/admin/action" class="inline"
          onsubmit="return confirm('确认将版本 {v} 移出黑名单？');">
      <input type="hidden" name="csrf" value="{csrf}">
      <input type="hidden" name="action" value="del_version">
      <input type="hidden" name="version" value="{v}">
      <button class="danger">移出黑名单</button>
    </form>
  </td>
</tr>""".format(v=escape(v), csrf=csrf))

        # APP 版本发布记录
        rels = release_list()
        latest_rel = next((r for r in rels if r["active"]), None)
        rel_rows = []
        for r in rels:
            is_latest = latest_rel is not None and r["id"] == latest_rel["id"]
            state = ('<span class="on">最新发布</span>' if is_latest else
                     ('<span class="on">启用</span>' if r["active"] else
                      '<span class="off">已停用</span>'))
            rel_rows.append("""
<tr>
  <td>{name} <span class="mono" style="color:#7b8a99">({code})</span></td>
  <td>{size}</td>
  <td class="multiline">{changelog}</td>
  <td>{created}</td>
  <td>{state}</td>
  <td class="ops">
    <form method="post" action="/admin/action" class="inline">
      <input type="hidden" name="csrf" value="{csrf}">
      <input type="hidden" name="action" value="release_toggle">
      <input type="hidden" name="id" value="{id}">
      <input type="hidden" name="active" value="{next_active}">
      <button>{toggle_label}</button>
    </form>
    <form method="post" action="/admin/action" class="inline"
          onsubmit="return confirm('确认删除版本 {name} 及其 APK 文件？');">
      <input type="hidden" name="csrf" value="{csrf}">
      <input type="hidden" name="action" value="release_delete">
      <input type="hidden" name="id" value="{id}">
      <button class="danger">删除</button>
    </form>
  </td>
</tr>""".format(
                id=r["id"], name=escape(r["version_name"]), code=int(r["version_code"]),
                size=fmt_size(r["size"]),
                changelog=nl2br(r["changelog"]) if r["changelog"] else '<span style="color:#a8b3bf">无</span>',
                created=time.strftime("%Y-%m-%d %H:%M", time.localtime(r["created_at"])),
                state=state, csrf=csrf,
                next_active="0" if r["active"] else "1",
                toggle_label="停用" if r["active"] else "启用"))

        # 当前生效公告
        active_rows = []
        for a in [x for x in anns if x["active"]]:
            target = "全体用户" if a["target_user_id"] is None else ("仅 " + escape(
                username_by_id.get(int(a["target_user_id"]), "已删除账号")))
            visible = "登录页 + APP 内" if a["target_user_id"] is None else "仅该用户 APP 内"
            active_rows.append("""
<tr>
  <td>{id}</td>
  <td>{title}</td>
  <td class="multiline">{content}</td>
  <td>{target}</td>
  <td>{visible}</td>
  <td>{created}</td>
  <td class="ops">
    <form method="post" action="/admin/action" class="inline" onsubmit="return confirm('确认取消该公告？取消后用户将不再看到。');">
      <input type="hidden" name="csrf" value="{csrf}">
      <input type="hidden" name="action" value="ann_cancel">
      <input type="hidden" name="id" value="{id}">
      <button class="danger">取消公告</button>
    </form>
  </td>
</tr>""".format(
                id=a["id"], title=escape(a["title"]),
                content=nl2br(a["content"]), target=target, visible=visible,
                created=time.strftime("%Y-%m-%d %H:%M", time.localtime(a["created_at"])),
                csrf=csrf))

        logrows = []
        for lg in logs:
            logrows.append("<tr><td>%s</td><td class=mono>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>" % (
                time.strftime("%m-%d %H:%M:%S", time.localtime(lg["ts"])),
                escape(lg["username"] or "-"),
                escape(lg["event"]),
                escape(lg["ip"] or "-"),
                escape((lg["detail"] or "")[:80]),
            ))

        # 注册策略选项
        mode_names = [(RM_MANUAL, "手动审核"), (RM_AUTO_REJECT, "默认拒绝"), (RM_AUTO_APPROVE, "自动通过")]
        mode_html = []
        for val, label in mode_names:
            checked = " checked" if CFG["register_mode"] == val else ""
            mode_html.append('<label class="radio"><input type="radio" name="mode" value="%s"%s> %s</label>'
                             % (val, checked, label))

        # 公告目标选项
        target_opts = ['<option value="">全体用户</option>']
        for u in users:
            if u["status"] == ST_ACTIVE:
                target_opts.append('<option value="%s">%s</option>'
                                   % (escape(u["username"]), escape(u["username"])))

        banner = ""
        if msg:
            banner = '<div class="msg %s">%s</div>' % (msg_kind, escape(msg))

        st_counts = {
            "total": len(users),
            "active": sum(1 for u in users if u["status"] == ST_ACTIVE),
            "pending": len(pending),
        }

        html = ADMIN_HTML
        html = html.replace("@@BANNER@@", banner)
        html = html.replace("@@ADMIN@@", escape(CFG.get("admin_user", "admin")))
        html = html.replace("@@COUNT_TOTAL@@", str(st_counts["total"]))
        html = html.replace("@@COUNT_ACTIVE@@", str(st_counts["active"]))
        html = html.replace("@@COUNT_PENDING@@", str(st_counts["pending"]))
        html = html.replace("@@MODE_OPTIONS@@", "".join(mode_html))
        html = html.replace("@@CSRF@@", csrf)
        html = html.replace("@@PENDING_ROWS@@", "".join(pending_rows))
        html = html.replace("@@PENDING_CARD_CLASS@@", "" if pending else "hide")
        html = html.replace("@@USER_ROWS@@", "".join(rows) or '<tr><td colspan="7" class="empty">暂无用户</td></tr>')
        html = html.replace("@@TARGET_OPTIONS@@", "".join(target_opts))
        html = html.replace("@@ANN_ROWS@@", "".join(ann_rows) or '<tr><td colspan="7" class="empty">暂无公告</td></tr>')
        html = html.replace("@@ACTIVE_ANN_ROWS@@", "".join(active_rows)
                            or '<tr><td colspan="7" class="empty">当前没有生效中的公告</td></tr>')
        html = html.replace("@@VERSION_ROWS@@", "".join(ver_rows)
                            or '<tr><td colspan="2" class="empty">未设置黑名单，当前不限制任何版本</td></tr>')
        html = html.replace("@@RELEASE_ROWS@@", "".join(rel_rows)
                            or '<tr><td colspan="6" class="empty">尚未发布任何 APP 版本</td></tr>')
        html = html.replace("@@LOG_ROWS@@", "".join(logrows) or '<tr><td colspan="5" class="empty">暂无记录</td></tr>')
        self._html(html, cookies=[self._csrf_cookie(csrf)])

    def _admin_upload(self):
        """后台 multipart 上传 APK 并发布新版本。"""
        if not self._admin_session():
            self._admin_login_page("登录已过期，请重新登录")
            return
        ctype = self.headers.get("Content-Type", "")
        m = re.search(r'boundary="?([^";]+)"?', ctype)
        if not ctype.lower().startswith("multipart/form-data") or not m:
            self._admin_page("上传请求格式不正确", "err")
            return
        try:
            total = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            total = 0
        if total <= 0:
            self._admin_page("上传内容为空", "err")
            return
        if total > MAX_APK_BYTES + 1024 * 1024:
            self._admin_page("APK 超过大小上限（%d MB）" % (MAX_APK_BYTES // 1024 // 1024), "err")
            return

        tmp_dir = os.path.join(release_dir(), ".tmp")
        os.makedirs(tmp_dir, exist_ok=True)
        tmp_path = os.path.join(tmp_dir, "upload-%s.apk" % rnd_hex(8))
        ip = self._client_ip()

        def saver(filename):
            if not filename.lower().endswith(".apk"):
                raise MultipartError("只能上传 .apk 文件")
            return tmp_path

        # 大文件上传：放宽 socket 超时到 300 秒（200MB APK 慢网络可能需要数分钟），
        # 超时后自动断开释放线程，防止恶意慢上传永久占用连接。
        self.connection.settimeout(300)
        try:
            fields, saved = MultipartReader(
                self.rfile, m.group(1).encode("utf-8"), total).parse(saver)
        except MultipartError as e:
            self._cleanup(tmp_path)
            self.connection.settimeout(self.timeout)
            self._admin_page(str(e), "err")
            return
        except Exception as e:
            self._cleanup(tmp_path)
            self.connection.settimeout(self.timeout)
            self._admin_page("上传失败：%s" % e, "err")
            return
        finally:
            self.connection.settimeout(self.timeout)

        try:
            if not self._csrf_ok(fields.get("csrf", "")):
                self._admin_page("操作已过期，请刷新页面后重试", "err")
                return
            raw_code = (fields.get("version_code") or "").strip()
            if not raw_code.isdigit() or int(raw_code) <= 0:
                self._admin_page("版本序号必须是大于 0 的整数（取自 build.gradle 的 versionCode）", "err")
                return
            version_code = int(raw_code)
            version_name = norm_version(fields.get("version_name"))
            if not VERSION_RE.match(version_name):
                self._admin_page("版本名格式不正确（示例：1.2）", "err")
                return
            changelog = (fields.get("changelog") or "").strip()[:2000]
            if not saved or saved[2] <= 0:
                self._admin_page("请选择要上传的 APK 文件", "err")
                return

            conn = db_connect()
            try:
                dup = conn.execute("SELECT 1 FROM app_releases WHERE version_code=?",
                                   (version_code,)).fetchone()
            finally:
                conn.close()
            if dup:
                self._admin_page("版本序号 %s 已存在，如需替换请先删除旧记录" % version_code, "err")
                return

            size = saved[2]
            sha = file_sha256(tmp_path)
            filename = "app-%d.apk" % version_code
            os.replace(tmp_path, os.path.join(release_dir(), filename))
            try:
                release_add(version_code, version_name, filename, size, sha, changelog)
            except ValueError as e:
                self._cleanup(os.path.join(release_dir(), filename))
                self._admin_page(str(e), "err")
                return
            audit("release_add", CFG.get("admin_user", ""), ip,
                  "版本=%s(%d) 大小=%s" % (version_name, version_code, fmt_size(size)))
            self._admin_page(
                "已发布版本 %s（序号 %d，%s），APP 更新接口立即生效" %
                (version_name, version_code, fmt_size(size)), "ok")
        finally:
            self._cleanup(tmp_path)

    @staticmethod
    def _cleanup(path):
        try:
            if path and os.path.isfile(path):
                os.remove(path)
        except OSError:
            pass

    def _admin_action(self):
        if not self._admin_session():
            self._admin_login_page("登录已过期，请重新登录")
            return
        form = self._read_form()
        if not self._csrf_ok(form.get("csrf")):
            self._admin_page("操作已过期，请刷新页面后重试", "err")
            return
        action = form.get("action", "")
        ip = self._client_ip()
        try:
            if action == "add":
                name = form.get("username", "")
                user_create(name, form.get("password", ""), ST_ACTIVE)
                audit("admin_add", name, ip, "管理员新增账号")
                self._admin_page("账号已创建：" + name, "ok")
            elif action == "approve":
                self._set_status(form, ST_ACTIVE, "通过审核", ip)
            elif action == "reject":
                self._set_status(form, ST_REJECTED, "拒绝注册", ip)
            elif action == "enable":
                self._set_status(form, ST_ACTIVE, "启用", ip)
            elif action == "disable":
                self._set_status(form, ST_DISABLED, "禁用", ip)
            elif action == "reset":
                uid = int(form.get("id", "0"))
                u = user_by_id(uid)
                if u is None:
                    self._admin_page("账号不存在", "err")
                    return
                user_reset_password(uid, form.get("password", ""))
                audit("admin_reset", u["username"], ip, "重置密码")
                self._admin_page("已重置密码：" + u["username"], "ok")
            elif action == "delete":
                uid = int(form.get("id", "0"))
                u = user_by_id(uid)
                if u is None:
                    self._admin_page("账号不存在", "err")
                    return
                user_delete(uid)
                audit("admin_delete", u["username"], ip, "删除账号")
                self._admin_page("已删除账号：" + u["username"], "ok")
            elif action == "set_mode":
                mode = form.get("mode", "")
                if mode not in REGISTER_MODES:
                    self._admin_page("无效的注册策略", "err")
                    return
                CFG["register_mode"] = mode
                save_config()
                label = {RM_MANUAL: "手动审核", RM_AUTO_REJECT: "默认拒绝", RM_AUTO_APPROVE: "自动通过"}[mode]
                audit("admin_mode", CFG.get("admin_user", ""), ip, "注册策略=" + label)
                self._admin_page("注册策略已更新：" + label, "ok")
            elif action == "ann_add":
                announce_create(form.get("title", ""), form.get("content", ""),
                                form.get("target", ""))
                audit("admin_announce", CFG.get("admin_user", ""), ip,
                      "发布公告：" + form.get("title", ""))
                self._admin_page("公告已发布", "ok")
            elif action == "ann_toggle":
                aid = int(form.get("id", "0"))
                active = form.get("active", "0") == "1"
                announce_toggle(aid, active)
                audit("admin_announce", CFG.get("admin_user", ""), ip,
                      "公告#%d %s" % (aid, "启用" if active else "停用"))
                self._admin_page("公告已%s" % ("启用" if active else "停用"), "ok")
            elif action == "ann_cancel":
                aid = int(form.get("id", "0"))
                announce_toggle(aid, False)
                audit("admin_announce", CFG.get("admin_user", ""), ip, "取消公告#%d" % aid)
                self._admin_page("公告已取消展示", "ok")
            elif action == "add_version":
                raw_v = (form.get("version") or "").strip()
                if not raw_v:
                    self._admin_page("请输入要禁止登录的版本号", "err")
                    return
                try:
                    lst = parse_version_list(raw_v)
                except ValueError as e:
                    self._admin_page(str(e), "err")
                    return
                if len(lst) != 1:
                    self._admin_page("一次只能添加一个版本号", "err")
                    return
                v = lst[0]
                cur = blocked_versions()
                if v in cur:
                    self._admin_page("版本 %s 已在黑名单中，无需重复添加" % v, "err")
                    return
                cur.append(v)
                CFG["blocked_versions"] = sorted(set(cur))
                save_config()
                audit("admin_versions", CFG.get("admin_user", ""), ip,
                      "加入黑名单=" + v)
                self._admin_page("已将版本 %s 加入黑名单，该版本立即无法登录" % v, "ok")
            elif action == "del_version":
                v = norm_version(form.get("version", ""))
                cur = blocked_versions()
                if v not in cur:
                    self._admin_page("版本 %s 不在黑名单中" % v, "err")
                    return
                CFG["blocked_versions"] = [x for x in cur if x != v]
                save_config()
                audit("admin_versions", CFG.get("admin_user", ""), ip,
                      "移出黑名单=" + v)
                self._admin_page("已将版本 %s 移出黑名单" % v, "ok")
            elif action == "release_toggle":
                rid = int(form.get("id", "0"))
                active = form.get("active", "0") == "1"
                release_set_active(rid, active)
                audit("release_toggle", CFG.get("admin_user", ""), ip,
                      "发布#%d %s" % (rid, "启用" if active else "停用"))
                self._admin_page("版本发布记录已%s" % ("启用" if active else "停用"), "ok")
            elif action == "release_delete":
                rid = int(form.get("id", "0"))
                row = release_delete(rid)
                if row is None:
                    self._admin_page("该发布记录不存在", "err")
                    return
                audit("release_delete", CFG.get("admin_user", ""), ip,
                      "删除版本 %s(%d)" % (row["version_name"], row["version_code"]))
                self._admin_page("已删除版本 %s 及其 APK 文件" % row["version_name"], "ok")
            elif action == "ann_delete":
                aid = int(form.get("id", "0"))
                announce_delete(aid)
                audit("admin_announce", CFG.get("admin_user", ""), ip, "删除公告#%d" % aid)
                self._admin_page("公告已删除", "ok")
            else:
                self._admin_page("未知操作", "err")
        except ValueError as e:
            self._admin_page(str(e), "err")
        except Exception as e:
            self._admin_page("操作失败：%s" % e, "err")

    def _set_status(self, form, status, event, ip):
        uid = int(form.get("id", "0"))
        u = user_by_id(uid)
        if u is None:
            self._admin_page("账号不存在", "err")
            return
        user_set_status(uid, status)
        audit("admin_" + status, u["username"], ip, event)
        self._admin_page("已%s账号：%s" % (event, u["username"]), "ok")


ADMIN_LOGIN_HTML = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>登录 - switch-auth 授权管理</title>
<style>
* { box-sizing: border-box; }
body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
       background:#f5f7fa; color:#243447;
       font-family:-apple-system,"Segoe UI","Microsoft YaHei",sans-serif; font-size:14px; }
.card { width:320px; background:#fff; border-radius:8px; padding:26px;
        box-shadow:0 2px 10px rgba(0,0,0,.08); }
h1 { font-size:18px; margin:0 0 4px; color:#1a6fb5; }
.sub { color:#7b8a99; font-size:12px; margin-bottom:18px; }
label { display:block; font-size:12px; color:#7b8a99; margin:12px 0 4px; }
input { width:100%; padding:8px 10px; border:1px solid #d5dce3; border-radius:4px; font-size:14px; }
button { width:100%; margin-top:18px; padding:9px; border:0; border-radius:4px;
         background:#1a6fb5; color:#fff; font-size:14px; cursor:pointer; }
button:hover { background:#155d99; }
.err { background:#fdecea; color:#c0392b; padding:8px 10px; border-radius:4px;
       font-size:13px; margin-bottom:6px; }
</style></head><body>
<div class="card">
  <h1>授权管理后台</h1>
  <div class="sub">交换机查询 APP · switch-auth</div>
  @@ERR@@
  <form method="post" action="/admin/login">
    <label>管理员账号</label>
    <input type="text" name="username" value="admin" autocomplete="username" required>
    <label>密码</label>
    <input type="password" name="password" autocomplete="current-password" required autofocus>
    <button type="submit">登录</button>
  </form>
</div>
</body></html>
"""


ADMIN_HTML = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>授权管理 - switch-auth</title>
<style>
* { box-sizing: border-box; }
body { margin:0; padding:24px; background:#f5f7fa; color:#243447;
       font-family:-apple-system,"Segoe UI","Microsoft YaHei",sans-serif; font-size:14px; }
h1 { font-size:20px; margin:0 0 4px; }
.sub { color:#7b8a99; font-size:12px; margin-bottom:18px; }
.card { background:#fff; border-radius:8px; padding:18px; margin-bottom:18px;
        box-shadow:0 1px 3px rgba(0,0,0,.08); }
.card h2 { font-size:15px; margin:0 0 12px; color:#1a6fb5; }
table { width:100%; border-collapse:collapse; }
th, td { padding:8px 6px; text-align:left; border-bottom:1px solid #eef2f6; font-size:13px; }
th { color:#7b8a99; font-weight:500; background:#fafbfc; }
.mono { font-family:Consolas,monospace; }
.on { color:#12894a; font-weight:600; }
.off { color:#c0392b; font-weight:600; }
.pend { color:#d08a00; font-weight:600; }
.rej { color:#8a6d3b; font-weight:600; }
.empty { color:#a8b3bf; text-align:center; padding:16px; }
.hide { display:none; }
button { padding:5px 10px; border:1px solid #d5dce3; background:#fff; border-radius:4px;
         cursor:pointer; font-size:12px; color:#3d4c5c; }
button:hover { background:#f0f4f8; }
button.ok { border-color:#12894a; color:#12894a; }
button.danger { border-color:#c0392b; color:#c0392b; }
.inline { display:inline-block; margin:2px 4px 2px 0; }
.pw { width:88px; padding:4px 6px; border:1px solid #d5dce3; border-radius:4px; font-size:12px; }
input[type=text], input[type=password] { padding:6px 8px; border:1px solid #d5dce3;
        border-radius:4px; font-size:13px; }
textarea { width:100%; min-height:70px; padding:6px 8px; border:1px solid #d5dce3;
           border-radius:4px; font-size:13px; font-family:inherit; }
select { padding:6px 8px; border:1px solid #d5dce3; border-radius:4px; font-size:13px; }
.msg { padding:10px 12px; border-radius:6px; margin-bottom:14px; font-size:13px; }
.msg.ok { background:#e6f6ec; color:#12894a; }
.msg.err { background:#fdecea; color:#c0392b; }
.msg.info { background:#eaf3fb; color:#1a6fb5; }
.ops { white-space:nowrap; }
.multiline { max-width:320px; word-break:break-word; line-height:1.5; }
.radio { margin-right:16px; font-size:13px; }
.row { display:flex; gap:8px; align-items:center; flex-wrap:wrap; margin-bottom:10px; }
</style></head><body>
<h1>交换机查询 APP · 授权管理</h1>
<div class="sub">管理员：@@ADMIN@@ &nbsp;|&nbsp; 账号 @@COUNT_ACTIVE@@ 正常 / 共 @@COUNT_TOTAL@@ &nbsp;|&nbsp; 待审核 @@COUNT_PENDING@@ &nbsp;|&nbsp; 停用、拒绝后用户下次操作立即生效
  <form method="post" action="/admin/logout" style="float:right;margin-top:-6px">
    <button>退出登录</button>
  </form>
</div>
@@BANNER@@

<div class="card">
  <h2>注册策略</h2>
  <form method="post" action="/admin/action" class="row">
    <input type="hidden" name="csrf" value="@@CSRF@@">
    <input type="hidden" name="action" value="set_mode">
    @@MODE_OPTIONS@@
    <button class="ok">保存</button>
  </form>
  <div class="sub" style="margin:0">手动审核：注册后进入待审核列表；默认拒绝：关闭注册入口；自动通过：注册即可直接使用。</div>
</div>

<div class="card @@PENDING_CARD_CLASS@@">
  <h2>待审核注册（@@COUNT_PENDING@@）</h2>
  <table>
    <tr><th>ID</th><th>账号</th><th>申请时间</th><th>操作</th></tr>
    @@PENDING_ROWS@@
  </table>
</div>

<div class="card">
  <h2>新增账号</h2>
  <form method="post" action="/admin/action" class="row">
    <input type="hidden" name="csrf" value="@@CSRF@@">
    <input type="hidden" name="action" value="add">
    <input type="text" name="username" placeholder="账号" required>
    <input type="password" name="password" placeholder="密码（至少6位）" required>
    <button class="ok">添加</button>
  </form>
</div>

<div class="card">
  <h2>账号列表</h2>
  <table>
    <tr><th>ID</th><th>账号</th><th>状态</th><th>当前版本</th><th>在线令牌</th><th>最后登录</th><th>创建时间</th><th>操作</th></tr>
    @@USER_ROWS@@
  </table>
  <div class="sub" style="margin:0">「当前版本」为 APP 最近一次登录或校验时上报的版本，升级 APP 后无需重新登录，下次校验会自动更新。显示「未上报」说明该账号使用的是不上报版本号的旧版 APP。</div>
</div>

<div class="card">
  <h2>当前生效公告</h2>
  <table>
    <tr><th>ID</th><th>标题</th><th>内容</th><th>目标</th><th>展示位置</th><th>发布时间</th><th>操作</th></tr>
    @@ACTIVE_ANN_ROWS@@
  </table>
  <div class="sub" style="margin:0">面向全体用户的公告会同时显示在登录页和 APP 一级页面；仅指定用户的公告只在该用户登录后显示。取消后立即从 APP 中消失。</div>
</div>

<div class="card">
  <h2>发布公告</h2>
  <form method="post" action="/admin/action">
    <input type="hidden" name="csrf" value="@@CSRF@@">
    <input type="hidden" name="action" value="ann_add">
    <div class="row">
      <input type="text" name="title" placeholder="公告标题" required style="flex:1;min-width:220px">
      <select name="target">@@TARGET_OPTIONS@@</select>
    </div>
    <textarea name="content" placeholder="公告内容（用户下次打开或进入项目时在 APP 内显示）" required></textarea>
    <div class="row" style="margin-top:10px"><button class="ok">发布</button></div>
  </form>
</div>

<div class="card">
  <h2>公告列表</h2>
  <table>
    <tr><th>ID</th><th>标题</th><th>内容</th><th>目标</th><th>状态</th><th>发布时间</th><th>操作</th></tr>
    @@ANN_ROWS@@
  </table>
</div>

<div class="card">
  <h2>版本控制</h2>
  <form method="post" action="/admin/action" class="row">
    <input type="hidden" name="csrf" value="@@CSRF@@">
    <input type="hidden" name="action" value="add_version">
    <input type="text" name="version" placeholder="输入要禁止登录的版本号，例如 1.0"
           style="flex:1;min-width:240px">
    <button class="ok">添加到黑名单</button>
  </form>
  <table>
    <tr><th>禁止登录的版本</th><th style="width:140px">操作</th></tr>
    @@VERSION_ROWS@@
  </table>
  <div class="sub" style="margin:0">黑名单为空表示不限制版本。添加后立即生效：该版本无法登录，已登录用户在下一次校验时失效并提示升级。黑名单非空时，不上报版本号的旧版 APP 也会被拒绝。</div>
</div>

<div class="card">
  <h2>APP 版本发布</h2>
  <form method="post" action="/admin/upload" enctype="multipart/form-data" class="row">
    <input type="hidden" name="csrf" value="@@CSRF@@">
    <input type="file" name="apk" accept=".apk,application/vnd.android.package-archive" required
           style="flex:1;min-width:220px">
    <input type="text" name="version_code" placeholder="序号，如 3" required style="width:110px">
    <input type="text" name="version_name" placeholder="版本名，如 1.2" required style="width:130px">
    <textarea name="changelog" placeholder="更新说明（可空，支持回车换行）" style="flex:1;min-width:200px;min-height:60px"></textarea>
    <button class="ok">上传并发布</button>
  </form>
  <table>
    <tr><th>版本（序号）</th><th>大小</th><th>更新说明</th><th>发布时间</th><th>状态</th><th>操作</th></tr>
    @@RELEASE_ROWS@@
  </table>
  <div class="sub" style="margin:0">上传成功后立即成为最新版本，APP 点「APP 更新」时才会下载，不会自动下载。序号取自 build.gradle 的 versionCode，必须大于当前线上版本。下载地址：<span class="mono">/download/app.apk</span>（无需登录，被封禁版本也能升级）。</div>
</div>

<div class="card">
  <h2>最近操作记录</h2>
  <table>
    <tr><th>时间</th><th>账号</th><th>事件</th><th>IP</th><th>备注</th></tr>
    @@LOG_ROWS@@
  </table>
</div>
</body></html>"""


# --------------------------------------------------------------------------
# 命令行工具
# --------------------------------------------------------------------------

def cli_set_admin_pass():
    p1 = getpass.getpass("请输入新的管理员密码：")
    p2 = getpass.getpass("请再次输入：")
    if p1 != p2:
        print("两次输入不一致")
        return 1
    if len(p1) < 8:
        print("管理员密码至少 8 位")
        return 1
    h, s, it = make_password_hash(p1)
    CFG["admin_pass_hash"] = h
    CFG["admin_pass_salt"] = s
    CFG["admin_pass_iterations"] = it
    save_config()
    print("管理员密码已更新（仅保存哈希）")
    return 0


def cli_add_user(name):
    pw = getpass.getpass("请输入账号 %s 的密码：" % name)
    if len(pw) < 6:
        print("密码至少 6 位")
        return 1
    user_create(name, pw, ST_ACTIVE)
    print("已创建账号：%s" % name)
    return 0


def cli_list_users():
    conn = db_connect()
    try:
        rows = conn.execute("SELECT * FROM users ORDER BY id").fetchall()
    finally:
        conn.close()
    if not rows:
        print("暂无账号")
        return 0
    label = {ST_ACTIVE: "正常", ST_PENDING: "待审核", ST_REJECTED: "已拒绝", ST_DISABLED: "已禁用"}
    print("%-4s %-20s %-8s %-20s %s" % ("ID", "账号", "状态", "最后登录", "创建时间"))
    for r in rows:
        last = time.strftime("%Y-%m-%d %H:%M", time.localtime(r["last_login"])) if r["last_login"] else "-"
        print("%-4d %-20s %-8s %-20s %s" % (
            r["id"], r["username"], label.get(r["status"], r["status"]),
            last, time.strftime("%Y-%m-%d %H:%M", time.localtime(r["created_at"]))))
    return 0


def cli_set_status(name, status, word):
    u = user_find(name)
    if u is None:
        print("账号不存在：%s" % name)
        return 1
    user_set_status(int(u["id"]), status)
    print("已%s账号：%s" % (word, name))
    return 0


def cli_reset(name):
    u = user_find(name)
    if u is None:
        print("账号不存在：%s" % name)
        return 1
    pw = getpass.getpass("请输入账号 %s 的新密码：" % name)
    user_reset_password(int(u["id"]), pw)
    print("已重置密码：%s" % name)
    return 0


def main():
    ap = argparse.ArgumentParser(description="交换机查询 APP 授权服务")
    ap.add_argument("--config", default=DEFAULT_CONFIG)
    ap.add_argument("--no-tls", action="store_true", help="本地调试用：不启用 TLS（生产环境勿用）")
    ap.add_argument("--set-admin-pass", action="store_true")
    ap.add_argument("--add-user", metavar="NAME")
    ap.add_argument("--list-users", action="store_true")
    ap.add_argument("--enable-user", metavar="NAME")
    ap.add_argument("--disable-user", metavar="NAME")
    ap.add_argument("--approve-user", metavar="NAME")
    ap.add_argument("--reject-user", metavar="NAME")
    ap.add_argument("--reset-password", metavar="NAME")
    args = ap.parse_args()

    load_config(args.config)
    db_init()

    if args.set_admin_pass:
        return cli_set_admin_pass()
    if args.add_user:
        return cli_add_user(args.add_user)
    if args.list_users:
        return cli_list_users()
    if args.enable_user:
        return cli_set_status(args.enable_user, ST_ACTIVE, "启用")
    if args.disable_user:
        return cli_set_status(args.disable_user, ST_DISABLED, "禁用")
    if args.approve_user:
        return cli_set_status(args.approve_user, ST_ACTIVE, "审核通过")
    if args.reject_user:
        return cli_set_status(args.reject_user, ST_REJECTED, "拒绝")
    if args.reset_password:
        return cli_reset(args.reset_password)

    host = CFG["listen_host"]
    port = int(CFG["listen_port"])

    class LimitedThreadingHTTPServer(ThreadingHTTPServer):
        """限制并发线程数的 ThreadingHTTPServer。

        原生 ThreadingHTTPServer 对并发线程数无上限，大量慢连接（慢 SSL 握手 /
        慢下载）会让线程数无限增长，最终耗尽系统资源导致服务挂起。
        这里用信号量把并发线程数限制在 MAX_CONCURRENT_THREADS，超限的连接立即
        关闭而不是排队等待（避免内核 Recv-Q 堆积、正常用户连不上）。
        """
        daemon_threads = True
        block_on_close = False

        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            self._sem = threading.BoundedSemaphore(MAX_CONCURRENT_THREADS)

        def process_request(self, request, client_address):
            if not self._sem.acquire(blocking=False):
                # 并发已满：拒绝该连接，不占用线程
                self.shutdown_request(request)
                return
            t = threading.Thread(target=self._worker, args=(request, client_address))
            t.daemon = True
            t.start()

        def _worker(self, request, client_address):
            try:
                self.process_request_thread(request, client_address)
            finally:
                self._sem.release()

    httpd = LimitedThreadingHTTPServer((host, port), Handler)

    CFG["_tls"] = not args.no_tls
    if not args.no_tls:
        cert = resolve_path(CFG["cert_file"])
        key = resolve_path(CFG["key_file"])
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(certfile=cert, keyfile=key)
        # do_handshake_on_connect=False：不在 accept()（主线程）里做 SSL 握手，
        # 改由 Handler.setup() 在工作线程里带超时完成，避免慢握手卡死主线程。
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True,
                                       do_handshake_on_connect=False)
        print("[switch-auth] listening on %s:%d (TLS)" % (host, port))
    else:
        print("[switch-auth] listening on %s:%d (NO TLS - 仅本地调试)" % (host, port))
    print("[switch-auth] db: %s" % resolve_path(CFG["db_file"]))
    print("[switch-auth] register_mode: %s" % CFG["register_mode"])
    sys.stdout.flush()

    # 优雅关闭：响应 systemd 的 SIGTERM 和 Ctrl+C 的 SIGINT，
    # 让 serve_forever() 正常退出，正在处理的请求有机会完成。
    def _graceful_shutdown(signum, frame):
        try:
            sig_name = signal.Signals(signum).name
        except ValueError:
            sig_name = str(signum)
        sys.stderr.write("[switch-auth] received %s, shutting down gracefully...\n" % sig_name)
        # shutdown() 必须在非 serve_forever() 所在线程调用，否则主线程会死锁；
        # 这里用 daemon 线程触发，serve_forever() 收到后即退出。
        threading.Thread(target=httpd.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, _graceful_shutdown)
    signal.signal(signal.SIGINT, _graceful_shutdown)

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
        print("[switch-auth] stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
