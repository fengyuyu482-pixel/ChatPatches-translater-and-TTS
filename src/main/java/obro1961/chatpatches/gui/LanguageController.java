package obro1961.chatpatches.gui;

import dev.isxander.yacl3.api.Controller;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.AbstractWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import dev.isxander.yacl3.gui.controllers.ControllerWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 语言选择控件：平时显示当前语言，点一下进入 {@link LanguagePickerScreen}。
 *
 * <p>替代原来的文本输入框 —— 手打语言名容易写错（English / 英语 / en 三种写法），
 * 现在只能从清单里选。
 */
public class LanguageController implements Controller<String> {

    private final Option<String> option;
    private final boolean allowAuto;

    public LanguageController(Option<String> option, boolean allowAuto) {
        this.option = option;
        this.allowAuto = allowAuto;
    }

    @Override
    public Option<String> option() {
        return option;
    }

    @Override
    public Component formatValue() {
        String v = option().pendingValue();
        return Component.literal(MinecraftLanguageCatalog.displayName(v));
    }

    @Override
    public AbstractWidget provideWidget(YACLScreen screen, Dimension<Integer> dim) {
        return new Widget(this, screen, dim);
    }

    public boolean allowAuto() {
        return allowAuto;
    }

    private static final class Widget extends ControllerWidget<LanguageController> {

        Widget(LanguageController control, YACLScreen screen, Dimension<Integer> dim) {
            super(control, screen, dim);
        }

        @Override
        protected int getHoveredControlWidth() {
            return getUnhoveredControlWidth();
        }

        @Override
        protected void drawValueText(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            // 不自己画底色（会变成黑块），只画文字；底框交给 YACL 控件本身
            Component text = control.formatValue();
            int x = getDimension().xLimit() - textRenderer.width(text) - getXPadding();
            int y = getDimension().centerY() - textRenderer.lineHeight / 2;
            graphics.drawString(textRenderer, text, x, y, 0xFFFFFFFF);
        }

        @Override
        public boolean onMouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && this.isMouseOver(mouseX, mouseY)) {
                LanguagePickerScreen picker = new LanguagePickerScreen(
                        this.screen,
                        control.option().pendingValue(),
                        control.allowAuto(),
                        value -> control.option().requestSet(value));
                net.minecraft.client.Minecraft.getInstance().setScreen(picker);
                return true;
            }
            return super.onMouseClicked(mouseX, mouseY, button);
        }
    }
}
