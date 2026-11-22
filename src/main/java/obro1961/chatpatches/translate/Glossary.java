package obro1961.chatpatches.translate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import obro1961.chatpatches.ChatPatches;
import obro1961.chatpatches.translate.ConfigStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 两张表：<b>术语表</b>（固定译法）+ <b>不翻译表</b>（原样保留）。
 *
 * <pre>
 * {
 *   "glossary":     { "wb": "欢迎回来", "ty": "谢谢", "l2": "第二层" },
 *   "keepOriginal": [ "awa", "qwq", "quq", "QAQ", "233", "orz" ]
 * }
 * </pre>
 *
 * <p>生效方式：
 * <ol>
 *   <li><b>整句命中术语表</b> → 直接返回该译文，<b>不发 API</b></li>
 *   <li><b>整句命中不翻译表</b> → 原样保留，也不发 API</li>
 *   <li><b>只是句子里混了</b> → 把命中项当"术语提示"塞进提示词让模型遵守</li>
 * </ol>
 *
 * <p>两张表都支持<b>叠词归一化</b>：{@code tyyyyy}→{@code ty}、{@code wbb}→{@code wb}、
 * {@code 2333}→{@code 233}。
 */
public final class Glossary {

    /** 提示模型「这个词原样保留」时用的措辞。 */
    private static final String KEEP_HINT = "(表情/拟声，原样保留，不要翻译)";

    /**
     * 查表结果。
     *
     * @param full         非空表示整句命中术语表，直接用这个译文
     * @param hints        要喂给模型的术语提示
     * @param keepOriginal true = 原样保留，不要翻译
     */
    public record Match(String full, List<String> hints, boolean keepOriginal) {
        public static final Match EMPTY = new Match(null, List.of(), false);
        public static final Match KEEP = new Match(null, List.of(), true);
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static Map<String, String> terms = new LinkedHashMap<>();
    private static Set<String> keep = new LinkedHashSet<>();

    /**
     * <b>英→英 展开表</b>：缩写 → 完整写法（{@code lmk = let me know}）。
     *
     * <p><b>为什么要它</b>：原来喂给模型的提示是「{@code wb = 欢迎回来}」这种<b>中文</b>提示 ——
     * 模型很容易照抄，而且翻别的语言就没用了。
     * 换成「{@code wb = welcome back}」是让模型<b>先把口语还原成完整英文</b>再翻，
     * 译文质量明显更稳，而且与目标语言无关。
     *
     * <p><b>同一张表还有第二个方向</b>：出站（你打中文、要发英文）时反过来用 ——
     * 把这张表的 key 当成"建议使用的简写"喂给出站提示词，
     * 让模型写出 {@code lmk} / {@code idk} / {@code u} 这种<b>真正的外服口语</b>，
     * 而不是教科书式的 "let me know" / "I do not know"。
     *
     * <p>⚠️ 这张表<b>只作为提示</b>喂给模型，<b>不做字符串替换</b> ——
     * 所以 {@code u} 不会把 {@code you} 里的字母也换掉（替换才会踩那个坑）。
     */
    private static Map<String, String> expand = new LinkedHashMap<>();

    private Glossary() {
    }

    /** 术语表文件。**改名接管**：老名 glossary.json，新名 4-glossary.json。 */
    public static Path file() {
        Path p = ConfigStore.dir().resolve("4-glossary.json");
        Path legacy = ConfigStore.dir().resolve("glossary.json");
        if (!Files.exists(p) && Files.exists(legacy)) {
            try {
                Files.copy(legacy, p);
                ChatPatches.LOGGER.info("[chatpatch] 配置改名：glossary.json → 4-glossary.json（旧文件保留）");
            } catch (Exception e) {
                ChatPatches.LOGGER.warn("[chatpatch] 生成 4-glossary.json 失败（继续用旧文件）: {}", e.toString());
            }
        }
        return p;
    }

    public static Map<String, String> terms() {
        return terms;
    }

    public static Set<String> keepOriginal() {
        return keep;
    }

    /** 英→英 展开表（缩写 → 完整写法）。出站提示词也用它。 */
    public static Map<String, String> expandTerms() {
        return expand;
    }

    /** 直接替换两张表（供 load 与自测使用）。 */
    public static void apply(Map<String, String> newTerms, Collection<String> newKeep) {
        apply(newTerms, newKeep, null);
    }

    /** 直接替换三张表。{@code newExpand} 传 {@code null} 表示保留现有展开表。 */
    public static void apply(Map<String, String> newTerms, Collection<String> newKeep,
                             Map<String, String> newExpand) {
        apply(newTerms, newKeep, newExpand, null);
    }

    /** 直接替换四张表。{@code newExpand} / {@code newByLanguage} 传 {@code null} 表示保留现有的。 */
    public static void apply(Map<String, String> newTerms, Collection<String> newKeep,
                             Map<String, String> newExpand,
                             Map<String, Map<String, String>> newByLanguage) {
        terms = newTerms == null ? new LinkedHashMap<>() : new LinkedHashMap<>(newTerms);
        keep = newKeep == null ? new LinkedHashSet<>() : new LinkedHashSet<>(newKeep);
        if (newExpand != null) {
            expand = new LinkedHashMap<>(newExpand);
        }
        if (newByLanguage != null) {
            byLanguage = new LinkedHashMap<>(newByLanguage);
        }
    }

