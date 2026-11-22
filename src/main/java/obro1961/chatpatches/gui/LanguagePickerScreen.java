package obro1961.chatpatches.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import obro1961.chatpatches.translate.Language;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 语言选择界面 —— 原版 {@code LanguageSelectScreen} 风格的整块可滚动列表。
 *
 * <p>语言列表直接来自 Minecraft 官方 {@code LanguageManager.getLanguages()}：
 * 顺序与原版一致，内容包含官方支持的全部语言。只有入站源语言会在最上面多一个
 * 「自动检测」。
 */
public class LanguagePickerScreen extends Screen {

    private static final int LIST_TOP = 33;
    private static final int FOOTER_H = 53;
    private static final int ROW_H = 18;
    private static final int SEARCH_W = 200;
    private static final int SEARCH_H = 15;
    private static final MinecraftLanguageCatalog.Entry AUTO =
            new MinecraftLanguageCatalog.Entry("自动检测", "自动检测", "Auto Detect");

    private final Screen parent;
    private final String initial;
    private final boolean allowAuto;
    private final Consumer<String> onPick;
    private final List<MinecraftLanguageCatalog.Entry> options = new ArrayList<>();

    private String selected;
    private String query = "";
    private LanguageList list;
    private EditBox search;

    public LanguagePickerScreen(Screen parent, String current, boolean allowAuto, Consumer<String> onPick) {
        super(Component.translatable("选择语言"));
        this.parent = parent;
        this.initial = current == null ? "" : current;
        this.selected = this.initial;
        this.allowAuto = allowAuto;
        this.onPick = onPick;
        if (allowAuto) {
            options.add(AUTO);
        }
        options.addAll(MinecraftLanguageCatalog.entries());
    }

    @Override
    protected void init() {
        int searchY = 17;
        search = new EditBox(this.font, (this.width - SEARCH_W) / 2, searchY, SEARCH_W, SEARCH_H,
                Component.translatable("搜索语言"));
        search.setHint(Component.translatable("搜索语言"));
        search.setMaxLength(64);
        search.setValue(query);
        search.setResponder(this::onSearch);
        addRenderableWidget(search);

        int listHeight = Math.max(ROW_H, this.height - LIST_TOP - FOOTER_H);
        list = new LanguageList(this.minecraft, this.width, listHeight, LIST_TOP, ROW_H);
        addRenderableWidget(list);
        list.updateSizeAndPosition(this.width, listHeight, 0, LIST_TOP);
        list.filter(query);

        int y2 = this.height - 30;
        addRenderableWidget(Button.builder(Component.translatable("完成"), b -> {
            onPick.accept(selected);
            back();
        }).bounds(this.width / 2 - 122, y2, 120, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("取消"), b -> {
            onPick.accept(initial);
            back();
        }).bounds(this.width / 2 + 2, y2, 120, 20).build());
    }

    private void onSearch(String value) {
        query = value == null ? "" : value;
        if (list != null) {
            list.filter(query);
        }
    }

    private void back() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(this.font, this.title, this.width / 2, 5, 0xFFFFFF);
        Component hint = allowAuto ? Component.literal("§8可选「自动检测」") : Component.literal("§8不能选「自动检测」");
        g.drawCenteredString(this.font, hint, this.width / 2, this.height - 50, 0xAAAAAA);
    }

    @Override
    public void onClose() {
        onPick.accept(initial);
        back();
    }

    @Nullable
    public Screen parent() {
        return parent;
    }

    private static boolean matches(MinecraftLanguageCatalog.Entry entry, String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return raw.equals(entry.code())
                || raw.equals(entry.label())
                || raw.equalsIgnoreCase(entry.nativeName())
                || Language.same(raw, entry.code());
    }

    /** 原版式滚动列表：整行宽、条目标题居中、右侧原版滚动条。 */
    private final class LanguageList extends ObjectSelectionList<LanguageList.Entry> {

        LanguageList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return super.getRowWidth() + 50;
        }

        void filter(String rawQuery) {
            String q = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
            clearEntries();
            setScrollAmount(0.0D);
            Entry selectedEntry = null;
            for (MinecraftLanguageCatalog.Entry option : options) {
                if (!q.isEmpty()
                        && !option.label().toLowerCase(Locale.ROOT).contains(q)
                        && !option.code().toLowerCase(Locale.ROOT).contains(q)
                        && !option.nativeName().toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                Entry entry = new Entry(option);
                addEntry(entry);
                if (selectedEntry == null && matches(option, selected)) {
                    selectedEntry = entry;
                }
            }
            if (selectedEntry != null) {
                setSelected(selectedEntry);
            }
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {

            private final MinecraftLanguageCatalog.Entry option;

            Entry(MinecraftLanguageCatalog.Entry option) {
                this.option = option;
            }

            @Override
            public void renderContent(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float delta) {
                String label = (matches(option, selected) ? "✔ " : "") + option.label();
                g.drawCenteredString(LanguagePickerScreen.this.font,
                        Component.literal(label),
                        LanguagePickerScreen.this.width / 2,
                        getContentYMiddle() - LanguagePickerScreen.this.font.lineHeight / 2,
                        0xFFFFFFFF);
            }

            @Override
            public Component getNarration() {
                return Component.translatable("narrator.select", Component.literal(option.label()));
            }

            @Override
            public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
                if (event.button() == 0) {
                    selected = option.code();
                    setSelected(this);
                    return true;
                }
                return super.mouseClicked(event, doubleClick);
            }
        }
    }
}
