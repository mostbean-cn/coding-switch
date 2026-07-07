package com.github.mostbean.codingswitch.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * WebDAV 备份定时调度器：根据用户设置的间隔自动上传配置快照。
 */
@Service(Service.Level.APP)
public final class WebDavBackupScheduler {

    private static final Logger LOG = Logger.getInstance(WebDavBackupScheduler.class);
    private final ScheduledExecutorService executor = AppExecutorUtil.getAppScheduledExecutorService();
    private ScheduledFuture<?> currentTask;

    public static WebDavBackupScheduler getInstance() {
        return ApplicationManager.getApplication().getService(WebDavBackupScheduler.class);
    }

    /**
     * 启动或重启定时任务（根据当前配置）。
     */
    public synchronized void reschedule() {
        // 取消旧任务
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(false);
            currentTask = null;
        }

        PluginSettings settings = PluginSettings.getInstance();
        if (!settings.isWebdavAutoUpload()) {
            LOG.info("WebDAV auto-upload is disabled, scheduler stopped.");
            return;
        }

        int intervalMinutes = settings.getWebdavAutoUploadIntervalMinutes();
        if (intervalMinutes < 1) {
            intervalMinutes = 30;
        }

        // 首次延迟 30 秒（避免启动时立即执行造成卡顿，同时让用户快速看到效果）
        long initialDelaySeconds = 30;
        long intervalSeconds = intervalMinutes * 60L;

        LOG.info("Scheduling WebDAV auto-upload: initial delay 30s, then every " + intervalMinutes + " minutes.");
        currentTask = executor.scheduleWithFixedDelay(
            this::performAutoUpload,
            initialDelaySeconds,
            intervalSeconds,
            TimeUnit.SECONDS
        );
    }

    private void performAutoUpload() {
        try {
            PluginSettings settings = PluginSettings.getInstance();
            if (!settings.isWebdavAutoUpload()) {
                return;
            }

            String url = settings.getWebdavBackupUrl();
            if (url == null || url.isBlank()) {
                LOG.info("WebDAV URL not configured, skipping auto-upload.");
                return;
            }

            String username = settings.getWebdavBackupUsername();
            String remotePath = settings.getWebdavRemotePath();
            boolean encrypt = settings.isWebdavEncryptSensitive();

            WebDavBackupService backupService = WebDavBackupService.getInstance();
            String password = backupService.loadWebDavPassword();
            String passphrase = encrypt ? backupService.loadBackupPassphrase() : "";

            // 加密模式下，口令为空时会回退使用 WebDAV 密码，因此不再提前验证 passphrase。
            // 若两者都为空，uploadSnapshot 内部会返回错误，记录到日志即可。

            WebDavBackupService.UploadResult result = backupService.uploadSnapshot(
                url, username, password, remotePath, encrypt, passphrase
            );

            if (result.success()) {
                LOG.info("WebDAV auto-upload succeeded: " + result.message());
            } else {
                LOG.warn("WebDAV auto-upload failed: " + result.message());
            }
        } catch (Exception e) {
            LOG.warn("WebDAV auto-upload encountered an error", e);
        }
    }
}