    /**
     * 读表；文件不存在就写一份带种子的；旧格式（平铺 map）自动迁移。
     *
     * <p>注意：配置<b>只在启动时读一次</b>，改完要重启（或 {@code /chatstudy reload}）。
     */
    public static void load() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());

            if (!Files.exists(f)) {
                // ★★ 三张表都要塞 —— 这里漏了 expand 造成过一个很隐蔽的 bug。
                //
                //   原来只写 apply(seedTerms(), seedKeep())，还直接 return 跳过了 mergeSeeds()，
                //   结果【全新安装】生成的 glossary.json 里 expand 是空的 ✗。
                //   后果分两边都不响：
                //     · 朗读：lmk 展不开（且 ty/gg 还能展开 —— 因为它们在朗读配置自己的替换表里，
                //       于是现象是"有的能展开有的不能"，极难看出是这张表空的）
                //     · 入站翻译：lmk / nvm / tbh 这些术语提示从来没生效过
                //   而老配置看不出问题 —— 因为它走的是下面的 mergeSeeds()，那条路会补 expand。
                //   **只有"全新安装"这条路能暴露它**（这正是那个全新安装测试端抓到的）。
                //
                // ★★ 2026-10-13 又踩了同一类坑（第二次！）：
                //   这次漏的是第四张表 seedByLanguage() —— 全新安装时 byLanguage 写成空对象 {} ✗。
                //   后果（用户实测）：「am poor pls donate」翻出来是
                //     「Tui nghèo quá, 求你了 donate đi.」✗ —— 越南语里嵌了个中文词 ✗
                //   因为越南语表空 → 提示词只能拿中文表的 pls=求你了 → 模型照抄 ✗
                //   ★ 教训：这个分支有四张表，以后加第五张也会漏 —— 所以让 apply 的
                //     四参重载【必须显式传】，别再退回三参 ✗
                apply(seedTerms(), seedKeep(), seedExpand(), seedByLanguage());
                save(f);
                ChatPatches.LOGGER.info("[chatpatch] 已生成术语表: {}（术语表 {} 条 / 不翻译表 {} 条 / "
                                + "英英展开表 {} 条 / 分语言对照表 {} 种语言）",
                        f.toAbsolutePath(), terms.size(), keep.size(), expand.size(), byLanguage.size());
                return;
            }

            String json = Files.readString(f, StandardCharsets.UTF_8);

            // ★★ 解析不了就【立刻返回，绝不往下走，更不覆盖文件】。
            //
            //   为什么必须在这里挡：loadFromJson 内部把解析异常【吞掉了】——
            //   解析失败它会掉进 readLegacy 分支，面对一段残缺 JSON 只能读出一张【空表】，
            //   然后下面那句 save(f) 就把用户的文件【覆盖成种子表】。
            //   用户的感受是：改术语表时打错一个引号 → 重启 → 自己加的几百条全没了。
            //
            //   现在的行为：内存里先用内置种子顶着（功能不至于瘫），
            //   但【一个字都不写回文件】，用户把 JSON 改好重启即可，数据还在。
            try {
                JsonParser.parseString(json).getAsJsonObject();
            } catch (Exception bad) {
                apply(seedTerms(), seedKeep());
                ChatPatches.LOGGER.warn("[chatpatch] 术语表 JSON 解析失败，"
                        + "【没有覆盖你的文件】，本次先用内置种子（把 {} 改好再重启即可）: {}",
                        f.toAbsolutePath(), bad.toString());
                return;
            }

            loadFromJson(json);

