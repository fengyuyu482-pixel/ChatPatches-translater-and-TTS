package obro1961.chatpatches.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.LanguageInfo;
import obro1961.chatpatches.translate.Language;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Minecraft 官方语言表适配层。
 *
 * <p>列表顺序直接沿用 {@link net.minecraft.client.resources.language.LanguageManager#getLanguages()}
 * 的 SortedMap 顺序 —— 与原版语言选择界面完全一致。
 */
public final class MinecraftLanguageCatalog {

    public record Entry(String code, String label, String nativeName) {
    }

    private static volatile List<Entry> cache = List.of();

    private MinecraftLanguageCatalog() {
    }

    /** 启动时注册一次；之后直接复用，避免翻译线程运行中反复改表。 */
    public static List<Entry> entries() {
        List<Entry> c = cache;
        return c.isEmpty() ? refresh() : c;
    }
    /** 刷新官方语言表并同步注册到核心 Language 表。 */
    public static synchronized List<Entry> refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getLanguageManager() == null) {
            return cache;
        }
        SortedMap<String, LanguageInfo> languages = mc.getLanguageManager().getLanguages();
        if (languages == null || languages.isEmpty()) {
            return cache;
        }

        List<Entry> list = new ArrayList<>(languages.size());
        Map<String, String> nativeNames = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (Map.Entry<String, LanguageInfo> e : languages.entrySet()) {
            LanguageInfo info = e.getValue();
            String nativeName = info.name() == null ? "" : info.name();
            String label = info.toComponent().getString();
            list.add(new Entry(e.getKey(), label, nativeName));
            nativeNames.put(e.getKey(), nativeName);
            labels.put(e.getKey(), label);
        }
        Language.registerMinecraftLanguages(nativeNames, labels);
        cache = List.copyOf(list);
        return cache;
    }

    /** 配置值 → 界面显示名；兼容旧的「英语 / 简体中文」配置值。 */
    public static String displayName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未设置";
        }
        if ("自动检测".equals(raw) || "auto".equalsIgnoreCase(raw)) {
            return "自动检测";
        }
        return Language.displayName(raw);
    }
}
