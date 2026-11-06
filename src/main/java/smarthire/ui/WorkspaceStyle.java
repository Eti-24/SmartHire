package smarthire.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import javax.swing.table.DefaultTableCellRenderer;

final class WorkspaceStyle {
    static final Color NAVY = new Color(22, 40, 61);
    static final Color WORKSPACE = new Color(244, 247, 250);
    static final Color TEAL = new Color(0, 107, 117);
    static final Color INK = new Color(23, 43, 61);
    static final Color MUTED = new Color(77, 96, 113);
    static final Color LINE = new Color(216, 226, 234);
    static final Color DANGER = new Color(156, 37, 42);

    private WorkspaceStyle() { }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
            .withZone(ZoneId.systemDefault());

    static void install() {
        var font = new FontUIResource("Segoe UI", Font.PLAIN, 14);
        var defaults = UIManager.getLookAndFeelDefaults();
        for (Object key : defaults.keySet()) {
            if (key.toString().endsWith(".font")) { defaults.put(key, font); }
        }
        UIManager.put("Panel.background", WORKSPACE);
        UIManager.put("Label.foreground", INK);
        UIManager.put("Button.background", new Color(234, 240, 245));
    }

    static JButton button(String label, Runnable action) {
        var button = new JButton(label);
        button.addActionListener(event -> action.run());
        return button;
    }

    static void primary(JButton button) {
        button.setBackground(TEAL);
        button.setForeground(Color.WHITE);
    }

    static JPanel actions() {
        return new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
    }

    static JLabel heading(String value) {
        var label = new JLabel(value);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 17f));
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 4));
        return label;
    }

    static void surface(JComponent component, int padding) {
        component.setBackground(Color.WHITE);
        component.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
                BorderFactory.createEmptyBorder(padding, padding, padding, padding)));
    }

    static JPanel form() {
        var form = new Form();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        surface(form, 14);
        return form;
    }

    static void field(JPanel form, String label, JComponent input) {
        var caption = new JLabel(label);
        caption.setLabelFor(input);
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        input.setAlignmentX(Component.LEFT_ALIGNMENT);
        int height = input instanceof JScrollPane ? Math.min(150, input.getPreferredSize().height) : 32;
        input.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        form.add(caption);
        form.add(Box.createVerticalStrut(4));
        form.add(input);
        form.add(Box.createVerticalStrut(10));
    }

    static JScrollPane scroll(Component component) {
        var pane = new JScrollPane(component);
        pane.setBorder(null);
        pane.getVerticalScrollBar().setUnitIncrement(18);
        return pane;
    }

    static String date(Instant value) {
        return DATE.format(value);
    }

    static JTextArea prose(String value) {
        var area = new JTextArea(value);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setForeground(INK);
        area.setCaretPosition(0);
        return area;
    }

    static JTextArea message(String value) {
        var area = prose(value);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setForeground(MUTED);
        return area;
    }

    static JScrollPane text(String value) {
        var area = prose(value);
        area.setRows(12);
        area.setColumns(42);
        area.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        return new JScrollPane(area);
    }

    static JScrollPane table(JTable table) {
        table.setRowHeight(34);
        table.setShowVerticalLines(false);
        table.setGridColor(LINE);
        table.setSelectionBackground(new Color(210, 236, 236));
        table.setSelectionForeground(INK);
        table.getTableHeader().setBackground(WORKSPACE);
        table.setFillsViewportHeight(true);
        table.setPreferredScrollableViewportSize(new Dimension(420, 230));
        return new JScrollPane(table);
    }

    static void statusColumn(JTable table, int column) {
        table.getColumnModel().getColumn(column).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                    boolean focused, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focused, row, column);
                setForeground(statusInk(String.valueOf(value)));
                setFont(table.getFont().deriveFont(Font.BOLD));
                return this;
            }
        });
    }

    static <T> T selected(JTable table, List<T> rows) {
        int row = table.getSelectedRow();
        return row < 0 ? null : rows.get(table.convertRowIndexToModel(row));
    }

    static <T> void previewSelection(JTable table, List<T> rows, Consumer<T> show) {
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) { show.accept(selected(table, rows)); }
        });
        if (!rows.isEmpty()) { table.setRowSelectionInterval(0, 0); }
    }

    static JSplitPane split(Component list, Component preview) {
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, preview);
        split.setResizeWeight(0.57);
        split.setContinuousLayout(true);
        split.setBorder(null);
        return split;
    }

    static String label(Enum<?> value) {
        String lower = value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static Color statusInk(String status) {
        return switch (status) {
            case "Not eligible", "Rejected", "Withdrawn", "Disabled" -> DANGER;
            case "Pending", "Pending approval", "Shortlisted", "Unverified" -> new Color(118, 73, 0);
            case "Eligible", "Approved", "Hired", "New", "Active", "Verified" -> TEAL;
            default -> INK;
        };
    }

    static Runnable disable(Component component) {
        var enabled = new ArrayList<Component>();
        disable(component, enabled);
        return () -> enabled.forEach(child -> child.setEnabled(true));
    }

    private static void disable(Component component, List<Component> enabled) {
        if (component.isEnabled()) {
            enabled.add(component);
            component.setEnabled(false);
        }
        if (component instanceof Container container) {
            for (var child : container.getComponents()) { disable(child, enabled); }
        }
    }

    static final class Preview extends JPanel {
        private final JLabel status = new JLabel();
        private final JTextArea summary = prose("");

        Preview() {
            super(new BorderLayout(0, 8));
            surface(this, 12);
            setMinimumSize(new Dimension(210, 80));
            setPreferredSize(new Dimension(330, 230));
            status.setOpaque(true);
            status.setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
            status.setFont(status.getFont().deriveFont(Font.BOLD));
            var scroll = new JScrollPane(summary);
            scroll.setBorder(null);
            add(status, BorderLayout.NORTH);
            add(scroll, BorderLayout.CENTER);
        }

        void showRecord(String label, String value) {
            status.setText(label);
            status.setForeground(statusInk(label));
            status.setBackground(switch (label) {
                case "Not eligible", "Rejected", "Withdrawn", "Disabled" -> new Color(252, 232, 233);
                case "Pending", "Pending approval", "Shortlisted", "Unverified" -> new Color(255, 242, 214);
                case "Eligible", "Approved", "Hired", "New", "Active", "Verified" -> new Color(225, 245, 242);
                default -> WORKSPACE;
            });
            summary.setText(value);
            summary.setCaretPosition(0);
        }
    }

    private static final class Form extends JPanel implements Scrollable {
        @Override protected void addImpl(Component component, Object constraints, int index) {
            if (component instanceof JComponent child) { child.setAlignmentX(LEFT_ALIGNMENT); }
            if (component instanceof JLabel label) {
                label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height));
            }
            super.addImpl(component, constraints, index);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(18, visible.height - 18);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
