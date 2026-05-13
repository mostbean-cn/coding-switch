package com.github.mostbean.codingswitch.service;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;

/**
 * 上下文收集器接口，用于从不同来源收集代码补全的上下文信息。
 */
public interface ContextCollector {

    /**
     * 收集器的优先级，数值越小优先级越高。
     */
    int getPriority();

    /**
     * 收集上下文信息。
     *
     * @param project  当前项目
     * @param editor   当前编辑器
     * @param cursorOffset 光标位置
     * @return 收集到的上下文信息，如果无法收集则返回空字符串
     */
    String collect(Project project, Editor editor, int cursorOffset);
}
