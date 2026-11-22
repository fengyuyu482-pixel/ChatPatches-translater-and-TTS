package obro1961.chatpatches.translate;

import static obro1961.chatpatches.ChatPatches.LOGGER;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>占位符保护</b> —— 把不该被翻译的东西换成 {@code ⟦0⟧} 这样的标记，翻完再换回来。
 *
 * <h2>为什么必须做这件事</h2>
 * <p>光在提示词里写「Keep player names unchanged」<b>不管用</b>：
 * 模型经常忽略，尤其术语多的时候；同一句这一次遵守、下次不遵守。
 * 而把这些东西<b>从字符串里拿走</b>，模型就<b>根本不知道原词是什么</b> —— 想违反也违反不了。
 *
 * <h2>保护什么</h2>
 * <table border=1>
 *   <caption>保护类型</caption>
 *   <tr><th>类型</th><th>例子</th><th>不保护的后果</th></tr>
 *   <tr><td>@提到</td><td>{@code @Bob_123}</td><td>翻成「在鲍勃」，失去 @ 功能</td></tr>
 *   <tr><td>命令</td><td>{@code /tp /home}</td><td>翻成「传送」，指令发不出去</td></tr>
 *   <tr><td>坐标</td><td>{@code 100 64 -200}</td><td>数字被翻乱，传送错位置</td></tr>
 *   <tr><td>数字</td><td>{@code 64} {@code 3.14}</td><td>数量/价格错乱</td></tr>
 *   <tr><td>链接</td><td>{@code https://...}</td><td>URL 被拆碎，点不开</td></tr>
 *   <tr><td>代码</td><td>{@code `code`} 或三反引号块</td><td>代码被翻译，失去意义</td></tr>
 *   <tr><td>自定义表情</td><td>{@code :kekw:}</td><td>表情名被翻，显示不出图标</td></tr>
 *   <tr><td>物品 ID</td><td>{@code minecraft:diamond_sword}</td><td>ID 被翻，命令失效</td></tr>
 * </table>
 *
 * <h2>标记形式为什么用 {@code ⟦0⟧}</h2>
 * <p>试过几种：{@code {{0}}} 在某些模型的 JSON 输出里会退化；{@code <0>} 会和 HTML 混淆。
 * {@code ⟦⟧}（U+27E6/U+27E7，数学双括号）是罕见的配对符号 ——
 * 模型不会自作主张删掉它，也不会把它当成标记语言。
 *
 * <h2>回填失败怎么办</h2>
 * <p>翻完必须<b>逐个找回</b>所有标记；少一个就说明模型吃掉了东西。
 * 这时 {@link #restore} 返回 {@code null}，调用方应<b>丢弃这条译文</b>（重试或发原文）——
 * 宁可这一条不翻，也不能把 {@code ⟦0⟧} 或残缺的句子发到公屏。
 */
public final class TextProtector {

    /** 标记的两端符号。 */
    private static final String OPEN = "\u27E6";
    private static final String CLOSE = "\u27E7";

    /** 一张保护表：原文片段 → 标记。 */
    public record Masked(String masked, List<String> tokens) {
        /** 有没有真的动了手。 */
        public boolean changed() {
            return !tokens.isEmpty();
        }
    }

    private TextProtector() {
    }

    /**
     * 保护规则，<b>顺序很重要</b>：
     * 长模式必须排在短模式前面，否则 {@code @Bob_123} 会被 {@code @Bob} 先吃掉一半，
     * {@code https://a.com`x`} 会被代码规则先切走。
     */
    private static final Pattern[] PATTERNS = {
            // ① 代码块（三反引号 + 单反引号）—— 最长，先来
            Pattern.compile("```[\\s\\S]*?```|`[^`\\n]+`"),
            // ② 链接
            Pattern.compile("https?://\\S+"),
            // ③ 命令：/tp、/home set 这种（前面不能是字母，否则 "and/or" 会被误伤）
            Pattern.compile("(?<![\\w/])/[a-zA-Z][\\w:.-]*(?:\\s+[\\w:.@-]+)*"),
            // ④ @提到：MC 用户名 3-16 位，允许下划线
            Pattern.compile("@[A-Za-z0-9_]{2,16}"),
            // ⑤ 资源 ID：minecraft:diamond_sword / namespace:path
            Pattern.compile("\\b[a-z0-9_.-]+:[a-z0-9_./-]+\\b"),
            // ⑥ 自定义表情 :kekw:
            Pattern.compile(":[a-zA-Z0-9_+-]{2,32}:"),
            // ⑦ 坐标：三个数字，中间空格（100 64 -200 / ~ ~1 ~）
            Pattern.compile("[~-]?\\d{1,6}\\s+[~-]?\\d{1,3}\\s+[~-]?\\d{1,6}"),
            // ⑧ 数字（整数/小数/千分位）
            Pattern.compile("\\b\\d+(?:[.,]\\d+)*\\b"),
    };

    /**
     * 把会被翻译破坏的片段换成标记。
     *
     * @return 遮罩后的文本 + 标记表；没有任何命中时 {@code tokens} 为空、文本原样返回
     */
    public static Masked mask(String text) {
        if (text == null || text.isBlank()) {
            return new Masked(text, List.of());
        }
        List<String> tokens = new ArrayList<>();
        String work = text;

        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(work);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            while (m.find()) {
                String hit = m.group();
                // 已经被前面的规则换成标记的片段不会再被匹配（标记里没有内容）
                if (hit.isBlank()) {
                    continue;
                }
                sb.append(work, last, m.start());
                sb.append(OPEN).append(tokens.size()).append(CLOSE);
                tokens.add(hit);
                last = m.end();
            }
            sb.append(work, last, work.length());
            work = sb.toString();
        }

        return new Masked(work, tokens);
    }

    /**
     * 把标记换回原片段。
     *
     * @return 还原后的文本；<b>有任何一个标记找不回来就返回 {@code null}</b>
     *         （调用方应丢弃这条译文 —— 说明模型吃掉了内容）
     */
    public static String restore(String translated, List<String> tokens) {
        if (translated == null) {
            return null;
        }
        if (tokens == null || tokens.isEmpty()) {
            return translated;
        }
        String out = translated;
        for (int i = 0; i < tokens.size(); i++) {
            String marker = OPEN + i + CLOSE;
            if (!out.contains(marker)) {
                LOGGER.warn("[chatpatch] 译文里找不到标记 {}（模型可能吃掉了占位符），本条作废。原文片段=[{}]",
                        marker, tokens.get(i));
                return null;
            }
            // 用 replace 而不是 replaceAll —— 原始片段里可能有 $ 和 \
            out = out.replace(marker, tokens.get(i));
        }
        // 还剩标记 = 模型多造了几个，或编号对不上
        if (out.contains(OPEN) || out.contains(CLOSE)) {
            LOGGER.warn("[chatpatch] 译文里残留未知标记，本条作废：{}", out);
            return null;
        }
        return out;
    }

    /**
     * 检查有没有"模型自己编出来的"多余标记 —— 用于校验阶段。
     *
     * <p>{@link #restore} 已经会拦这种情况；这个方法是给 {@code TranslationVerifier}
     * 单独做检查用的（不依赖 restore 的副作用）。
     */
    public static boolean hasLeftoverMarkers(String text) {
        return text != null && (text.contains(OPEN) || text.contains(CLOSE));
    }

    /** 统计原文里的数字个数 —— 校验阶段比对用。 */
    public static int countNumbers(String text) {
        if (text == null) {
            return 0;
        }
        Matcher m = PATTERNS[7].matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
