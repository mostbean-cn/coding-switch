package com.github.mostbean.codingswitch.startup;

import com.github.mostbean.codingswitch.service.WebDavBackupScheduler;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/**
 * IDE 启动后初始化 WebDAV 备份调度器。
 */
public final class WebDavBackupStartupActivity implements StartupActivity {

    @Override
    public void runActivity(@NotNull Project project) {
        // 只在第一个打开的项目时初始化（应用级服务）
        WebDavBackupScheduler.getInstance().reschedule();
    }
}
