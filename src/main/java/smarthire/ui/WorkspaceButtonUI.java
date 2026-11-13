package smarthire.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicButtonUI;

/** Shared Swing button treatment, retaining native actions, mnemonics and focus traversal. */
public final class WorkspaceButtonUI extends BasicButtonUI {
    public static ComponentUI createUI(JComponent component) { return new WorkspaceButtonUI(); }

    @Override public void installUI(JComponent component) {
        super.installUI(component);
        var button = (AbstractButton) component;
        button.setOpaque(false);
        button.setRolloverEnabled(true);
    }

    @Override public void paint(Graphics graphics, JComponent component) {
        var button = (AbstractButton) component;
        var painter = (Graphics2D) graphics.create();
        painter.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color fill = button.isEnabled() ? button.getBackground() : new Color(230, 235, 239);
        if (button.isEnabled() && button.getModel().isPressed()) { fill = fill.darker(); }
        else if (button.isEnabled() && button.getModel().isRollover()) {
            fill = new Color(Math.min(255, fill.getRed() + 10), Math.min(255, fill.getGreen() + 10),
                    Math.min(255, fill.getBlue() + 10));
        }
        painter.setColor(fill);
        painter.fillRoundRect(1, 1, component.getWidth() - 3, component.getHeight() - 3, 10, 10);
        painter.setColor(button.getBackground().equals(WorkspaceStyle.NAVY) ? new Color(49, 70, 89) : WorkspaceStyle.LINE);
        painter.drawRoundRect(1, 1, component.getWidth() - 3, component.getHeight() - 3, 10, 10);
        if (button.hasFocus() && button.isFocusPainted()) {
            painter.setStroke(new BasicStroke(2));
            painter.setColor(button.getForeground().equals(Color.WHITE) ? new Color(146, 226, 217) : WorkspaceStyle.TEAL);
            painter.drawRoundRect(3, 3, component.getWidth() - 7, component.getHeight() - 7, 8, 8);
        }
        painter.dispose();
        super.paint(graphics, component);
    }

    @Override protected void paintButtonPressed(Graphics graphics, AbstractButton button) {
        // The rounded background above supplies the pressed state.
    }
}
