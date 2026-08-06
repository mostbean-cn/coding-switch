package com.github.mostbean.codingswitch.ui.component;

import com.intellij.icons.AllIcons;
import com.intellij.ide.CopyProvider;
import com.intellij.ide.CutProvider;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * 带显示/隐藏切换按钮的密码输入框。
 * 默认隐藏密码，点击输入框内右侧的眼睛图标可切换显示明文。
 * 完美支持在配置管理编辑等对话框中直接通过 Ctrl+C / Cmd+C 复制明文 API Key。
 */
public class PasswordFieldWithToggle extends JBPasswordField implements DataProvider, CopyProvider, CutProvider {

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

        // 允许 Swing 层的剪切与复制
        putClientProperty("JPasswordField.cutCopyAllowed", Boolean.TRUE);

        // 1. 注册 IntelliJ ActionManager 快捷键
        registerIntelliJActions();

        // 2. 设置自定义 TransferHandler
        setupTransferHandler();

        // 3. 注册 Swing KeyboardAction & InputMap / ActionMap
        setupKeyboardActions();

        // 4. 注册 KeyListener 底层监听
        setupKeyListener();

        // 5. 注册右键上下文菜单
        setupContextMenu();

        // 初始状态是隐藏密码，显示 Unshare 图标
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

    /**
     * 重写 AWT 最底层的 processKeyEvent：
     * 这是组件接收键盘事件的虚函数入口，绝对优先捕获 Ctrl+C / Cmd+C 并强行触发明文复制，
     * 解决任何对话框、Keymap 或 IDE 事件拦截导致快捷键失效的问题。
     */
    @Override
    protected void processKeyEvent(KeyEvent e) {
        if (e.getID() == KeyEvent.KEY_PRESSED) {
            boolean isCtrlOrCmd = (e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0;
            if (isCtrlOrCmd && e.getKeyCode() == KeyEvent.VK_C) {
                performCopyText();
                e.consume();
                return;
            } else if (isCtrlOrCmd && e.getKeyCode() == KeyEvent.VK_X) {
                performCutText();
                e.consume();
                return;
            }
        }
        super.processKeyEvent(e);
    }

    /**
     * 重写 getSelectedText()：
     * JDK 默认的 JPasswordField.getSelectedText() 在隐藏模式下会直接返回 null！
     * 这里重写使其无论在明文还是密文模式下，只要有选区都返回真实的选中文本明文。
     */
    @Override
    public String getSelectedText() {
        int p0 = Math.min(getCaret().getDot(), getCaret().getMark());
        int p1 = Math.max(getCaret().getDot(), getCaret().getMark());
        if (p0 != p1) {
            try {
                return getDocument().getText(p0, p1 - p0);
            } catch (Exception ignored) {
            }
        }
        return super.getSelectedText();
    }

    private void registerIntelliJActions() {
        try {
            AnAction copyAction = new DumbAwareAction() {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    performCopyText();
                }
            };
            CustomShortcutSet ctrlCSet = new CustomShortcutSet(
                    new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), null),
                    new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.META_DOWN_MASK), null)
            );
            copyAction.registerCustomShortcutSet(ctrlCSet, this);

            AnAction cutAction = new DumbAwareAction() {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    performCutText();
                }
            };
            CustomShortcutSet ctrlXSet = new CustomShortcutSet(
                    new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK), null),
                    new KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.META_DOWN_MASK), null)
            );
            cutAction.registerCustomShortcutSet(ctrlXSet, this);
        } catch (Throwable ignored) {
        }
    }

    private void setupTransferHandler() {
        setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY_OR_MOVE;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                String textToCopy = getSelectedTextOrAll();
                return new StringSelection(textToCopy);
            }

            @Override
            public void exportToClipboard(JComponent comp, Clipboard clip, int action) {
                String textToCopy = getSelectedTextOrAll();
                if (textToCopy != null && !textToCopy.isEmpty()) {
                    StringSelection contents = new StringSelection(textToCopy);
                    try {
                        CopyPasteManager.getInstance().setContents(contents);
                    } catch (Throwable ignored) {
                    }
                    try {
                        clip.setContents(contents, null);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    private void setupKeyboardActions() {
        registerKeyboardAction(
                e -> performCopyText(),
                KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_FOCUSED
        );
        registerKeyboardAction(
                e -> performCopyText(),
                KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.META_DOWN_MASK),
                JComponent.WHEN_FOCUSED
        );
        registerKeyboardAction(
                e -> performCutText(),
                KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_FOCUSED
        );
        registerKeyboardAction(
                e -> performCutText(),
                KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.META_DOWN_MASK),
                JComponent.WHEN_FOCUSED
        );

        InputMap inputMapFocused = getInputMap(JComponent.WHEN_FOCUSED);
        InputMap inputMapAncestor = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

        KeyStroke ctrlC = KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK);
        KeyStroke cmdC = KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.META_DOWN_MASK);
        KeyStroke ctrlX = KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK);
        KeyStroke cmdX = KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.META_DOWN_MASK);

        inputMapFocused.put(ctrlC, "custom-copy");
        inputMapFocused.put(cmdC, "custom-copy");
        inputMapAncestor.put(ctrlC, "custom-copy");
        inputMapAncestor.put(cmdC, "custom-copy");

        inputMapFocused.put(ctrlX, "custom-cut");
        inputMapFocused.put(cmdX, "custom-cut");
        inputMapAncestor.put(ctrlX, "custom-cut");
        inputMapAncestor.put(cmdX, "custom-cut");

        Action copyAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                performCopyText();
            }
        };
        Action cutAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                performCutText();
            }
        };

        getActionMap().put("custom-copy", copyAction);
        getActionMap().put("custom-cut", cutAction);
        getActionMap().put("copy", copyAction);
        getActionMap().put("cut", cutAction);
        getActionMap().put(DefaultEditorKit.copyAction, copyAction);
        getActionMap().put(DefaultEditorKit.cutAction, cutAction);
    }

    private void setupKeyListener() {
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                boolean isCtrlOrCmd = (e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0;
                if (isCtrlOrCmd && e.getKeyCode() == KeyEvent.VK_C) {
                    performCopyText();
                    e.consume();
                } else if (isCtrlOrCmd && e.getKeyCode() == KeyEvent.VK_X) {
                    performCutText();
                    e.consume();
                }
            }
        });
    }

    private void setupContextMenu() {
        JPopupMenu popupMenu = new JPopupMenu();

        JMenuItem copyItem = new JMenuItem("复制 (Copy)");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        copyItem.addActionListener(e -> performCopyText());

        JMenuItem cutItem = new JMenuItem("剪切 (Cut)");
        cutItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        cutItem.addActionListener(e -> performCutText());

        JMenuItem pasteItem = new JMenuItem("粘贴 (Paste)");
        pasteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        pasteItem.addActionListener(e -> paste());

        JMenuItem selectAllItem = new JMenuItem("全选 (Select All)");
        selectAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        selectAllItem.addActionListener(e -> selectAll());

        popupMenu.add(copyItem);
        popupMenu.add(cutItem);
        popupMenu.add(pasteItem);
        popupMenu.addSeparator();
        popupMenu.add(selectAllItem);

        setComponentPopupMenu(popupMenu);
    }

    public String getSelectedTextOrAll() {
        String selected = getSelectedText();
        if (selected != null && !selected.isEmpty()) {
            return selected;
        }
        return getTextValue();
    }

    public void performCopyText() {
        String textToCopy = getSelectedTextOrAll();
        if (textToCopy != null && !textToCopy.isEmpty()) {
            StringSelection contents = new StringSelection(textToCopy);
            try {
                CopyPasteManager.getInstance().setContents(contents);
            } catch (Throwable ignored) {
            }
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(contents, null);
            } catch (Throwable ignored) {
            }
        }
    }

    public void performCutText() {
        if (!isEditable() || !isEnabled()) {
            return;
        }
        performCopyText();
        int p0 = Math.min(getCaret().getDot(), getCaret().getMark());
        int p1 = Math.max(getCaret().getDot(), getCaret().getMark());
        if (p0 != p1) {
            try {
                getDocument().remove(p0, p1 - p0);
            } catch (Exception ignored) {
            }
        } else {
            setText("");
        }
    }

    @Override
    public void copy() {
        performCopyText();
    }

    @Override
    public void cut() {
        performCutText();
    }

    // --- IntelliJ DataProvider & CopyProvider & CutProvider 接口实现 ---

    @Override
    public @Nullable Object getData(@NotNull String dataId) {
        if ("copyProvider".equalsIgnoreCase(dataId) || "cutProvider".equalsIgnoreCase(dataId)
                || PlatformDataKeys.COPY_PROVIDER.is(dataId) || PlatformDataKeys.CUT_PROVIDER.is(dataId)) {
            return this;
        }
        return null;
    }

    @Override
    public void performCopy(@NotNull DataContext dataContext) {
        performCopyText();
    }

    @Override
    public boolean isCopyEnabled(@NotNull DataContext dataContext) {
        return true;
    }

    @Override
    public boolean isCopyVisible(@NotNull DataContext dataContext) {
        return true;
    }

    @Override
    public void performCut(@NotNull DataContext dataContext) {
        performCutText();
    }

    @Override
    public boolean isCutEnabled(@NotNull DataContext dataContext) {
        return isEditable() && isEnabled();
    }

    @Override
    public boolean isCutVisible(@NotNull DataContext dataContext) {
        return true;
    }

    private String I18nTooltip() {
        return "显示/隐藏";
    }

    private void togglePasswordVisibility() {
        passwordVisible = !passwordVisible;
        if (passwordVisible) {
            setEchoChar((char) 0);
            toggleIcon.setIcon(scaleIcon(AllIcons.Actions.Show, 0.8));
            toggleIcon.setToolTipText("隐藏密码");
        } else {
            setEchoChar(defaultEchoChar);
            toggleIcon.setIcon(scaleIcon(AllIcons.Actions.Unshare, 0.8));
            toggleIcon.setToolTipText("显示密码");
        }
        requestFocusInWindow();
    }

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

    public JTextField getActiveField() {
        return this;
    }

    public String getTextValue() {
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
