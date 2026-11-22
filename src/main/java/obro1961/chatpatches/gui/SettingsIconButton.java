package obro1961.chatpatches.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 透明按钮：只画 16×16 的 png 图标，悬停时加一层淡淡的高亮。
 *
 * <p>用 {@link AbstractWidget} 而不是原版 {@code Button}，所以没有灰色底和边框；
 * 加到 Screen 后由框架负责渲染和点击，不需要手写 render/mouseClicked 注入。
 */
public class SettingsIconButton extends AbstractWidget {

    private static final Identifier ICON =
            Identifier.fromNamespaceAndPath("chatpatches", "textures/gui/settings_icon.png");

    private final Runnable action;

    public SettingsIconButton(int x, int y, Runnable action) {
        super(x, y, 16, 16, Component.literal("设置"));
        this.action = action;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (this.isHoveredOrFocused()) {
            g.fill(getX() - 1, getY() - 1, getX() + getWidth() + 1, getY() + getHeight() + 1, 0x40FFFFFF);
        }
        g.blit(ICON, getX(), getY(), 0, 0, getWidth(), getHeight(), 16, 16);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent mouse, boolean bl) {
        if (this.isMouseOver(mouse.x(), mouse.y())) {
            this.action.run();
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // 无朗读需求
    }
}
