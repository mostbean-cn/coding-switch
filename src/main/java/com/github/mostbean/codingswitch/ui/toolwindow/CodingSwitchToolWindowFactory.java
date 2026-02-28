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
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Coding Switch Tool Window 工厂。
 */
public class CodingSwitchToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentFactory contentFactory = ContentFactory.getInstance();

        Content providerContent = contentFactory.createContent(
                new ProviderPanel(), I18n.t("toolwindow.tab.providers"), false);
        toolWindow.getContentManager().addContent(providerContent);

        Content sessionContent = contentFactory.createContent(
                new SessionPanel(), I18n.t("toolwindow.tab.sessions"), false);
        toolWindow.getContentManager().addContent(sessionContent);

        Content mcpContent = contentFactory.createContent(
                new McpPanel(project), I18n.t("toolwindow.tab.mcp"), false);
        toolWindow.getContentManager().addContent(mcpContent);

        Content skillContent = contentFactory.createContent(
                new SkillPanel(), I18n.t("toolwindow.tab.skills"), false);
        toolWindow.getContentManager().addContent(skillContent);

        Content promptContent = contentFactory.createContent(
                new PromptPanel(), I18n.t("toolwindow.tab.prompts"), false);
        toolWindow.getContentManager().addContent(promptContent);

        Content settingsContent = contentFactory.createContent(
                new SettingsPanel(), I18n.t("toolwindow.tab.settings"), false);
        toolWindow.getContentManager().addContent(settingsContent);
    }
}
