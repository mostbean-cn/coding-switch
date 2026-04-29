package com.github.mostbean.codingswitch.ui.toolwindow;

import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.ui.panel.McpPanel;
import com.github.mostbean.codingswitch.ui.panel.PromptPanel;
import com.github.mostbean.codingswitch.ui.panel.ProviderPanel;
import com.github.mostbean.codingswitch.ui.panel.SessionPanel;
import com.github.mostbean.codingswitch.ui.panel.SettingsPanel;
import com.github.mostbean.codingswitch.ui.panel.SkillPanel;
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
 */
public class CodingSwitchToolWindowFactory implements ToolWindowFactory, DumbAware {

    private static final Key<Boolean> LISTENER_ATTACHED_KEY = Key.create("coding.switch.toolwindow.listener.attached");

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentFactory contentFactory = ContentFactory.getInstance();

        for (com.github.mostbean.codingswitch.service.PluginSettings.ToolWindowFeature feature
            : com.github.mostbean.codingswitch.service.PluginSettings.getInstance().getEnabledToolWindowFeatures()) {
            Content content = switch (feature) {
                case PROVIDERS -> contentFactory.createContent(
                    new ProviderPanel(), I18n.t("toolwindow.tab.providers"), false);
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
