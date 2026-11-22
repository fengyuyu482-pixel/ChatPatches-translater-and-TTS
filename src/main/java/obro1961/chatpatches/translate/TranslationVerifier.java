package obro1961.chatpatches.translate;

import static obro1961.chatpatches.ChatPatches.LOGGER;

import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>译文质量校验</b> —— 发出去之前的最后一道闸。
 *
 * <h2>为什么必须有</h2>
 * <p>模型返回的东西不能直接上屏。实测过的翻车形态：
 * <ul>
 *   <li><b>原样返回</b>：没翻，但代码以为翻好了 → 屏幕上多一行一模一样的</li>
 *   <li><b>漏译</b>：长句只翻了前半截</li>
 *   <li><b>自我解释</b>：输出「这句话的意思是…」「Here is the translation:」</li>
 *   <li><b>吃掉占位符</b>：{@code @Bob} 消失、坐标不见了</li>
 *   <li><b>语言跑偏</b>：目标是中文却回了一串英文（或反过来）</li>
 *   <li><b>退化重复</b>：同一个词连续刷 10 遍</li>
 *   <li><b>背术语表</b>：把 prompt 里的简写清单当译文吐出来（真实事故）</li>
 * </ul>
 *
 * <h2>六项检查</h2>
 * <table border=1>
 *   <caption>检查项与判据</caption>
 *   <tr><th>#</th><th>检查</th><th>判据</th><th>不过怎么办</th></tr>
 *   <tr><td>1</td><td>占位符完整</td><td>发出几个就回几个，且不残留</td><td>作废（模型吃了内容）</td></tr>
 *   <tr><td>2</td><td>数字一致</td><td>原文的每个数字都要在译文里出现</td><td>作废</td></tr>
 *   <tr><td>3</td><td>目标脚本</td><td>目标是中文就必须有汉字；目标是英文就不能全是汉字</td><td>作废</td></tr>
 *   <tr><td>4</td><td>长度比</td><td>输出 / 输入 在 0.25 ~ 4 之间</td><td>作废（漏译或幻觉）</td></tr>
 *   <tr><td>5</td><td>自我解释</td><td>不含「意思是」「翻译为」「Here is」</td><td>作废</td></tr>
 *   <tr><td>6</td><td>退化重复</td><td>同一词连续 ≥5 次</td><td>作废</td></tr>
 * </table>
 *
 * <h2>为什么是"作废"而不是"重试"</h2>
 * <p>重试要再花一次调用、再等一次超时。聊天场景里<b>迟到的正确译文不如没有</b> ——
 * 用户早就翻过那段了。所以这里只判断"能不能发"，不能发就返回 {@code null}，
 * 由调用方丢弃这一条（发原文，或干脆不显示）。重试交给降级链去换下一个源。
 */
public final class TranslationVerifier {

    /** 校验不通过的原因码，给日志用。 */
    public enum Reject {
        OK(null),
        PLACEHOLDER("占位符缺失或被改"),
        NUMBER("数字对不上"),
        SCRIPT("目标文字系统不符"),
        LENGTH("长度异常（疑似漏译或幻觉）"),
        META("含自我解释"),
        DEGENERATE("退化重复"),
        EMPTY("译文为空");

        public final String label;

        Reject(String label) {
            this.label = label;
        }
    }

    /** 自我解释的典型开头。 */
    private static final Pattern META = Pattern.compile(
            "(?i)(意思是|翻译为|译为|以下是|下面是|这意味着|"
                    + "here is|here's|the translation|translation:|"
                    + "this means|in english|in chinese|"
                    + "抱歉|对不起.*无法|as an ai)");

    /**
     * 校验一条译文。
     *
     * @param source      原文（遮罩<b>之前</b>的原样文本，用于数字/长度比对）
     * @param translated  译文（<b>已经回填占位符</b>的最终文本）
     * @param targetLang  目标语言（配置里的写法，如 {@code fr_ca} / 简体中文）
     * @return {@link Reject#OK} 表示可以用；其它值表示该丢弃
     */
    public static Reject check(String source, String translated, String targetLang) {
        if (translated == null || translated.isBlank()) {
            return Reject.EMPTY;
        }
        String out = translated.trim();

        // ① 占位符 —— restore 阶段已经拦过一次，这里再兜一遍（防止调用方忘了 restore）
        if (TextProtector.hasLeftoverMarkers(out)) {
            return Reject.PLACEHOLDER;
        }

        // ② 数字一致 —— 原文里出现的每个数字都得在译文里能找到
        if (!numbersPreserved(source, out)) {
            return Reject.NUMBER;
        }

        // ③ 目标文字系统
        if (!scriptMatches(out, targetLang)) {
            return Reject.SCRIPT;
        }

        // ④ 长度 —— 不检查。见 lengthOk 的说明。

        // ⑤ 自我解释
        if (META.matcher(out).find()) {
            return Reject.META;
        }

        // ⑥ 退化重复
        if (degenerate(out)) {
            return Reject.DEGENERATE;
        }

        return Reject.OK;
    }

    /**
     * 校验并打日志。
     *
     * @return 可以发的译文；不合格返回 {@code null}
     */
    public static String checkAndLog(String source, String translated, String targetLang, String who) {
        Reject r = check(source, translated, targetLang);
        if (r == Reject.OK) {
            return translated;
        }
        LOGGER.warn("[chatpatch] {} 译文未通过校验（{}），本条丢弃。原文=[{}] 译文=[{}]",
                who, r.label, shorten(source), shorten(translated));
        return null;
    }

