package com.wgj.switchquery;

import android.app.Application;

import com.wgj.switchquery.auth.AuthManager;
import com.wgj.switchquery.connection.ConnectionManager;

/**
 * 全局 Application，持有全局连接管理器。
 */
public class App extends Application {

    private static App instance;
    private ConnectionManager connectionManager;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        connectionManager = new ConnectionManager();
        AuthManager.get().init(this);
    }

    public static App get() {
        return instance;
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }
}
