package com.github.mostbean.codingswitch.service;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import java.util.function.Supplier;

/**
 * 避免使用已废弃的同步读 API，同时避免在 EDT 上调用后台可取消读动作。
 */
final class PlatformReadAccess {

    private PlatformReadAccess() {
    }

    static <T> T compute(Supplier<T> supplier) {
        Application application = ApplicationManager.getApplication();
        if (application.isDispatchThread() || application.isReadAccessAllowed()) {
            return supplier.get();
        }
        return ReadAction.nonBlocking(supplier::get).executeSynchronously();
    }
}