            int added = mergeSeeds();
            // ★ 双向表：老配置里"值还是单一说法"的，升级成「打得好/恭喜/祝贺」这种多说法写法。
            //   不升级的话，"打祝贺出 gg"在老用户那儿永远不生效（合并只补缺、不覆盖）。
            int upgraded = upgradeValues();
            save(f); // 统一写回新格式，顺带完成旧格式迁移
            ChatPatches.LOGGER.info("[chatpatch] 术语表 {} 条 / 不翻译表 {} 条 / 英英展开表 {} 条"
                            + "（补入 {} 条新种子，升级 {} 条为多说法）",
                    terms.size(), keep.size(), expand.size(), added, upgraded);
        } catch (Exception e) {
            ChatPatches.LOGGER.warn("[chatpatch] 术语表读取失败，改用内置种子", e);
            apply(seedTerms(), seedKeep());
        }
    }

    /**
     * 从 JSON 文本载入，自动识别新旧格式。
     *
     * <p>旧格式是平铺的 map（值为空串表示“不翻译”），读到就迁移成两张表。
     * 抽成 public 是为了能单独测——**迁移一旦有 bug 就是整张表丢光**，不能只靠"跑一次看看"。
     */
    public static void loadFromJson(String json) {
        JsonObject root = null;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception ignored) {
            // 解析不了就走迁移分支，由 readLegacy 兜住
        }
        if (root != null && (root.has("glossary") || root.has("keepOriginal"))) {
            readNew(root);
        } else {
            readLegacy(json);
        }
    }

    /**
     * <b>术语表是双向的</b>：一张表，两个方向都用。
     *
     * <pre>
     *   入站（别人说英文）：gg  →  整句命中就直接出「打得好」；出现在句子里就作为提示喂给模型
     *   出站（你打中文）  ：打「祝贺 / 恭喜 / 打得好」 →  反过来查出 gg，提示模型用 gg
     * </pre>
     *
     * <p><b>所以中文侧要写全会用到的说法，用 {@code /} 分隔</b>，例如
     * {@code "gg": "打得好/恭喜/祝贺"}。原因（用户实测的原话）：
     * <blockquote>「我的想法是，我打祝贺能出现 gg」</blockquote>
     * 而原来 {@code gg} 的值只有「打得好」、{@code congrats} 只有「恭喜」——
     * 打「祝贺」反查不到任何东西，模型只好自己写正式英文。
     *
     * <p><b>显示只取第一个说法</b>，所以整句快译不会输出 {@code 打得好/恭喜/祝贺} 这种四不像。
     */
    private static String display(String value) {
        if (value == null) {
            return null;
        }
        int i = value.indexOf('/');
        return i < 0 ? value : value.substring(0, i).trim();
    }

    /** 把中文侧拆成所有说法（双向表里"反着查"要全都认）。 */
    private static List<String> aliases(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (String s : value.split("/")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 查表。匹配前按 {@link #candidates} 生成候选 key 依次试。 */
    public static Match lookup(String text) {
        return lookup(text, null);
    }

    /**
     * <b>带目标语言的查表 —— 语言与表【锁死】</b>。
     *
     * <p>★ 锁定规则（用户要求「把配置的语言跟对照表锁死，以免出bug」）：
     * <pre>
     *   targetLanguage = 简体中文  → 只用 glossary（中文表）
     *   targetLanguage = 越南语    → 只用 byLanguage[越南语]
     *   该语言【没有】自己的表     → 返回空 → 调用方走 API（绝不跨语言回落 ✗）
     * </pre>
     *
     * <p><b>为什么必须锁死</b>：跨语言回落正是那个 bug 的根子 ✗ ——
     * 越南语目标下，`pls` 在越南语表里没命中就落到中文表，
     * 拿到提示「{@code pls = 求你了}」→ 模型照抄 →
     * 翻出「{@code Tui nghèo quá, 求你了 donate đi.}」（越南语里嵌中文）✗
     * （用户实测：「pls 又翻译成求你了…这个越南版要完全越南语」）。
     *
     * <p>锁死之后：表里有的用表（零成本、语言一定对 ✓），
     * 表里没有的交给 API（慢一点，但语言也一定对 ✓）—— <b>两条路都不会出中文</b> ✓
     *
     * @param targetLanguage 目标语言；{@code null} = 只查中文表（老行为）
     */
    public static Match lookup(String text, String targetLanguage) {
        if (text == null || text.isBlank() || (terms.isEmpty() && keep.isEmpty())) {
            return Match.EMPTY;
        }
        String body = text.trim();
        // ★ 锁死：这门语言有专属表就【只】用它；没有才用中文表
        Map<String, String> langTable = termsFor(targetLanguage);
        Map<String, String> valueTable = langTable.isEmpty() ? terms : langTable;

        // 路径 1：整句命中
        if (hits(keep, body)) {
            return Match.KEEP;
        }
        String whole = valueTable.get(hitKey(valueTable, body));
        if (whole != null) {
            // ★ 双向表：值可能是「打得好/恭喜/祝贺」这种多说法写法 ——
            //   整句快译只出【第一个】，别把斜杠一起显示出来。
            return new Match(display(whole), List.of(), false);
        }

        // 路径 2：词级命中 → 术语提示（同样只用锁定的那张表 ✓）
        List<String> hints = new ArrayList<>();
        for (String token : body.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}/']+")) {
            if (token.isBlank()) {
                continue;
            }
            if (hits(keep, token)) {
                hints.add(token + " = " + KEEP_HINT);
                continue;
            }
            String t = valueTable.get(hitKey(valueTable, token));
            if (t != null && !t.isBlank()) {
                hints.add(token + " = " + t);
                continue;
            }
            // 英→英 展开：让模型先把口语还原成完整英文，再翻成目标语言
            // （这张表是语言中立的 ✓ —— lmk = let me know 对任何目标语言都有用）
            String ex = expand.get(hitKey(expand, token));
            if (ex != null && !ex.isBlank()) {
                hints.add(token + " = " + ex);
            }
        }
        return new Match(null, hints, false);
    }

    /**
     * <b>反向提示：你说的话 → 英文缩写</b>（双向表的另一半）。
     *
     * <p>出站翻译（你打中文/越南语、实际发出去的却是英文）时用这个，
     * 让模型知道「谢谢」在外服聊天里通常写成 {@code ty}，而不是正式的 "thank you"。
     *
     * <p>★ 中文侧有<b>多个说法</b>时（{@code "gg": "打得好/恭喜/祝贺"}），这里<b>全都认</b> ——
     * 用户打「祝贺」也要能提示出 {@code gg}。
     *
     * <p>★ <b>2026-10-13 修了两个洞</b>：
     * <ol>
     *   <li><b>匹配方向只有一个</b> ✗ —— 原来只判 {@code 你打的字.contains(表里的值)}。
     *       表里 {@code wb → 欢迎回来}，而用户只打「<b>欢迎</b>」（比表词短）→ 判不上 ✗
     *       （用户实测：「没有把欢迎变成 wb」）。
     *       现在<b>双向</b>：既认"句子里出现表词"，也认"你打的正是表词的一部分" ✓。
     *       反向匹配要求输入 <b>≥2 个字符</b> —— 一个字太含糊（「好」会命中「打得好」✗）。</li>
     *   <li><b>只扫中文表</b> ✗ —— 越南语版把译文放在 {@code byLanguage} 里，
     *       于是越南人打 {@code cảm ơn} 拿不到 {@code ty} 提示。
     *       现在<b>所有语言表都扫</b> ✓。</li>
     * </ol>
     */
    public static List<String> reverseHints(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String t = text.toLowerCase(Locale.ROOT);
        // 中文表
        for (Map.Entry<String, String> e : terms.entrySet()) {
            for (String zh : aliases(e.getValue())) {
                addReverseHint(out, t, zh, e.getKey());
            }
        }
        // ★ 各语言表（越南语等）—— 越南语用户打「cảm ơn」也要能提示出 ty
        for (Map<String, String> lang : byLanguage.values()) {
            for (Map.Entry<String, String> e : lang.entrySet()) {
                addReverseHint(out, t, e.getValue(), e.getKey());
            }
        }
        return out;
    }

    /** 加一条反向提示（双向匹配；短输入只走"输入 ⊂ 表词"那一侧）。 */
    private static void addReverseHint(List<String> out, String lowerInput, String value, String abbr) {
        if (value == null || value.isBlank() || abbr == null || abbr.isBlank()) {
            return;
        }
        String v = value.toLowerCase(Locale.ROOT);
        boolean forward = lowerInput.contains(v);
        // 反向：你打的正是表词的一部分（「欢迎」⊂「欢迎回来」）——
        // 要求输入 ≥2 字符，免得「好」命中「打得好」这种过宽的提示 ✗
        boolean backward = lowerInput.length() >= 2 && v.contains(lowerInput);
        if (!forward && !backward) {
            return;
        }
        String hint = value + " -> " + abbr;
        if (!out.contains(hint)) {
            out.add(hint);
        }
    }

    private static boolean hits(Set<String> table, String raw) {
        for (String c : candidates(raw)) {
            if (table.contains(c)) {
                return true;
            }
        }
        return false;
    }

    /** 命中的那个候选 key，没命中返回一个必定取不到的值。 */
    private static String hitKey(Map<String, String> table, String raw) {
        for (String c : candidates(raw)) {
            if (table.containsKey(c)) {
                return c;
            }
        }
        return "\u0000\u0000none\u0000\u0000";
    }

    /**
     * 生成候选 key：原文 → 连续字符截到最多 2 个 → 截到最多 1 个。
     *
     * <p>“逐级截断”而不是直接压成 1 个：{@code gggg} 直接压成 1 个会变 {@code g}，
     * 永远命中不了 {@code gg}。先截到 2 个就能命中。
     * <pre>
     *   gggg   -> [gggg, gg, g]      命中 gg
     *   tyyyyy -> [tyyyyy, tyy, ty]  命中 ty
     *   2333   -> [2333, 233, 23]    命中 233
     * </pre>
     */
    static List<String> candidates(String raw) {
        String base = raw.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(3);
        out.add(base);
        String c2 = collapse(base, 2);
        if (!out.contains(c2)) {
            out.add(c2);
        }
        String c1 = collapse(base, 1);
        if (!out.contains(c1)) {
            out.add(c1);
        }
        return out;
    }

    /** 把长度超过 {@code maxRun} 的连续相同字符截到 {@code maxRun} 个。 */
    static String collapse(String s, int maxRun) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int j = i;
            while (j < s.length() && s.charAt(j) == c) {
                j++;
            }
            int total = Math.min(j - i, maxRun);
            for (int k = 0; k < total; k++) {
                sb.append(c);
            }
            i = j;
        }
        return sb.toString();
    }

    // ---------------- 读写 ----------------

    private static void readNew(JsonObject root) {
        Map<String, String> t = new LinkedHashMap<>();
        Set<String> k = new LinkedHashSet<>();
        JsonObject g = root.getAsJsonObject("glossary");
        if (g != null) {
            for (String key : g.keySet()) {
                JsonElement v = g.get(key);
                if (v != null && !v.isJsonNull()) {
                    t.put(key, v.getAsString());
                }
            }
        }
        JsonArray a = root.getAsJsonArray("keepOriginal");
        if (a != null) {
            for (JsonElement e : a) {
                if (e != null && !e.isJsonNull()) {
                    k.add(e.getAsString());
                }
            }
        }
        // 英→英 展开表（老配置没有这一段 → 保持空，后面 mergeSeeds 会补种子）
        Map<String, String> ex = null;
        JsonObject eo = root.getAsJsonObject("expand");
        if (eo != null) {
            ex = new LinkedHashMap<>();
            for (String key : eo.keySet()) {
                JsonElement v = eo.get(key);
                if (v != null && !v.isJsonNull()) {
                    ex.put(key, v.getAsString());
                }
            }
        }
        // 按目标语言分组的对照表（老配置没这段 → 保持空，mergeSeeds 会补越南语种子）
        Map<String, Map<String, String>> bl = null;
        JsonObject bo = root.getAsJsonObject("byLanguage");
        if (bo != null) {
            bl = new LinkedHashMap<>();
            for (String lang : bo.keySet()) {
                JsonElement le = bo.get(lang);
                if (le == null || !le.isJsonObject()) {
                    continue;
                }
                Map<String, String> one = new LinkedHashMap<>();
                for (String key : le.getAsJsonObject().keySet()) {
                    JsonElement v = le.getAsJsonObject().get(key);
                    if (v != null && !v.isJsonNull()) {
                        one.put(key, v.getAsString());
                    }
                }
                bl.put(lang, one);
            }
        }
        apply(t, k, ex, bl);
    }

    /** 旧格式：平铺的 map，值为空串表示“不翻译”。读完会自动迁移成两张表。 */
    private static void readLegacy(String json) {
        Map<String, String> flat = GSON.fromJson(json,
                new TypeToken<LinkedHashMap<String, String>>() { }.getType());
        Map<String, String> t = new LinkedHashMap<>();
        Set<String> k = new LinkedHashSet<>();
        if (flat != null) {
            for (Map.Entry<String, String> e : flat.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) {
                    k.add(e.getKey());
                } else {
                    t.put(e.getKey(), e.getValue());
                }
            }
        }
        apply(t, k);
        ChatPatches.LOGGER.info("[chatpatch] 术语表为旧格式，已迁移为「术语表 + 不翻译表」");
    }

    private static void save(Path f) throws Exception {
        JsonObject root = new JsonObject();
        JsonObject g = new JsonObject();
        for (Map.Entry<String, String> e : terms.entrySet()) {
            g.addProperty(e.getKey(), e.getValue());
        }
        root.add("glossary", g);

        // 英→英 展开表
        JsonObject eo = new JsonObject();
        for (Map.Entry<String, String> e : expand.entrySet()) {
            eo.addProperty(e.getKey(), e.getValue());
        }
        root.add("expand", eo);

        JsonArray a = new JsonArray();
        for (String s : keep) {
            a.add(s);
        }
        root.add("keepOriginal", a);

        // 按目标语言分组的对照表
        JsonObject bl = new JsonObject();
        for (Map.Entry<String, Map<String, String>> lang : byLanguage.entrySet()) {
            JsonObject one = new JsonObject();
            for (Map.Entry<String, String> e : lang.getValue().entrySet()) {
                one.addProperty(e.getKey(), e.getValue());
            }
            bl.add(lang.getKey(), one);
        }
        root.add("byLanguage", bl);

        Files.writeString(f, GSON.toJson(root), StandardCharsets.UTF_8);
    }

    /**
     * 把"只有单一说法"的内置值升级成双向表的多说法写法。
     *
     * <p><b>为什么需要</b>：{@link #mergeSeeds} <b>只补缺失的键、从不覆盖已有的</b> ——
     * 这是对的（用户改过的东西永远是他的）。但副作用是：
     * 老配置里那句 {@code "gg": "打得好"} 永远升不上去，
     * 于是"打「祝贺」出 gg"这个新能力在<b>老用户那儿根本不生效</b>。
     *
     * <p>所以这里<b>只升级"值仍然是旧默认"的那些</b>：
     * 用户只要动过这个值（哪怕改成「好耶」），就一律不碰。
     */
    public static int upgradeValues() {
        int n = 0;
        // gg：原来只有「打得好」，现在把"恭喜"这层意思也写进中文侧
        if ("打得好".equals(terms.get("gg"))) {
            terms.put("gg", "打得好/恭喜/祝贺");
            n++;
        }
        for (String k : new String[]{"congrats", "congratz", "congrat", "grats", "gratz", "gz"}) {
            if ("恭喜".equals(terms.get(k))) {
                terms.put(k, "恭喜/祝贺/道喜");
                n++;
            }
        }
        return n;
    }

    /** 按目标语言分组的对照表。 */
    public static Map<String, Map<String, String>> byLanguage() {
        return byLanguage;
    }

    /**
     * <b>按目标语言分组的对照表</b>：语言名 → （缩写 → 该语言的译文）。
     *
     * <p><b>为什么需要它</b>：{@code glossary} 段存的值<b>全是中文</b>
     * （{@code wb → 欢迎回来}）。目标语言设成越南语时，那条整句快译就会吐中文 ✗ ——
     * 用户实测反馈：「越南语版本好像还是把英语翻译成中文而不是越南语」。
     * 修掉"不该用中文值"之后（见 {@code TranslationManager.canUseGlossaryHit}），
     * 还得有个地方放<b>越南语的对照表</b>，否则每个常用词都要走一次 API。
     *
     * <pre>{@code
     * "byLanguage": {
     *   "越南语": { "wb": "Chào mừng trở lại", "ty": "cảm ơn", "gg": "chúc mừng" }
     * }
     * }</pre>
     *
     * <p>语言名按<b>模糊匹配</b>（配置里写「越南语」「Vietnamese」「vi」都认）——
     * 用户写的是给人看的语言名，不该逼他记代码。
     *
     * <p>{@code "en": {"bruh": "bruh"}} 这种同形词也可以放，用途见 {@code TranslationManager}。
     */
    private static Map<String, Map<String, String>> byLanguage = new LinkedHashMap<>();

    /** 语言名匹配：配置里怎么写都能对上（越南语 / Vietnamese / vi / vi-VN）。 */
    static boolean sameLanguage(String tableLang, String wanted) {
        if (tableLang == null || wanted == null) {
            return false;
        }
        String a = tableLang.trim().toLowerCase(Locale.ROOT);
        String b = wanted.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        // 越南语 ↔ Vietnamese ↔ vi
        if ((a.startsWith("越南") || a.contains("vietnam") || a.equals("vi") || a.startsWith("vi-"))
                && (b.startsWith("越南") || b.contains("vietnam") || b.equals("vi") || b.startsWith("vi-"))) {
            return true;
        }
        // 英文 ↔ English ↔ en
        if ((a.startsWith("英") || a.contains("english") || a.equals("en") || a.startsWith("en-"))
                && (b.startsWith("英") || b.contains("english") || b.equals("en") || b.startsWith("en-"))) {
            return true;
        }
        return false;
    }

    /** 指定目标语言的对照表；没有就返回空表（调用方据此走 API）。 */
    public static Map<String, String> termsFor(String targetLanguage) {
        if (targetLanguage == null || targetLanguage.isBlank()) {
            return Map.of();
        }
        for (Map.Entry<String, Map<String, String>> e : byLanguage.entrySet()) {
            if (sameLanguage(e.getKey(), targetLanguage)) {
                return e.getValue();
            }
        }
        return Map.of();
    }

    /**
     * <b>整句命中（指定目标语言那一份表）</b>—— 匹配规则和 {@link #lookup} 一样
     * （大小写不敏感 + 容忍拖长音：{@code tyyyy} ≡ {@code ty}）。
     *
     * @return 译文；没命中返回 null（调用方走 API）
     */
    public static String wholeLineFor(String text, String targetLanguage) {
        Map<String, String> table = termsFor(targetLanguage);
        if (table == null || table.isEmpty() || text == null || text.isBlank()) {
            return null;
        }
        return table.get(hitKey(table, text.trim()));
    }

    /**
     * 只补缺失的种子，不覆盖你改过的。返回补入条数。
     *
     * <p>public 是为了能单独测：种子的覆盖度就是功能的覆盖度，
     * 少一条就是某个缩写"入站看不懂、出站也不会用"。
     */
    public static int mergeSeeds() {
        int added = 0;
        for (Map.Entry<String, String> e : seedTerms().entrySet()) {
            if (!terms.containsKey(e.getKey()) && !keep.contains(e.getKey())) {
                terms.put(e.getKey(), e.getValue());
                added++;
            }
        }
        for (String s : seedKeep()) {
            if (!terms.containsKey(s) && keep.add(s)) {
                added++;
            }
        }
        for (Map.Entry<String, String> e : seedExpand().entrySet()) {
            if (!expand.containsKey(e.getKey()) && !keep.contains(e.getKey())) {
                expand.put(e.getKey(), e.getValue());
                added++;
            }
        }
        // 按语言的对照表：只补缺失的【语言】和缺失的【词】，不覆盖你改过的
        for (Map.Entry<String, Map<String, String>> lang : seedByLanguage().entrySet()) {
            Map<String, String> cur = byLanguage.get(lang.getKey());
            if (cur == null) {
                byLanguage.put(lang.getKey(), new LinkedHashMap<>(lang.getValue()));
                added += lang.getValue().size();
                continue;
            }
            for (Map.Entry<String, String> e : lang.getValue().entrySet()) {
                if (!cur.containsKey(e.getKey())) {
                    cur.put(e.getKey(), e.getValue());
                    added++;
                }
            }
        }
        return added;
    }

    /**
     * <b>按目标语言分组的对照表 —— 内置种子</b>。
     *
     * <p>目前只有越南语。这些词机翻出来往往能用、但不够"游戏里说话"的味道；
     * 而且每查一次都要走 API（几百毫秒 + 一次调用）。进表之后整句零成本。
     *
     * <p>⚠️ <b>越南语这部分建议让越南玩家过一遍</b> ——
     * 我是按常用说法写的，日常够用，但俚语/语气未必和 MIA 社区一致。
     * 改 {@code glossary.json} 的 {@code byLanguage} 段即可，改完重启生效。
     */
    private static Map<String, Map<String, String>> seedByLanguage() {
        Map<String, Map<String, String>> all = new LinkedHashMap<>();

        Map<String, String> vi = new LinkedHashMap<>();
        // ★ 覆盖【全部】74 个种子键 —— 漏一个就会被 mergeSeeds 回填成中文 ✗
        //   （合并只补缺失的键，从不覆盖）。这就是为什么要一条条对齐全。
        // 礼貌 / 常用
        vi.put("wb", "Chào mừng trở lại");
        vi.put("ty", "cảm ơn");
        vi.put("thx", "cảm ơn");
        vi.put("tysm", "cảm ơn rất nhiều");
        vi.put("yw", "không có gì");
        vi.put("np", "không sao đâu");
        vi.put("sry", "xin lỗi");
        vi.put("mb", "lỗi của tôi");
        vi.put("alr", "được rồi");
        // 恭喜一族
        vi.put("gg", "chúc mừng");
        vi.put("congrats", "chúc mừng");
        vi.put("congratz", "chúc mừng");
        vi.put("congrat", "chúc mừng");
        vi.put("grats", "chúc mừng");
        vi.put("gratz", "chúc mừng");
        vi.put("gz", "chúc mừng");
        // 游戏
        vi.put("gj", "làm tốt lắm");
        vi.put("nc", "hay đấy");
        vi.put("ez", "dễ thôi");
        vi.put("pog", "đỉnh thật");
        vi.put("rip", "chia buồn");
        vi.put("wp", "chơi hay lắm");
        vi.put("nt", "suýt nữa thì được");
        vi.put("op", "quá mạnh");
        // 笑
        vi.put("hehe", "hehe");
        vi.put("haha", "haha");
        vi.put("lol", "haha");
        vi.put("lolol", "hahaha");
        vi.put("lololol", "hahahaha");
        vi.put("lmao", "cười chết mất");
        vi.put("lmfao", "cười không thở nổi");
        vi.put("rofl", "cười lăn lộn");
        // 状态 / 请求
        vi.put("brb", "quay lại ngay");
        vi.put("afk", "đang AFK");
        vi.put("gn", "ngủ ngon");
        vi.put("gm", "chào buổi sáng");
        vi.put("cya", "hẹn gặp lại");
        vi.put("gtg", "tôi phải đi rồi");
        vi.put("omw", "đang tới");
        vi.put("ily", "yêu bạn");
        vi.put("sup", "đang làm gì thế");
        vi.put("omg", "trời ơi");
        vi.put("bruh", "bro");
        vi.put("pls", "làm ơn");
        vi.put("lmk", "cho tôi biết");
        vi.put("wdym", "ý bạn là gì");
        vi.put("wym", "ý bạn là gì");
        vi.put("idc", "không quan tâm");
        vi.put("ikr", "đúng vậy");
        vi.put("ngl", "nói thật");
        vi.put("fr", "thật đấy");
        vi.put("rq", "ngay bây giờ");
        vi.put("asap", "càng sớm càng tốt");
        vi.put("imo", "theo tôi nghĩ");
        vi.put("tbh", "nói thật thì");
        vi.put("btw", "tiện thể");
        vi.put("idk", "không biết");
        vi.put("nvm", "thôi bỏ đi");
        vi.put("rn", "bây giờ");
        vi.put("ig", "tôi đoán");
        // 交易 / 组队
        vi.put("wts", "cần bán");
        vi.put("wtb", "cần mua");
        vi.put("lf", "tìm");
        vi.put("lfm", "tìm người chơi cùng");
        vi.put("lfp", "tìm người vào nhóm");
        // 动作 / 表情
        vi.put("o/", "vẫy tay chào");
        vi.put("o7", "chào kiểu nghiêm");
        // MIA 专用
        vi.put("l1", "tầng 1");
        vi.put("l2", "tầng 2");
        vi.put("l3", "tầng 3");
        vi.put("gondola", "lồng treo");
        vi.put("gondolas", "lồng treo");
        vi.put("nailong", "Nailong");
        vi.put("liyu", "cá chép");
        all.put("越南语", vi);

        return all;
    }

    // ---------------- 种子 ----------------

    private static Map<String, String> seedTerms() {
        Map<String, String> m = new LinkedHashMap<>();
        // 礼貌 / 常用
        m.put("wb", "欢迎回来");
        m.put("ty", "谢谢");
        m.put("thx", "谢谢");
        m.put("tysm", "非常感谢");
        m.put("yw", "不客气");
        m.put("np", "没问题");
        m.put("sry", "抱歉");
        m.put("mb", "我的错");
        m.put("alr", "好的");   // 用户给的：alr = alright（表里一加，入站提示 + 朗读展开都跟着生效）
        // 游戏
        m.put("gg", "打得好/恭喜/祝贺");
        m.put("gj", "干得好");
        m.put("nc", "不错");
        // 恭喜一族 —— MIA 里大量出现（拿到遗物 / 升层 / 开图）。
        // 实测证据：某次「Lysawen 拿到 Grade IV 遗物」之后紧跟着 FuSH_233 发的就是 g / gg。
        // 中文侧把「恭喜 / 祝贺 / 道喜」都写上 —— 用户打哪个都能反查出缩写。
        m.put("congrats", "恭喜/祝贺/道喜");
        m.put("congratz", "恭喜/祝贺/道喜");
        m.put("congrat", "恭喜/祝贺/道喜");
        m.put("grats", "恭喜/祝贺/道喜");
        m.put("gratz", "恭喜/祝贺/道喜");
        m.put("gz", "恭喜/祝贺/道喜");
        m.put("ez", "轻松拿下");
        m.put("pog", "太强了");
        m.put("rip", "节哀");
        // 笑：强度用「哈」的字数分档
        // lol 和 lmao 的唯一区别是强度，所以译法必须拉开档次，不能都写成「哈哈」
        m.put("hehe", "嘿嘿");
        m.put("haha", "哈哈");
        m.put("lol", "哈哈");
        // lolol 是 l/o 交替，没有连续相同字符，叠词归一化碰不到，必须单独收词条
        m.put("lolol", "哈哈哈哈哈");
        m.put("lololol", "哈哈哈哈哈");
        m.put("lmao", "笑死");
        m.put("lmfao", "笑不活了");
        m.put("rofl", "笑到打滚");
        m.put("brb", "马上回来");
        m.put("afk", "挂机中");
        m.put("o/", "挥手打招呼");
        m.put("o7", "敬礼");
        // 打招呼：整句快译（零成本）。
        // 实测有人只发 gn —— 不收录的话每次都要调一次 API 才出一个「晚安」，白花钱还慢。
        // 判据：**这些缩写本身能独立成一句**。像 gonna / cuz / dont 这种语法词就不收。
        m.put("gn", "晚安");
        m.put("gm", "早上好");
        m.put("cya", "再见");
        m.put("gtg", "我得走了");
        m.put("omw", "在路上");
        m.put("ily", "爱你");
        m.put("wp", "打得好");
        m.put("nt", "差一点");
        m.put("sup", "在干嘛");
        m.put("omg", "天啊");
        m.put("bruh", "兄弟");
        m.put("pls", "求你了");
        m.put("lmk", "告诉我");
        m.put("wdym", "什么意思");
        m.put("wym", "什么意思");
        m.put("idc", "不在乎");
        m.put("ikr", "就是说");
        m.put("ngl", "说实话");
        m.put("fr", "真的");
        m.put("rq", "马上");
        m.put("asap", "尽快");
        // 交易
        m.put("wts", "要卖");
        m.put("wtb", "要买");
        m.put("lf", "找");
        m.put("lfm", "找人组队");
        m.put("lfp", "找人进队");
        // 缩写
        m.put("imo", "我觉得");
        m.put("tbh", "说实话");
        m.put("btw", "顺便说");
        m.put("idk", "不知道");
        m.put("nvm", "算了");
        m.put("rn", "现在");
        m.put("ig", "我猜");
        m.put("op", "太强了");
        // MIA 相关
        m.put("l1", "第一层");
        m.put("l2", "第二层");
        m.put("l3", "第三层");
        // MIA 的设施名 —— 用户给的：gondola 是深渊里的吊笼（上下层坐的那个）
        // 这类词机翻会翻成"威尼斯小船"✗，必须进表。
        m.put("gondola", "吊笼");
        m.put("gondolas", "吊笼");
        m.put("nailong", "奶龙");
        m.put("liyu", "鲤鱼");
        return m;
    }

    /**
     * <b>英→英 展开表的内置种子</b> —— 外服聊天里最常见的口语缩写。
     *
     * <p>两个方向都用它：入站当术语提示（让模型看懂缩写再翻），
     * 出站当"该用哪些简写"的清单（让模型写出口语，而不是教科书英文）。
     *
     * <p>你可以在 {@code config/chatpatches/glossary.json} 的 {@code expand} 段里随意增删 ——
     * 这里只负责<b>补缺失的</b>，不会覆盖你改过的。
     */
    private static Map<String, String> seedExpand() {
        Map<String, String> m = new LinkedHashMap<>();
        // 人称 / 所有格
        m.put("u", "you");
        m.put("ur", "your");
        m.put("urs", "yours");
        m.put("alr", "alright");   // 用户给的：alr = alright
        m.put("im", "I am");
        m.put("ive", "I have");
        m.put("dont", "do not");
        m.put("cant", "cannot");
        m.put("wont", "will not");
        m.put("doesnt", "does not");
        m.put("didnt", "did not");
        // 请求 / 询问
        m.put("lmk", "let me know");
        m.put("lemme", "let me");
        m.put("gimme", "give me");
        m.put("pls", "please");
        m.put("wdym", "what do you mean");
        m.put("wym", "what do you mean");
        m.put("sup", "what's up");
        m.put("idk", "I don't know");
        m.put("idc", "I don't care");
        m.put("ikr", "I know, right");
        m.put("ig", "I guess");
        m.put("nvm", "never mind");
        // 交易 / 组队
        m.put("lf", "looking for");
        m.put("lfm", "looking for more");
        m.put("lfp", "looking for party");
        m.put("wts", "want to sell");
        m.put("wtb", "want to buy");
        // 语气 / 态度
        m.put("ngl", "not gonna lie");
        m.put("tbh", "to be honest");
        m.put("imo", "in my opinion");
        m.put("btw", "by the way");
        m.put("fr", "for real");
        m.put("smh", "shaking my head");
        m.put("bruh", "bro");
        m.put("omg", "oh my god");
        // 时间
        m.put("rn", "right now");
        m.put("rq", "real quick");
        m.put("asap", "as soon as possible");
        m.put("omw", "on my way");
        // 口语连读
        m.put("gonna", "going to");
        m.put("wanna", "want to");
        m.put("gotta", "got to");
        m.put("kinda", "kind of");
        m.put("sorta", "sort of");
        m.put("cuz", "because");
        m.put("bc", "because");
        // 寒暄 / 礼貌
        m.put("ty", "thank you");
        m.put("thx", "thanks");
        m.put("tysm", "thank you so much");
        m.put("yw", "you're welcome");
        m.put("np", "no problem");
        m.put("sry", "sorry");
        m.put("mb", "my bad");
        m.put("wb", "welcome back");
        m.put("gn", "good night");
        m.put("gm", "good morning");
        m.put("cya", "see you");
        m.put("gtg", "got to go");
        m.put("brb", "be right back");
        m.put("afk", "away from keyboard");
        m.put("ily", "I love you");
        // 游戏
        m.put("gg", "good game / congrats");
        m.put("gj", "good job");
        m.put("nc", "nice");
        m.put("congrats", "congratulations");
        m.put("congratz", "congratulations");
        m.put("congrat", "congratulations");
        m.put("grats", "congratulations");
        m.put("gratz", "congratulations");
        m.put("gz", "congratulations");
        m.put("ez", "easy");
        m.put("op", "overpowered");
        m.put("rip", "rest in peace");
        m.put("wp", "well played");
        m.put("nt", "nice try");
        m.put("lol", "laughing out loud");
        m.put("lmao", "laughing my ass off");
        m.put("lmfao", "laughing my fucking ass off");
        m.put("rofl", "rolling on the floor laughing");
        // MIA 层级（出站让模型写 l1/l2/l3，入站也看得懂）
        m.put("l1", "layer 1");
        m.put("l2", "layer 2");
        m.put("l3", "layer 3");
        return m;
    }

    private static Set<String> seedKeep() {        Set<String> s = new LinkedHashSet<>();
        s.add("awa");
        s.add("qwq");
        s.add("quq");
        s.add("qaq");
        s.add("qeq");
        s.add("qoq");
        s.add("ovo");
        s.add("uwu");
        s.add("uvu");
        s.add("0w0");
        s.add("0v0");
        s.add("tvt");
        s.add("t_t");
        s.add("o_o");
        s.add("orz");
        s.add("otz");
        s.add("otl");
        s.add("233");
        s.add("ww");
        s.add("www");
        s.add("xd");
        return s;
    }
}
