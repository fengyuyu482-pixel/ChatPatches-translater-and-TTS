package obro1961.chatpatches.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>语言别名的兼容层 —— 内部一律走 {@link LanguageRegistry}。</b>
 *
 * <h2>为什么还要有它</h2>
 * <p>注册表是单一事实源，但外面有一堆老调用点（{@code Language.iso()}、
 * {@code promptName()}、{@code same()}…）散布在 {@code ScriptDetector} /
 * {@code TranslationManager} / {@code DialogLanguages} / GUI 里。这个类<b>保留那些签名</b>，
 * 实现改成查注册表 —— 调用点一个都不用动。
 *
 * <h2>相比旧实现的变化</h2>
 * <table border=1>
 *   <caption>能力对比</caption>
 *   <tr><th></th><th>旧（硬编码 8 种）</th><th>新（注册表）</th></tr>
 *   <tr><td>认识多少语言</td><td>中/英/日/韩/俄/越/西/法</td><td><b>85 种</b></td></tr>
 *   <tr><td>发给模型的名字</td><td>靠 {@code Locale.forLanguageTag} 猜英文名</td>
 *       <td>注册表的 {@code promptName}（人工校对过）</td></tr>
 *   <tr><td>JVM 不认识的 10 个 locale</td><td>返回代码本身（{@code "brb"}）→ <b>静默出错</b></td>
 *       <td>归到母语言（{@code brb → nld}）→ 译文正确</td></tr>
 *   <tr><td>ISO 代码</td><td>8 个硬编码</td><td>注册表的 {@code iso639_1}</td></tr>
 * </table>
 *
 * <p><b>★ 用户自定义别名（{@code languages} 配置项）仍然保留</b> ——
 * 那是"配置至上"的一部分，注册表里没有的写法由它兜。
 */
public final class Language {

    /** 内置规范名（保留常量，老代码可能引用）。 */
    public static final String ZH = "简体中文";
    public static final String EN = "英语";

    /** 用户自定义：别名 → 规范名（来自 1-translate.json 的 languages 数组）。 */
    private static final Map<String, String> CUSTOM = new LinkedHashMap<>();

    /** MC 官方语言表：locale → 界面上显示的名字（由 GUI 启动时灌进来）。 */
    private static final Map<String, String> MC_DISPLAY = new LinkedHashMap<>();

    private Language() {
    }

    // ==================== MC 官方语言表 ====================

    /**
     * 注册 Minecraft 官方语言表。<b>保留这个入口是为了不改 GUI 的调用点</b>，
     * 但实际解析已经不依赖它 —— 注册表里自带 MC 的 {@code language.name}。
     *
     * <p>这里只把它记下来，供 {@link #displayName(String)} 在注册表查不到时兜底
     * （比如某个 locale 在这份 MC 版本里有、但注册表生成时还没出现）。
     */
    public static synchronized void registerMinecraftLanguages(Map<String, String> codeToNativeName,
                                                               Map<String, String> codeToLabel) {
        if (codeToNativeName == null || codeToNativeName.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : codeToNativeName.entrySet()) {
            String code = key(e.getKey());
            if (code.isEmpty()) {
                continue;
            }
            String nativeName = e.getValue() == null ? "" : e.getValue().trim();
            String label = codeToLabel == null ? nativeName : codeToLabel.getOrDefault(e.getKey(), nativeName);
            String best = (label == null || label.isBlank()) ? nativeName : label;
            if (!best.isBlank()) {
                MC_DISPLAY.put(code, best);
            }
        }
    }

    // ==================== 解析 ====================

    /**
     * 任意写法 → 注册表条目。依次试：用户自定义 → 注册表（id / locale / 显示名 / 英文名）。
     *
     * @return 认不出返回 {@code null} —— <b>不编造</b>
     */
    public static LanguageRegistry.Lang find(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String custom = CUSTOM.get(key(raw));
        if (custom != null) {
            LanguageRegistry.Lang byCustom = LanguageRegistry.resolve(custom);
            if (byCustom != null) {
                return byCustom;
            }
        }
        return LanguageRegistry.resolve(raw);
    }

    /**
     * 解析成规范名（显示名，如「简体中文」/「Deutsch」）。
     *
     * <p>保留旧语义：认不出返回 {@code null}（调用方据此判"不支持"）。
     */
    public static String resolve(String raw) {
        LanguageRegistry.Lang l = find(raw);
        return l == null ? null : lane(l);
    }

    /** 只认注册表 + 内置（不给用户自定义）—— 给 {@code setCustom} 校验用。 */
    public static String resolveBuiltin(String raw) {
        LanguageRegistry.Lang l = LanguageRegistry.resolve(raw);
        return l == null ? null : lane(l);
    }

    /**
     * 规范名。用 {@code display}（MC 原生名），<b>不是</b>英文名 ——
     * 这个名字会写进配置文件和日志，玩家看到 {@code Deutsch} 比 {@code German} 更亲切。
     */
    private static String lane(LanguageRegistry.Lang l) {
        if (l.display != null && !l.display.isBlank()) {
            return l.display;
        }
        return l.id;
    }

    /** 两个写法是不是同一门语言。 */
    public static boolean same(String a, String b) {
        LanguageRegistry.Lang la = find(a);
        LanguageRegistry.Lang lb = find(b);
        if (la != null && lb != null) {
            return la.id.equals(lb.id);
        }
        return key(a).equals(key(b));
    }

    /** 目标语言是不是中文（术语表整句命中只看这一条）。 */
    public static boolean isChinese(String raw) {
        LanguageRegistry.Lang l = find(raw);
        if (l == null) {
            return false;
        }
        if ("cmn".equals(l.id)) {
            return true;
        }
        return l.iso639_1 != null && l.iso639_1.startsWith("zh");
    }

