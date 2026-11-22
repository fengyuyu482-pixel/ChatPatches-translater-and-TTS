package obro1961.chatpatches.translate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 对话语言集：把翻译配置里的「源语言 / 目标语言」翻译成语言代码。
 *
 * <p>朗读的切段与音色选择都以它为准 —— 配的是中英，就只在这两种语言之间切；
 * 遇到别的文字（偶尔蹦出的日文/泰文）归到源语言，不额外引入新语言。
 *
 * <h2>★ 全部查注册表</h2>
 * <p>旧实现是一张硬编码 17 种语言的 {@code Map}，而语言选择器存的是 MC 的 locale 码
 * （{@code fr_ca}、{@code en_us}）。两者对不上时 {@code codeOf} 返回 {@code null}，
 * 界面就报「无法识别」，而且 {@link ScriptDetector#hasCharsOf} 会退化成
 * 「有字母就翻」—— 定向翻译等于失效。
 *
 * <p>现在一律走 {@link LanguageRegistry}：{@code fr_ca → fr}、{@code en_us → en}。
 */
public final class DialogLanguages {

    private DialogLanguages() {
    }

    /**
     * 配置里的语言 → 语言代码（ISO 639-1，如 {@code fr}）。
     *
     * @return 认不出返回 {@code null}
     */
    public static String codeOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        // 注册表里每条语言都带 iso639_1；认不出会返回 null
        String iso = Language.iso(name);
        if (iso != null && !iso.isBlank()) {
            return iso;
        }
        // 没有两字母代码的语言（威尼斯语等）退到 franc 三字母代码 ——
        // 至少能让"源/目标"互相比对，而不是返回 null 让人以为配置错了
        return Language.francCode(name);
    }

    /**
     * 当前对话语言集（源语言在前）。
     *
     * @return 认不出返回 {@code null} 表示"全自动"
     */
    public static Set<String> current() {
        TranslateConfig cfg = ConfigStore.translate();
        if (cfg == null) {
            return null;
        }
        String src = codeOf(cfg.sourceLanguage);
        String dst = codeOf(cfg.targetLanguage);
        if (src == null && dst == null) {
            return null;
        }
        Set<String> set = new LinkedHashSet<>();
        if (src != null) {
            set.add(src);
        }
        if (dst != null) {
            set.add(dst);
        }
        return set.isEmpty() ? null : set;
    }

    /**
     * 界面上提示用的语言名清单。
     *
     * <p>按注册表顺序（= 人口降序）给前 16 个，剩下的折成一行 ——
     * 以前只有 8 种硬编码，现在有 85 种可选。
     */
    public static List<String> supportedNames() {
        List<String> official = Language.displayNames();
        if (!official.isEmpty()) {
            if (official.size() <= 16) {
                return official;
            }
            List<String> out = new java.util.ArrayList<>(official.subList(0, 16));
            out.add("…（共 " + official.size() + " 种，完整列表见语言选择界面）");
            return out;
        }
        return List.of("English / 英语", "简体中文", "日本語", "한국어");
    }

    /** 源语言码（用于"不认识的语言归到这里"）。 */
    public static String primary() {
        Set<String> set = current();
        return set == null || set.isEmpty() ? "en" : set.iterator().next();
    }
}
