package obro1961.chatpatches.gui;

import dev.isxander.yacl3.api.Controller;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.controller.ControllerBuilder;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.AbstractWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import dev.isxander.yacl3.gui.controllers.ControllerWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 只显示提示文字的伪控件：标签在左，提示文字靠右，不占整行。
 *
 * <p>用途：给「本地翻译/本地朗读」这类条目在右边标注常见地址。
 */
public class HintController implements Controller<String> {

    private final Option<String> option;
    private final String hint;

    public HintController(Option<String> option, String hint) {
        this.option = option;
        this.hint = hint;
    }

    @Override
    public Option<String> option() {
        return option;
    }

    @Override
    public Component formatValue() {
        return Component.literal(hint);
    }

    @Override
    public AbstractWidget provideWidget(YACLScreen screen, Dimension<Integer> dim) {
        return new Widget(this, screen, dim);
    }

    public String hint() {
        return hint;
    }

    private static final class Widget extends ControllerWidget<HintController> {
        Widget(HintController control, YACLScreen screen, Dimension<Integer> dim) {
            super(control, screen, dim);
        }

        @Override
        protected int getHoveredControlWidth() {
            return getUnhoveredControlWidth();
        }

        @Override
        protected void drawValueText(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            String text = control.hint();
            int w = textRenderer.width(text);
            int x = getDimension().xLimit() - w - 4;
            int y = getDimension().y() + (getDimension().height() - 8) / 2 + 1;
            graphics.drawString(textRenderer, Component.literal(text), x, y, 0xFF808080, false);
        }
    }
}