    // ==================== 给各 provider 用的代码/名字 ====================

    /**
     * ISO 639-1 两字母代码（GET 型接口用）；没有或认不出返回 {@code null}。
     *
     * <p>没有两字母代码的语言（威尼斯语、低地德语等）返回 {@code null} ——
     * 调用方（{@code SimpleGetProvider}）据此判定"这个源翻不了这门语言"。
     */
    public static String iso(String raw) {
        LanguageRegistry.Lang l = find(raw);
        return l == null ? null : l.iso639_1;
    }

    /**
     * <b>发给模型的语言名 —— 一律英文</b>。
     *
     * <p>实测（2026-11-21，B站 Index-Translate）：中英文名当前都能正确解析
     * （写「越南语」和写 {@code Vietnamese} 结果一致）。但<b>仍然统一用英文</b>：
     * <ul>
     *   <li>本地小模型（混元等）对英文名更稳，中文名可能不认识</li>
     *   <li>冷门语言（{@code Yakut} / {@code Venetian}）只有英文名在训练语料里</li>
     *   <li>换 provider 时不用改提示词</li>
     * </ul>
     *
     * <p>认不出时返回原值 —— 至少比返回 {@code "English"} 更接近用户意图。
     */
    public static String promptName(String raw) {
        LanguageRegistry.Lang l = find(raw);
        if (l != null && l.promptName != null && !l.promptName.isBlank()) {
            return l.promptName;
        }
        return raw == null || raw.isBlank() ? "English" : raw;
    }

    /** mymemory 的 langpair 代码；该源不支持这门语言时返回 {@code null}。 */
    public static String mymemoryCode(String raw) {
        LanguageRegistry.Lang l = find(raw);
        return l == null ? null : l.mymemoryCode();
    }

    /** 这门语言能不能用某个通道翻（降级链判据）。 */
    public static boolean supports(String raw, String provider) {
        LanguageRegistry.Lang l = find(raw);
        return l != null && l.supports(provider);
    }

    /** 语言的 franc 检测代码（= 注册表 id），如 {@code deu}。 */
    public static String francCode(String raw) {
        LanguageRegistry.Lang l = find(raw);
        return l == null ? null : l.id;
    }

    /** 文字系统：{@code Latin} / {@code Cyrillic} / {@code cmn}… */
    public static String script(String raw) {
        LanguageRegistry.Lang l = find(raw);
        return l == null ? null : l.script;
    }

    // ==================== 显示 ====================

    /**
     * 界面显示名：优先注册表（MC 原生名），其次 MC 运行时语言表，最后返回原值。
     */
    public static String displayName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        LanguageRegistry.Lang l = find(raw);
        if (l != null && l.display != null && !l.display.isBlank()) {
            // 玩家用的可能就是某个地区变体，优先显示那个变体的原生名
            return l.displayFor(raw);
        }
        String direct = MC_DISPLAY.get(key(raw));
        return direct != null && !direct.isBlank() ? direct : raw;
    }

    /** 全部语言的显示名（按注册表顺序 = 人口降序）。 */
    public static List<String> displayNames() {
        List<LanguageRegistry.Lang> all = LanguageRegistry.all();
        if (all.isEmpty()) {
            return List.copyOf(MC_DISPLAY.values());
        }
        List<String> out = new ArrayList<>(all.size());
        for (LanguageRegistry.Lang l : all) {
            String n = lane(l);
            if (!n.isBlank()) {
                out.add(n);
            }
        }
        return List.copyOf(new LinkedHashSet<>(out));
    }

    /** 全部语言的规范名（= {@link #displayNames}）。 */
    public static List<String> canonicals() {
        return displayNames();
    }

    /** 注册表可用吗。false 时上层应视为"任何语言都不支持"。 */
    public static boolean available() {
        return LanguageRegistry.enabled();
    }

    // ==================== 用户自定义 ====================

    /**
     * 装用户自定义别名。每项支持两种写法：
     * <pre>
     *   "歪果话=英语"     ← 别名 = 规范名（会校验规范名是否存在）
     *   "英语"            ← 只列规范名（确认支持，实际等价于不写）
     * </pre>
     * 规范名认不出就忽略该项并留日志 —— <b>不静默接受错值</b>。
     */
    public static synchronized void setCustom(List<String> entries) {
        CUSTOM.clear();
        if (entries == null) {
            return;
        }
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            int eq = raw.indexOf('=');
            if (eq < 0) {
                if (resolveBuiltin(raw) == null) {
                    obro1961.chatpatches.ChatPatches.LOGGER.warn(
                            "[chatpatch] languages 里的「{}」不是已知语言，已忽略", raw.trim());
                }
                continue;
            }
            String alias = raw.substring(0, eq).trim();
            String canonical = raw.substring(eq + 1).trim();
            String c = resolveBuiltin(canonical);
            if (c == null) {
                obro1961.chatpatches.ChatPatches.LOGGER.warn(
                        "[chatpatch] languages 里的「{}」映射到未知语言「{}」，已忽略", alias, canonical);
                continue;
            }
            if (!alias.isEmpty()) {
                CUSTOM.put(key(alias), c);
            }
        }
    }

    /** 当前自定义了多少条（排错用）。 */
    public static int customCount() {
        return CUSTOM.size();
    }

    /** 纯测试辅助。 */
    public static List<String> sampleCustom() {
        List<String> l = new ArrayList<>();
        l.add("歪果话=英语");
        return l;
    }

    // ==================== 内部 ====================

    /** 统一 key：小写、去空格、下划线转连字符。 */
    private static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
