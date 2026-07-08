package com.github.mostbean.codingswitch.ui.toolwindow;

import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.LanguageChangedListener;
import com.github.mostbean.codingswitch.service.PluginSettings;
import com.github.mostbean.codingswitch.ui.panel.McpPanel;
import com.github.mostbean.codingswitch.ui.panel.PromptPanel;
import com.github.mostbean.codingswitch.ui.panel.ProviderPanel;
import com.github.mostbean.codingswitch.ui.panel.SessionPanel;
import com.github.mostbean.codingswitch.ui.panel.SettingsPanel;
import com.github.mostbean.codingswitch.ui.panel.SkillPanel;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import org.jetbrains.annotations.NotNull;

/**
 * Coding Switch Tool Window 工厂。
 * 支持动态语言切换，无需重启 IDE。
 */
public class CodingSwitchToolWindowFactory implements ToolWindowFactory, DumbAware {

    private static final Key<Boolean> LISTENER_ATTACHED_KEY = Key.create("coding.switch.toolwindow.listener.attached");

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 初始化工具窗口内容
        rebuildToolWindowContent(project, toolWindow);

        // 订阅语言切换事件，实现动态刷新
        if (!Boolean.TRUE.equals(toolWindow.getContentManager().getComponent().getClientProperty("language.listener.attached"))) {
            project.getMessageBus().connect().subscribe(
                LanguageChangedListener.TOPIC,
                new LanguageChangedListener() {
                    @Override
                    public void languageChanged(PluginSettings.Language oldLanguage, PluginSettings.Language newLanguage) {
                        // 在 EDT 线程中刷新 UI
                        ApplicationManager.getApplication().invokeLater(() -> {
                            rebuildToolWindowContent(project, toolWindow);
                        });
                    }
                }
            );
            toolWindow.getContentManager().getComponent().putClientProperty("language.listener.attached", Boolean.TRUE);
        }
    }

    /**
     * 重建工具窗口的所有标签页内容。
     * 会清空现有内容并重新创建，以应用最新的语言设置。
     */
    private void rebuildToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 记住当前选中的标签页索引
        int selectedIndex = toolWindow.getContentManager().getSelectedContent() != null
            ? toolWindow.getContentManager().getIndexOfContent(toolWindow.getContentManager().getSelectedContent())
            : 0;

        // 清空现有内容
        toolWindow.getContentManager().removeAllContents(true);

        // 重新创建所有标签页
        ContentFactory contentFactory = ContentFactory.getInstance();
        for (PluginSettings.ToolWindowFeature feature
            : PluginSettings.getInstance().getEnabledToolWindowFeatures()) {
            Content content = switch (feature) {
                case PROVIDERS -> contentFactory.createContent(
                    new ProviderPanel(project), I18n.t("toolwindow.tab.providers"), false);
                case SESSIONS -> contentFactory.createContent(
                    new SessionPanel(project), I18n.t("toolwindow.tab.sessions"), false);
                case MCP -> contentFactory.createContent(
                    new McpPanel(project), I18n.t("toolwindow.tab.mcp"), false);
                case SKILLS -> contentFactory.createContent(
                    new SkillPanel(), I18n.t("toolwindow.tab.skills"), false);
                case PROMPTS -> contentFactory.createContent(
                    new PromptPanel(), I18n.t("toolwindow.tab.prompts"), false);
                case SETTINGS -> contentFactory.createContent(
                    new SettingsPanel(project), I18n.t("toolwindow.tab.settings"), false);
            };
            toolWindow.getContentManager().addContent(content);
        }

        // 恢复之前选中的标签页
        int contentCount = toolWindow.getContentManager().getContentCount();
        if (contentCount > 0) {
            int indexToSelect = Math.min(selectedIndex, contentCount - 1);
            Content contentToSelect = toolWindow.getContentManager().getContent(indexToSelect);
            if (contentToSelect != null) {
                toolWindow.getContentManager().setSelectedContent(contentToSelect);
            }
        }

        // 重新附加会话面板的自动刷新监听器
        attachSessionPanelListener(toolWindow);
    }

    /**
     * 附加会话面板的自动刷新监听器。
     */
    private void attachSessionPanelListener(@NotNull ToolWindow toolWindow) {
        if (!Boolean.TRUE.equals(toolWindow.getContentManager().getComponent().getClientProperty(LISTENER_ATTACHED_KEY))) {
            toolWindow.getContentManager().addContentManagerListener(new ContentManagerListener() {
                @Override
                public void selectionChanged(@NotNull ContentManagerEvent event) {
                    Content selectedContent = toolWindow.getContentManager().getSelectedContent();
                    if (selectedContent != null && selectedContent.getComponent() instanceof SessionPanel sessionPanel) {
                        sessionPanel.autoRefreshOnEntry();
                    }
                }
            });
            toolWindow.getContentManager().getComponent().putClientProperty(LISTENER_ATTACHED_KEY, Boolean.TRUE);
        }
    }
}
