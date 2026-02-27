package com.github.mostbean.codingswitch.ui.toolwindow;

import com.github.mostbean.codingswitch.ui.panel.McpPanel;
import com.github.mostbean.codingswitch.ui.panel.PromptPanel;
import com.github.mostbean.codingswitch.ui.panel.ProviderPanel;
import com.github.mostbean.codingswitch.ui.panel.SessionPanel;
import com.github.mostbean.codingswitch.ui.panel.SettingsPanel;
import com.github.mostbean.codingswitch.ui.panel.SkillPanel;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Coding Switch Tool Window 工厂。
 * 在 IDE 右侧创建一个 Tool Window，包含多个标签页。
 */
public class CodingSwitchToolWindowFactory implements ToolWindowFactory, DumbAware {

        @Override
        public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
                ContentFactory contentFactory = ContentFactory.getInstance();

                // 标签页 1: Providers（配置管理）
                Content providerContent = contentFactory.createContent(
                                new ProviderPanel(), "配置管理", false);
                toolWindow.getContentManager().addContent(providerContent);

                // 标签页 2: 会话管理
                Content sessionContent = contentFactory.createContent(
                                new SessionPanel(), "会话管理", false);
                toolWindow.getContentManager().addContent(sessionContent);

                // 标签页 3: MCP（MCP 服务器管理）
                Content mcpContent = contentFactory.createContent(
                                new McpPanel(), "MCP 服务", false);
                toolWindow.getContentManager().addContent(mcpContent);

                // 标签页 3: Skills（Skills 管理）
                Content skillContent = contentFactory.createContent(
                                new SkillPanel(), "Skills", false);
                toolWindow.getContentManager().addContent(skillContent);

                // 标签页 4: Prompts（提示词管理）
                Content promptContent = contentFactory.createContent(
                                new PromptPanel(), "提示词", false);
                toolWindow.getContentManager().addContent(promptContent);

                // 标签页 5: Settings（CLI 版本检测 + 安装）
                Content settingsContent = contentFactory.createContent(
                                new SettingsPanel(), "设置", false);
                toolWindow.getContentManager().addContent(settingsContent);
        }
}