    // ==================== 各项检查 ====================

    /**
     * 原文里的数字是否都还在。
     *
     * <p>只比"个数"不比"位置" —— 语序会变。但个数少了就说明吃掉了。
     * 注意：{@link TextProtector} 遮罩过的数字会在回填后回来，
     * 所以这里比对的是<b>回填之后</b>的最终文本。
     */
    static boolean numbersPreserved(String source, String translated) {
        if (source == null) {
            return true;
        }
        List<String> srcNums = extractNumbers(source);
        if (srcNums.isEmpty()) {
            return true;
        }
        List<String> outNums = extractNumbers(translated);
        // 逐个消耗：原文有几个，译文里至少得有那么多
        for (String n : srcNums) {
            if (!outNums.remove(n)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> extractNumbers(String s) {
        List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = Pattern.compile("\\d+(?:[.,]\\d+)*").matcher(s);
        while (m.find()) {
            // 千分位统一去掉，避免 "1,000" 和 "1000" 判不等
            out.add(m.group().replace(",", "").replace(".", ""));
        }
        return out;
    }

    /**
     * 译文是否符合目标语言的文字系统。
     *
     * <p>只做"明显不对"的判断 —— 目标是中文却一个汉字都没有，那是肯定错了；
     * 目标是英文却全是汉字，也肯定错了。不做细粒度判断（那交给模型）。
     */
    static boolean scriptMatches(String translated, String targetLang) {
        LanguageRegistry.Lang lang = Language.find(targetLang);
        if (lang == null) {
            return true;   // 认不出目标语言 → 不拦（别因为注册表缺项挡住翻译）
        }
        String script = lang.script == null ? "" : lang.script;
        String t = translated;

        if ("cmn".equalsIgnoreCase(script) || "Han".equalsIgnoreCase(script)) {
            return ScriptDetector.profile(t).han();
        }
        if ("Latin".equalsIgnoreCase(script)) {
            // 拉丁语言：不能全是汉字/假名/谚文/西里尔
            ScriptDetector.Profile p = ScriptDetector.profile(t);
            return !(p.han() || p.kana() || p.hangul() || p.cyrillic()) || p.latin();
        }
        if ("Cyrillic".equalsIgnoreCase(script)) {
            return ScriptDetector.profile(t).cyrillic() || ScriptDetector.profile(t).latin();
        }
        if ("jpn".equalsIgnoreCase(script)) {
            ScriptDetector.Profile p = ScriptDetector.profile(t);
            return p.kana() || p.han();
        }
        if ("kor".equalsIgnoreCase(script)) {
            return ScriptDetector.profile(t).hangul();
        }
        // 其它 script：不做限制（数据少，误判代价大）
        return true;
    }

    /**
     * <b>长度比 —— 已停用，永不拦截。</b>
     *
     * <h2>为什么删掉</h2>
     * <p>原来按「译文字符数 / 原文字符数」设了 0.25 下限，想拦"漏译大半"。
     * 实测<b>误杀严重</b>：英译中的压缩比天然就小，正常译文全被拦在门外。
     * <pre>
     *   holy shit   (9 字符) → 卧槽    (2 字符)  比例 0.22  ❌ 被拦（实际完全正确）
     *   oh my god   (9 字符) → 天哪    (2 字符)  比例 0.22  ❌
     *   good morning(12 字符) → 早上好  (3 字符)  比例 0.25  勉强过
     * </pre>
     * 根因：<b>中文信息密度远高于英文</b>，一个汉字常对应两三个字母。
     * 用统一的字符数比例衡量跨语言长度，本身就不成立。
     *
     * <p>而它想解决的问题（漏译）本来就很少见 —— 聊天句子短，模型要么全翻要么不翻；
     * 真漏了的，{@link #numbersPreserved} 和 {@link #scriptMatches} 更可能先发现。
     * 与其误杀大量正常译文，不如不拦。
     *
     * <p>保留这个方法只为说明历史决策，<b>不再被调用</b>。
     */
    @Deprecated
    static boolean lengthOk(String source, String translated) {
        return true;
    }

    /**
     * 退化重复：同一个词连续出现 ≥5 次。
     *
     * <p>模型卡住时会长这样：{@code "the the the the the"} 或 {@code "是的 是的 是的 是的 是的"}。
     */
    static boolean degenerate(String s) {
        if (s == null) {
            return false;
        }
        String[] parts = s.split("\\s+");
        if (parts.length < 5) {
            // 没有空格的语言（中日韩）—— 按 2~4 字符的片段查连续重复
            return degenerateNoSpace(s);
        }
        int run = 1;
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].equalsIgnoreCase(parts[i - 1]) && !parts[i].isBlank()) {
                run++;
                if (run >= 5) {
                    return true;
                }
            } else {
                run = 1;
            }
        }
        return false;
    }

    private static boolean degenerateNoSpace(String s) {
        // 找 1~4 字的重复单元连续出现 ≥5 次
        for (int len = 1; len <= 4 && len * 5 <= s.length(); len++) {
            String unit = s.substring(0, len);
            int count = 0;
            int i = 0;
            while (i + len <= s.length() && s.regionMatches(i, unit, 0, len)) {
                count++;
                i += len;
            }
            if (count >= 5 && unit.trim().length() > 0) {
                return true;
            }
        }
        return false;
    }

    private static String shorten(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
