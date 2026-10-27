package com.wgj.switchquery.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wgj.switchquery.App;
import com.wgj.switchquery.R;
import com.wgj.switchquery.auth.Announcement;
import com.wgj.switchquery.auth.AuthManager;
import com.wgj.switchquery.auth.AuthResult;
import com.wgj.switchquery.model.Project;
import com.wgj.switchquery.model.SwitchDevice;
import com.wgj.switchquery.storage.ProjectStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 项目选择界面。展示所有网络项目，点击进入项目详情（核心+节点列表）。
 */
public class ProjectSelectActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private ProjectAdapter adapter;
    private ProjectStore store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_project_select);
        SystemBarInsets.applyBottom(findViewById(android.R.id.content));

        store = new ProjectStore(this);
        recyclerView = findViewById(R.id.rv_projects);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProjectAdapter();
        recyclerView.setAdapter(adapter);

        // 安全退出：断开全部交换机连接、注销登录令牌后退出应用
        findViewById(R.id.btn_back).setOnClickListener(v -> {
            new Thread(() -> {
                App.get().getConnectionManager().disconnectAll();
                AuthManager.get().logout();
            }).start();
            finishAffinity();
        });

        // APP 更新（仅服务端认证模式显示）
        findViewById(R.id.btn_update).setVisibility(
                AuthManager.get().isRemote() ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_update).setOnClickListener(v -> AppUpdateEntry.check(this));

        // 显示 APP 版本号（页面底部，安全退出按钮下方）
        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((android.widget.TextView) findViewById(R.id.tvAppVersion)).setText("v" + ver);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        adapter.setProjects(store.getProjects());
        checkAuthorization();
    }

    /**
     * 进入一级页面时校验授权并拉取公告。
     * 本地认证模式下 verify 恒为通过、公告为空，不会产生任何网络请求。
     */
    private void checkAuthorization() {
        new Thread(() -> {
            AuthResult r = AuthManager.get().verify();
            if (!r.ok) {
                if (r.isDenied()) {
                    // 服务端明确拒绝（停用/删除/过期）：退回登录页
                    runOnUiThread(() -> forceRelogin(r.message));
                } else {
                    // 网络或服务器异常：仅提示，令牌与登录状态保持不变
                    runOnUiThread(() -> Toast.makeText(this,
                            r.message.isEmpty() ? "网络异常，授权校验失败，请稍后重试" : r.message,
                            Toast.LENGTH_LONG).show());
                }
                return;
            }
            List<Announcement> items = AuthManager.get().announcements();
            runOnUiThread(() -> AnnouncementCenter.render(
                    findViewById(R.id.announcement_box),
                    findViewById(R.id.tv_ann_content), this, items));
        }).start();
    }

    private void forceRelogin(String message) {
        if (isFinishing()) return;
        Toast.makeText(this,
                message == null || message.isEmpty() ? "账号已不可用，请重新登录" : message,
                Toast.LENGTH_LONG).show();
        AuthManager.get().logout();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private class ProjectAdapter extends RecyclerView.Adapter<ProjectAdapter.VH> {
        private List<Project> projects = new ArrayList<>();

        void setProjects(List<Project> p) {
            this.projects = p;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_project, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Project p = projects.get(position);
            holder.tvName.setText(p.getName());
            SwitchDevice core = p.getCore();
            holder.tvCore.setText(getString(R.string.core_switch) + "："
                    + (core != null ? core.getIp() : "未配置"));
            holder.tvNodeCount.setText("节点交换机：" + p.getNodes().size() + " 台");
            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(ProjectSelectActivity.this, MainActivity.class);
                intent.putExtra("project_id", p.getId());
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return projects.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvCore, tvNodeCount;
            VH(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_project_name);
                tvCore = itemView.findViewById(R.id.tv_core);
                tvNodeCount = itemView.findViewById(R.id.tv_node_count);
            }
        }
    }
}
