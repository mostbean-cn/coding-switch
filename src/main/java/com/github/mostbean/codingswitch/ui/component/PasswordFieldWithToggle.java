package com.github.mostbean.codingswitch.ui.component;

import com.intellij.icons.AllIcons;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * 带显示/隐藏切换按钮的密码输入框。
 * 默认隐藏密码，点击输入框内右侧的眼睛图标可切换显示明文。
 * 通过切换 echoChar 实现，仅一个输入框，宽度与普通输入框一致。
 */
public class PasswordFieldWithToggle extends JBPasswordField {

    private final char defaultEchoChar;
    private final JLabel toggleIcon;
    private boolean passwordVisible = false;

    public PasswordFieldWithToggle(int columns) {
        this("", columns);
    }

    public PasswordFieldWithToggle(String text, int columns) {
        setColumns(columns);
        setText(text == null ? "" : text);
        defaultEchoChar = getEchoChar();

        // 初始状态是隐藏密码，显示 Unshare 图标（表示当前已隐藏）
        Icon hideIcon = scaleIcon(AllIcons.Actions.Unshare, 0.8);
        toggleIcon = new JLabel(hideIcon);
        toggleIcon.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        toggleIcon.setToolTipText("显示密码");
        toggleIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                togglePasswordVisibility();
            }
        });

        // 将图标作为子组件放到输入框内部右侧，并预留右侧内边距避免文字被遮挡
        int iconWidth = hideIcon.getIconWidth();
        setLayout(new BorderLayout());
        JPanel iconWrap = new JPanel(new BorderLayout());
        iconWrap.setOpaque(false);
        iconWrap.setBorder(JBUI.Borders.empty(0, 3, 0, 5));
        iconWrap.add(toggleIcon, BorderLayout.CENTER);
        add(iconWrap, BorderLayout.EAST);

        Insets current = getMargin();
        int top = current == null ? 0 : current.top;
        int left = current == null ? 0 : current.left;
        int bottom = current == null ? 0 : current.bottom;
        setMargin(new Insets(top, left, bottom, iconWidth + JBUI.scale(10)));
    }

    private String I18nTooltip() {
        return "显示/隐藏";
    }

    private void togglePasswordVisibility() {
        passwordVisible = !passwordVisible;
        if (passwordVisible) {
            // 显示明文 - 图标显示 Show（表示当前正在显示）
            setEchoChar((char) 0);
            toggleIcon.setIcon(scaleIcon(AllIcons.Actions.Show, 0.8));
            toggleIcon.setToolTipText("隐藏密码");
        } else {
            // 隐藏密码 - 图标显示 Unshare（表示当前已隐藏）
            setEchoChar(defaultEchoChar);
            toggleIcon.setIcon(scaleIcon(AllIcons.Actions.Unshare, 0.8));
            toggleIcon.setToolTipText("显示密码");
        }
        requestFocusInWindow();
    }

    /**
     * 按比例缩放图标。
     */
    private static Icon scaleIcon(Icon icon, double scale) {
        int w = (int) Math.round(icon.getIconWidth() * scale);
        int h = (int) Math.round(icon.getIconHeight() * scale);
        BufferedImage image = new BufferedImage(
                icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        icon.paintIcon(null, g, 0, 0);
        g.dispose();
        Image scaled = image.getScaledInstance(w, h, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }

    /**
     * 获取内部输入框的 Document，用于添加监听器。
     */
    public JTextField getActiveField() {
        return this;
    }

    /** 返回输入的密码明文。 */
    @Override
    public String getText() {
        return new String(getPassword());
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (toggleIcon != null) {
            toggleIcon.setEnabled(enabled);
        }
    }
}
