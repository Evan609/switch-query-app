package com.wgj.switchquery.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.wgj.switchquery.R;
import com.wgj.switchquery.auth.Announcement;
import com.wgj.switchquery.auth.AuthManager;
import com.wgj.switchquery.auth.AuthResult;

import java.util.List;

/**
 * 登录界面。
 * 本地认证模式：只显示密码框（与原逻辑一致）；
 * 服务端认证模式：显示账号+密码，并提供注册入口。
 * 界面只调用 AuthManager，不感知具体认证实现。
 */
public class LoginActivity extends AppCompatActivity {

    private View tilUsername;
    private EditText etUsername, etPassword;
    private Button btnLogin, btnRegister;

    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        SystemBarInsets.applyBottom(findViewById(android.R.id.content));

        AuthManager.get().init(this);

        tilUsername = findViewById(R.id.til_username);
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        btnLogin = findViewById(R.id.btn_login);
        btnRegister = findViewById(R.id.btn_register);

        boolean remote = AuthManager.get().isRemote();
        tilUsername.setVisibility(remote ? View.VISIBLE : View.GONE);
        btnRegister.setVisibility(remote && AuthManager.get().supportsRegister()
                ? View.VISIBLE : View.GONE);

        btnLogin.setOnClickListener(v -> attemptLogin());
        btnRegister.setOnClickListener(v -> showRegisterDialog());
        findViewById(R.id.btn_update).setVisibility(remote ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_update).setOnClickListener(v -> AppUpdateEntry.check(this));

        // 登录前公告（面向全体用户，本地模式下为空）
        loadAnnouncements();

        // 已有有效令牌时自动校验并进入，避免每次启动都重复登录
        autoVerify();
    }

    private void loadAnnouncements() {
        if (!AuthManager.get().isRemote()) return;
        new Thread(() -> {
            List<Announcement> items = AuthManager.get().publicAnnouncements();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                AnnouncementCenter.render(findViewById(R.id.announcement_box),
                        findViewById(R.id.tv_ann_content), this, items);
            });
        }).start();
    }

    private void autoVerify() {
        if (!AuthManager.get().isRemote() || !AuthManager.get().hasToken()) return;
        setBusy(true, "正在验证登录状态…");
        new Thread(() -> {
            AuthResult r = AuthManager.get().verify();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (r.ok) {
                    enterApp();
                } else {
                    setBusy(false, null);
                    if (!r.message.isEmpty()) {
                        Toast.makeText(this, r.message, Toast.LENGTH_LONG).show();
                    }
                    offerUpdateIfBlocked(r);
                }
            });
        }).start();
    }

    private void attemptLogin() {
        if (busy) return;
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString();
        if (AuthManager.get().isRemote() && TextUtils.isEmpty(username)) {
            Toast.makeText(this, R.string.login_username_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(password)) {
            Toast.makeText(this, R.string.login_hint, Toast.LENGTH_SHORT).show();
            return;
        }

        setBusy(true, "认证中…");
        new Thread(() -> {
            AuthResult r = AuthManager.get().login(username, password);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                setBusy(false, null);
                if (r.ok) {
                    enterApp();
                } else {
                    Toast.makeText(this,
                            r.message.isEmpty() ? getString(R.string.login_error) : r.message,
                            Toast.LENGTH_LONG).show();
                    etPassword.setText("");
                    offerUpdateIfBlocked(r);
                }
            });
        }).start();
    }

    /** 版本被封禁时提供更新入口：此时账号无法登录，但仍可下载安装新版本。 */
    private void offerUpdateIfBlocked(AuthResult r) {
        if (!AuthResult.VERSION_BLOCKED.equals(r.code)) return;
        new AlertDialog.Builder(this)
                .setTitle("需要更新")
                .setMessage(r.message.isEmpty()
                        ? "当前版本已停止使用，请更新到最新版本" : r.message)
                .setPositiveButton("立即更新", (d, w) -> AppUpdateEntry.check(this))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showRegisterDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText etUser = new EditText(this);
        etUser.setHint(R.string.login_username_hint);
        etUser.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(etUser);

        EditText etPass = new EditText(this);
        etPass.setHint(R.string.login_hint);
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(etPass);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.register_title)
                .setMessage(R.string.register_hint)
                .setView(box)
                .setPositiveButton("提交", null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String u = etUser.getText().toString().trim();
                    String p = etPass.getText().toString();
                    if (u.length() < 3 || p.length() < 3) {
                        Toast.makeText(this, R.string.register_hint, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    submitRegister(dlg, u, p);
                }));
        dlg.show();
    }

    private void submitRegister(AlertDialog dlg, String username, String password) {
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        new Thread(() -> {
            AuthResult r = AuthManager.get().register(username, password);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                if (r.ok) {
                    dlg.dismiss();
                    if (AuthResult.PENDING.equals(r.registerStatus)) {
                        new AlertDialog.Builder(this)
                                .setTitle("注册已提交")
                                .setMessage(r.message.isEmpty()
                                        ? "注册申请已提交，待管理员审核通过后即可使用"
                                        : r.message)
                                .setPositiveButton(R.string.announcement_known, null)
                                .show();
                    } else {
                        etUsername.setText(username);
                        etPassword.setText("");
                        Toast.makeText(this, r.message.isEmpty() ? "注册成功，请登录" : r.message,
                                Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(this,
                            r.message.isEmpty() ? "注册失败" : r.message,
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void setBusy(boolean b, String buttonText) {
        busy = b;
        btnLogin.setEnabled(!b);
        btnRegister.setEnabled(!b);
        btnLogin.setText(buttonText == null ? getString(R.string.login_submit) : buttonText);
    }

    private void enterApp() {
        startActivity(new Intent(this, ProjectSelectActivity.class));
        finish();
    }
}
