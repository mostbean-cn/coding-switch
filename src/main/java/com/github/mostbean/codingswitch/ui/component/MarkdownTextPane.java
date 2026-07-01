package com.github.mostbean.codingswitch.ui.component;

import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;

/**
 * Markdown 渲染文本面板。
 * 支持基本 Markdown 语法：加粗、斜体、代码、列表、引用等。
 */
public class MarkdownTextPane extends JTextPane {

    private static final Parser MARKDOWN_PARSER = Parser.builder().build();

    private final StyleContext styleContext;
    private final Style defaultStyle;
    private final Style boldStyle;
    private final Style italicStyle;
    private final Style codeStyle;
    private final Style headingStyle;
    private final Style linkStyle;

    public MarkdownTextPane() {
        setEditable(false);
        setOpaque(false);
        setBorder(JBUI.Borders.empty());

        styleContext = new StyleContext();
        StyledDocument doc = new DefaultStyledDocument(styleContext);
        setStyledDocument(doc);

        // 默认样式
        defaultStyle = styleContext.addStyle("default", null);
        StyleConstants.setFontFamily(defaultStyle, getFont().getFamily());
        StyleConstants.setFontSize(defaultStyle, 11);
        StyleConstants.setForeground(defaultStyle, UIUtil.getLabelForeground());

        // 加粗样式
        boldStyle = styleContext.addStyle("bold", defaultStyle);
        StyleConstants.setBold(boldStyle, true);

        // 斜体样式
        italicStyle = styleContext.addStyle("italic", defaultStyle);
        StyleConstants.setItalic(italicStyle, true);

        // 代码样式
        codeStyle = styleContext.addStyle("code", defaultStyle);
        StyleConstants.setFontFamily(codeStyle, "Monospaced");
        StyleConstants.setBackground(codeStyle, new Color(240, 240, 240));
        StyleConstants.setForeground(codeStyle, new Color(199, 37, 78));

        // 标题样式
        headingStyle = styleContext.addStyle("heading", defaultStyle);
        StyleConstants.setBold(headingStyle, true);
        StyleConstants.setFontSize(headingStyle, 13);

        // 链接样式
        linkStyle = styleContext.addStyle("link", defaultStyle);
        StyleConstants.setForeground(linkStyle, new Color(59, 130, 246));
        StyleConstants.setUnderline(linkStyle, true);
    }

    /**
     * 设置 Markdown 文本内容并渲染。
     */
    public void setMarkdownText(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            setText("");
            return;
        }

        try {
            Node document = MARKDOWN_PARSER.parse(markdown);
            StyledDocument doc = getStyledDocument();
            doc.remove(0, doc.getLength());

            MarkdownRenderer renderer = new MarkdownRenderer(doc);
            document.accept(renderer);

            setCaretPosition(0);
        } catch (Exception e) {
            // 解析失败时回退到纯文本显示
            setText(markdown);
        }
    }

    /**
     * Markdown AST 遍历渲染器。
     */
    private class MarkdownRenderer extends AbstractVisitor {
        private final StyledDocument document;
        private Style currentStyle;

        MarkdownRenderer(StyledDocument document) {
            this.document = document;
            this.currentStyle = defaultStyle;
        }

        @Override
        public void visit(Text text) {
            appendText(text.getLiteral(), currentStyle);
        }

        @Override
        public void visit(Paragraph paragraph) {
            visitChildren(paragraph);
            appendText("\n", defaultStyle);
        }

        @Override
        public void visit(Heading heading) {
            Style prevStyle = currentStyle;
            currentStyle = headingStyle;
            visitChildren(heading);
            currentStyle = prevStyle;
            appendText("\n", defaultStyle);
        }

        @Override
        public void visit(Emphasis emphasis) {
            Style prevStyle = currentStyle;
            currentStyle = italicStyle;
            visitChildren(emphasis);
            currentStyle = prevStyle;
        }

        @Override
        public void visit(StrongEmphasis strongEmphasis) {
            Style prevStyle = currentStyle;
            currentStyle = boldStyle;
            visitChildren(strongEmphasis);
            currentStyle = prevStyle;
        }

        @Override
        public void visit(Code code) {
            appendText(code.getLiteral(), codeStyle);
        }

        @Override
        public void visit(FencedCodeBlock fencedCodeBlock) {
            appendText(fencedCodeBlock.getLiteral(), codeStyle);
            appendText("\n", defaultStyle);
        }

        @Override
        public void visit(IndentedCodeBlock indentedCodeBlock) {
            appendText(indentedCodeBlock.getLiteral(), codeStyle);
            appendText("\n", defaultStyle);
        }

        @Override
        public void visit(Link link) {
            Style prevStyle = currentStyle;
            currentStyle = linkStyle;
            visitChildren(link);
            currentStyle = prevStyle;
        }

        @Override
        public void visit(BulletList bulletList) {
            visitChildren(bulletList);
            appendText("\n", defaultStyle);
        }

        @Override
        public void visit(OrderedList orderedList) {
            visitChildren(orderedList);
            appendText("\n", defaultStyle);
        }

        @Override
        public void visit(ListItem listItem) {
            appendText("  • ", defaultStyle);
            visitChildren(listItem);
        }

        @Override
        public void visit(BlockQuote blockQuote) {
            appendText("│ ", defaultStyle);
            visitChildren(blockQuote);
        }

        @Override
        public void visit(SoftLineBreak softLineBreak) {
            appendText(" ", defaultStyle);
        }

        @Override
        public void visit(HardLineBreak hardLineBreak) {
            appendText("\n", defaultStyle);
        }

        private void appendText(String text, Style style) {
            try {
                document.insertString(document.getLength(), text, style);
            } catch (BadLocationException e) {
                // 忽略插入错误
            }
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Container parent = getParent();
        if (parent != null && parent.getWidth() > 0) {
            int width = Math.max(1, parent.getWidth());
            setSize(width, Short.MAX_VALUE);
        }
        return super.getPreferredSize();
    }
}
