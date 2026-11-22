package obro1961.chatpatches.translate;

import com.google.gson.Gson;
import static obro1961.chatpatches.ChatPatches.LOGGER;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 语言注册表 —— <b>整个翻译模块的单一事实源</b>。
 *
 * <p>数据来自 {@code assets/chatpatches/languages.json}，是
 * 「Minecraft 官方语言表」与「franc 语言检测库」的交集，共 85 种语言 / 123 个 locale 映射。
 *
 * <h2>为什么要有它</h2>
 * <p>同一门语言在四个地方有四种写法，散在代码里必然走岔：
 * <pre>
 *   MC 客户端     zh_cn / de_at / bar       （142 个 locale，含方言、梗语）
 *   franc 检测     cmn  / deu  / deu        （ISO 639-3，判定"要不要翻"）
 *   大模型        Chinese / German          （英文名，发给 prompt）
 *   mymemory       zh-CN / de-DE / 无        （GET 型 langpair 代码）
 * </pre>
 * 注册表把四者绑在一条记录上 —— 改一处，整条链路跟着变。
 *
 * <h2>能力矩阵（降级链的判据）</h2>
 * <p>{@code providers} 下的布尔字段表示<b>这条通道能不能翻这门语言</b>，
 * <b>不是</b>"语言名怎么写"：
 * <ul>
 *   <li>{@code hunyuan} —— 本地模型，实测仅中/英；其余语言必须走云端</li>
 *   <li>{@code deepseek} / {@code bilibili} —— 云端，全语言</li>
 *   <li>{@code mymemory} —— 字符串；为 {@code null} 表示该源不支持这门语言</li>
 * </ul>
 * {@link #supports(String, String)} 返回 false 时，降级链直接跳过该源。
 *
 * <h2>显示名 vs 发给模型的名</h2>
 * <p>{@link Lang#display} 取自 MC 的 {@code language.name}，和玩家客户端里
 * 看到的一模一样（{@code Deutsch} / {@code 日本語} / {@code Русский}）。
 * <b>但它不能发给模型</b> —— 那是该语言自己的写法。
 * 送给模型一律用 {@link Lang#promptName}（英文名）。
 *
 * <h2>失败处理</h2>
 * <p>加载失败时 {@link #enabled()} 返回 false，翻译整体不可用并打 ERROR 日志。
 * <b>不做静默降级</b> —— 宁可功能关掉，也不要拿着一堆编造的语言名去调 API。
 */
public final class LanguageRegistry {

    /** 资源路径（在 jar 内）。 */
    public static final String RESOURCE = "/assets/chatpatches/languages.json";

    /**
     * <b>MC 的默认语言 —— 不在 {@code assets/lang/} 里，必须代码兜底。</b>
     *
     * <p>{@code en_us} 是 Minecraft 的硬编码默认语言，{@code assets/indexes/*.json} 里
     * <b>没有</b> {@code minecraft/lang/en_us.json} 这一项。按资源索引自动生成注册表时
     * 必然漏掉它 —— 而几乎所有人的源语言就是它。
     *
     * <p>漏掉的后果（实测）：{@code Language.find("en_us")} 返回 {@code null}
     * → {@link ScriptDetector#hasCharsOf} 判"不认识的语言 → 不翻"
     * → <b>整个自动翻译静默失效</b>，日志里只有一行"跳过"，看不出根因。
     *
     * <p>所以这里做两层保险：① 生成 {@code languages.json} 时手工补；
     * ② 加载时再补一次（即使将来换了数据来源也能兜住）。
     */
    private static final Map<String, String> DEFAULT_LOCALES = Map.of(
            "en_us", "eng"
    );

    private static final Gson GSON = new Gson();

    /** 不可变快照，整体替换 —— 天然线程安全，不需要锁读取。 */
    private static volatile Snapshot snap = Snapshot.EMPTY;

    private LanguageRegistry() {
    }

    // ==================== 数据模型 ====================

    /**
     * 一门受支持的语言。
     *
     * <p>用普通 class 而不用 record：Gson 反序列化 record 需要较新版本 + 反射配置，
     * 而这里的 Gson 来自 Minecraft 依赖，版本不受我们控制。
     */
    public static final class Lang {
        /** 语言 id，同时是 franc 检测代码（ISO 639-3），如 {@code cmn} / {@code deu}。 */
        public String id = "";
        /** 分层：{@code T1} 常用 / {@code T2} 次常用 / {@code T3} 其他。 */
        public String tier = "T3";
        /** 使用人口（百万）。未知为 null。 */
        public Double populationM;
        /** 文字系统：{@code Latin} / {@code Cyrillic} / {@code cmn} 等。 */
        public String script = "";
        /** MC 原生显示名，如 {@code 简体中文} / {@code Deutsch}。 */
        public String display = "";
        /** MC 原生地区名，如 {@code 中国} / {@code Deutschland}。 */
        public String displayRegion = "";
        /** 该语言的主 locale，如 {@code zh_cn}。 */
        public String mcPrimaryLocale = "";
        /** <b>发给模型</b>的英文语言名，如 {@code Mandarin Chinese} / {@code German}。 */
        public String promptName = "";
        /**
         * ISO 639-1 两字母代码（{@code zh} / {@code de}），GET 型接口（mymemory）用。
         *
         * <p><b>可能为 {@code null}</b> —— 很多语言没有两字母代码（威尼斯语、低地德语、
         * 雅库特语等）。这种情况该源<b>翻不了</b>这门语言，降级链应当跳过它。
         */
        public String iso639_1;
        /** 各 locale 的 MC 原生显示名：{@code de_at → Deitsch}。 */
        public Map<String, String> localeNames = new LinkedHashMap<>();
        /** 能力矩阵。 */
        public Providers providers = new Providers();

        /** 是不是「常用」层（默认启用）。 */
        public boolean isCore() {
            return "T1".equalsIgnoreCase(tier);
        }

        /** 这条通道能不能翻这门语言。未知通道一律 false。 */
        public boolean supports(String provider) {
            if (provider == null || providers == null) {
                return false;
            }
            return switch (provider.toLowerCase(Locale.ROOT)) {
                case "hunyuan" -> providers.hunyuan;
                case "deepseek" -> providers.deepseek;
                case "bilibili", "bili" -> providers.bilibili;
                case "mymemory" -> providers.mymemory != null && !providers.mymemory.isBlank();
                default -> false;
            };
        }

        /** mymemory 的 langpair 代码；不支持则 {@code null}。 */
        public String mymemoryCode() {
            return providers == null ? null : providers.mymemory;
        }

        /** 按玩家实际 locale 取 MC 原生显示名，取不到退回主显示名。 */
        public String displayFor(String locale) {
            if (locale != null && localeNames != null) {
                String n = localeNames.get(normalizeLocale(locale));
                if (n != null && !n.isBlank()) {
                    return n;
                }
            }
            return display == null || display.isBlank() ? id : display;
        }

        @Override
        public String toString() {
            return id + "(" + display + ")";
        }
    }

    /** 能力矩阵。<b>布尔 = 能不能翻，不是名字怎么写。</b> */
    public static final class Providers {
        /** 本地模型（混元），实测仅中/英。 */
        public boolean hunyuan;
        /** 云端大模型，全语言。 */
        public boolean deepseek;
        /** B站 Index-Translate，全语言。 */
        public boolean bilibili;
        /** GET 型 langpair 代码；{@code null} = 该源不支持这门语言。 */
        public String mymemory;
    }

    /** 加载好的一份快照。 */
    private static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot("", Map.of(), Map.of(), Set.of(), List.of());

        final String version;
        final Map<String, Lang> byId;
        final Map<String, String> localeToId;
        final Set<String> unsupportedLocales;
        final List<Lang> ordered;

        Snapshot(String version, Map<String, Lang> byId, Map<String, String> localeToId,
                 Set<String> unsupportedLocales, List<Lang> ordered) {
            this.version = version;
            this.byId = byId;
            this.localeToId = localeToId;
            this.unsupportedLocales = unsupportedLocales;
            this.ordered = ordered;
        }
    }

    /** 原始 JSON 结构。 */
    private static final class Raw {
        String version;
        List<Lang> languages;
        Map<String, String> localeToId;
        List<String> unsupportedLocales;
    }

    // ==================== 加载 ====================

    /**
     * 从 jar 资源加载。启动时调一次。
     *
     * @return 加载到的语言条数（0 = 失败，翻译应整体关闭）
     */
    public static synchronized int load() {
        try (InputStream in = LanguageRegistry.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.error("[chatpatch] 找不到语言注册表 {} —— 翻译功能不可用。"
                        + "自己构建的话，确认 src/main/resources 里带了该文件。", RESOURCE);
                snap = Snapshot.EMPTY;
                return 0;
            }
            Raw raw = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Raw.class);
            if (raw == null || raw.languages == null || raw.languages.isEmpty()) {
                LOGGER.error("[chatpatch] 语言注册表为空或格式不对 —— 翻译功能不可用。");
                snap = Snapshot.EMPTY;
                return 0;
            }

            Map<String, Lang> byId = new LinkedHashMap<>();
            List<Lang> ordered = new ArrayList<>();
            for (Lang e : raw.languages) {
                if (e == null || e.id == null || e.id.isBlank()) {
                    LOGGER.warn("[chatpatch] 注册表有条目缺 id，已跳过");
                    continue;
                }
                if (byId.putIfAbsent(e.id, e) != null) {
                    LOGGER.warn("[chatpatch] 注册表 id 重复：{}，保留第一条", e.id);
                    continue;
                }
                if (e.providers == null) {
                    e.providers = new Providers();
                }
                ordered.add(e);
            }

            Map<String, String> localeMap = raw.localeToId == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(raw.localeToId);
            Set<String> unsupported = raw.unsupportedLocales == null
                    ? new LinkedHashSet<>() : new LinkedHashSet<>(raw.unsupportedLocales);

            // 每条 locale 映射都必须指向真实存在的语言，否则查出来是空的
            localeMap.entrySet().removeIf(en -> {
                boolean bad = en.getValue() == null || !byId.containsKey(en.getValue());
                if (bad) {
                    LOGGER.warn("[chatpatch] locale {} 指向不存在的语言 {}，已丢弃",
                            en.getKey(), en.getValue());
                }
                return bad;
            });

            // ★ MC 的默认语言不在 assets/lang 里（en_us 是硬编码的），按资源索引生成时必然漏掉。
            //   这里补一次 —— 漏掉的后果很隐蔽：源语言是 en_us 时查不到 → 所有消息都不翻，
            //   表现就是"自动翻译没动静"，而且日志里看不出原因（用户实测验过）。
            for (Map.Entry<String, String> def : DEFAULT_LOCALES.entrySet()) {
                if (!localeMap.containsKey(def.getKey()) && byId.containsKey(def.getValue())) {
                    localeMap.put(def.getKey(), def.getValue());
                    LOGGER.info("[chatpatch] 补入 MC 默认语言 {} → {}", def.getKey(), def.getValue());
                }
            }

            snap = new Snapshot(
                    raw.version == null ? "?" : raw.version,
                    Collections.unmodifiableMap(byId),
                    Collections.unmodifiableMap(localeMap),
                    Collections.unmodifiableSet(unsupported),
                    List.copyOf(ordered)
            );

            LOGGER.info("[chatpatch] {}", summary());
            return byId.size();

        } catch (Exception ex) {
            LOGGER.error("[chatpatch] 读取语言注册表失败 —— 翻译功能不可用：{}", ex.toString());
            snap = Snapshot.EMPTY;
            return 0;
        }
    }

    // ==================== 查询 ====================

    /** 注册表可用吗。false 时所有翻译路径都该直接放弃。 */
    public static boolean enabled() {
        return !snap.byId.isEmpty();
    }

    /** MC locale → 语言。<b>认不出返回 null</b> —— 调用方据此不翻，不要回落。 */
    public static Lang fromLocale(String mcLocale) {
        if (mcLocale == null || mcLocale.isBlank()) {
            return null;
        }
        String key = normalizeLocale(mcLocale);
        String id = snap.localeToId.get(key);
        if (id == null) {
            // 表里没有的带地区变体（如 zh_cn_xxx）退到主代码再试一次
            int cut = key.indexOf('_');
            if (cut > 0) {
                id = snap.localeToId.get(key.substring(0, cut));
            }
        }
        return id == null ? null : snap.byId.get(id);
    }

    /** 语言 id（= franc 代码）→ 语言。 */
    public static Lang byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return snap.byId.get(id.toLowerCase(Locale.ROOT));
    }

    /**
     * 把任意写法的语言名解析成语言。
     *
     * <p>依次试：id → MC locale → MC 原生显示名 → 英文名 → 各 locale 的显示名。
     * 全不中返回 {@code null} —— <b>不编造</b>。
     */
    public static Lang resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();

        Lang direct = byId(s);
        if (direct != null) {
            return direct;
        }
        Lang viaLocale = fromLocale(s);
        if (viaLocale != null) {
            return viaLocale;
        }

        String lower = s.toLowerCase(Locale.ROOT);
        for (Lang e : snap.ordered) {
            if (lower.equals(lowerOf(e.display))
                    || lower.equals(lowerOf(e.promptName))
                    || lower.equals(lowerOf(e.id))) {
                return e;
            }
            if (e.localeNames != null) {
                for (String n : e.localeNames.values()) {
                    if (lower.equals(lowerOf(n))) {
                        return e;
                    }
                }
            }
        }
        return null;
    }

    /** 这条 locale 是不是已知「不支持」的（虚构语 / 方言 / 极小语种）。 */
    public static boolean isUnsupportedLocale(String mcLocale) {
        return mcLocale != null && snap.unsupportedLocales.contains(normalizeLocale(mcLocale));
    }

    /**
     * 这门语言能不能用这个 provider 翻。
     *
     * <p><b>降级链的判据</b>：false 就跳到下一个源。未知 provider 一律 false。
     */
    public static boolean supports(String languageId, String provider) {
        Lang e = byId(languageId);
        return e != null && e.supports(provider);
    }

    /** 全部语言（注册表原顺序 = 按人口降序）。 */
    public static List<Lang> all() {
        return snap.ordered;
    }

    /** 按层筛选：{@code T1} / {@code T2} / {@code T3}。 */
    public static List<Lang> byTier(String tier) {
        List<Lang> out = new ArrayList<>();
        for (Lang e : snap.ordered) {
            if (tier == null || tier.equalsIgnoreCase(e.tier)) {
                out.add(e);
            }
        }
        return out;
    }

    /** 默认启用的语言（T1 常用层）。 */
    public static List<Lang> defaultEnabled() {
        List<Lang> out = new ArrayList<>();
        for (Lang e : snap.ordered) {
            if (e.isCore()) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * franc 检测的 {@code only} 候选集 —— <b>注册表有几条就是几条</b>。
     *
     * <p>注册表即能力边界：表里没有的语言，检测出来也翻不了，所以不进候选。
     * 这也正是 franc 官方推荐的用法（{@code filterLanguages} 在算距离<b>之前</b>过滤）——
     * 候选越少，误判越低。
     */
    public static List<String> francOnly() {
        List<String> out = new ArrayList<>(snap.ordered.size());
        for (Lang e : snap.ordered) {
            out.add(e.id);
        }
        return out;
    }

    /** 注册表版本号。 */
    public static String version() {
        return snap.version;
    }

    /** 日志摘要。 */
    public static String summary() {
        Snapshot s = snap;
        if (s.byId.isEmpty()) {
            return "语言注册表：未加载";
        }
        int t1 = 0, t2 = 0, t3 = 0;
        for (Lang e : s.ordered) {
            if ("T1".equalsIgnoreCase(e.tier)) {
                t1++;
            } else if ("T2".equalsIgnoreCase(e.tier)) {
                t2++;
            } else {
                t3++;
            }
        }
        return "语言注册表 v" + s.version + " 已加载：" + s.byId.size() + " 种语言"
                + "（T1 " + t1 + " / T2 " + t2 + " / T3 " + t3 + "）"
                + "，locale 映射 " + s.localeToId.size()
                + "，不支持 " + s.unsupportedLocales.size();
    }

    // ==================== 内部 ====================

    /** 统一 locale 写法：小写、下划线（MC 用 {@code zh_cn}，别处可能写 {@code zh-CN}）。 */
    private static String normalizeLocale(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String lowerOf(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** 便于自测：直接塞一份数据进来。 */
    static void loadFromJson(String json) {
        try {
            Raw raw = GSON.fromJson(json, Raw.class);
            if (raw == null || raw.languages == null) {
                snap = Snapshot.EMPTY;
                return;
            }
            Map<String, Lang> byId = new LinkedHashMap<>();
            for (Lang e : raw.languages) {
                if (e != null && e.id != null && !e.id.isBlank()) {
                    byId.putIfAbsent(e.id, e);
                }
            }
            snap = new Snapshot(
                    raw.version == null ? "?" : raw.version,
                    Collections.unmodifiableMap(byId),
                    raw.localeToId == null ? Map.of() : Collections.unmodifiableMap(raw.localeToId),
                    raw.unsupportedLocales == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(raw.unsupportedLocales)),
                    List.copyOf(byId.values())
            );
        } catch (Exception e) {
            snap = Snapshot.EMPTY;
        }
    }
}
