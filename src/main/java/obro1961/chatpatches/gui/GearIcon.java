package obro1961.chatpatches.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 齿轮图标（只负责画图，不抢点击）。
 *
 * <p>和原版 {@link net.minecraft.client.gui.components.Button} 叠在同一位置：
 * 按钮画底框/悬停/处理点击，本组件后添加、只在其上叠加齿轮，点击一律穿透给按钮。
 */
public class GearIcon extends AbstractWidget {

    private static final Identifier TEX =
            Identifier.fromNamespaceAndPath("chatpatches", "textures/gui/settings_gear.png");

    public GearIcon(int x, int y, int size) {
        super(x, y, size, size, Component.empty());
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
        // 16×16 贴图按整数居中（不缩放，避免半像素糊/歪）
        int dx = getX() + (getWidth() - 16) / 2;
        int dy = getY() + (getHeight() - 16) / 2;
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, TEX,
                dx, dy, 0.0F, 0.0F, 16, 16, 16, 16);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent mouse, boolean bl) {
        return false; // 点击穿透给下面的原版按钮
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}