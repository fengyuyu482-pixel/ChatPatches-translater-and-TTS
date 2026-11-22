package obro1961.chatpatches.tts;

import obro1961.chatpatches.translate.Glossary;
import obro1961.chatpatches.translate.ScriptDetector;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 朗读规则 —— <b>哪条消息该读出声</b>。
 *
 * <p>核心规则（用户定的）：<b>只读"需要翻译的那些"原文。</b>
 *
 * <pre>
 * 目标语言 = 简体中文
 *   → 英文消息：需要翻译 → 读它的原文 ✅（练英语听力）
 *   → 中文消息：不需要翻译 → 不读 ✅（不然很吵）
 *
 * 以后目标语言改成 English
 *   → 规则自动反过来，变成"读所有非英文的原文"
 * </pre>
 *
 * <p>这条规则<b>跟具体语言无关</b> —— 它跟着配置里的目标语言自动变。
 */
public final class SpeechPolicy {

    /** 太短的（"k"、"gg"）读出来没意义。 */
    public static final int DEFAULT_MIN_LENGTH = 2;

    /** 太长的不读（多半是系统播报）。 */
    public static final int DEFAULT_MAX_LENGTH = 300;

    /**
     * 内置表情表 —— 这些"不是话"，不读。
     *
     * <p>必须有这一份内置的：<b>不能指望用户文件一定存在</b>（首次启动、文件丢了、
     * 用户把不翻译表清空了…），否则一进服就被 "awa" 刷屏。
     * 用户还能在术语表的"不翻译表"里<b>追加</b>自己的。
     */
    private static final java.util.Set<String> BUILTIN_EMOTICONS = java.util.Set.of(
            "awa", "qwq", "quq", "qaq", "qvq", "twt", "uwu", "owo",
            "orz", "otz", "233", "2333", "23333", "www", "hhh");

    /** 纯规则判断，无状态 —— 好测。 */
    public static boolean shouldSpeak(String text, String targetLanguage,
                                      boolean enabled, boolean readOriginal,
                                      int minLength, int maxLength) {
        return shouldSpeak(text, targetLanguage, enabled, readOriginal,
                minLength, maxLength, false);
    }

    /**
     * 全参数版：多一个 {@code onlyEnglish}（用户要求「只读英语」）。
     *
     * <p>{@code onlyEnglish = true} 时<b>只念英语</b> ✓ —— 别的原文一律不念 ✗。
     * 为什么：嗓音只有 {@code zh}/{@code en} 两档 ✗，
     * 拿英文嗓音念越南语/俄语就是怪声（用户实测听过那种声 ✓）。
     *
     * <p>判据见 {@link #isEnglishText}：<b>拉丁字母 + 没有变音符号</b> ✓
     * （越南语是拉丁字母 ✗，必须靠变音符排除 ✓）
     */
    public static boolean shouldSpeak(String text, String targetLanguage,
                                      boolean enabled, boolean readOriginal,
                                      int minLength, int maxLength, boolean onlyEnglish) {
        if (!enabled) {
            return false;
        }
        if (text == null) {
            return false;
        }
        String s = text.trim();
        if (s.length() < minLength || s.length() > maxLength) {
            return false;
        }
        // ★ 只读英语（用户要求）—— 放在表情判断之前：非英语直接不读，省得白算
        // onlyEnglish 过滤已移除（2026-10-26 用户要求）：该参数保留只为兼容旧签名
        // ★ 表情 / 拟声不读。两道保险：
        //   ① 内置表（不依赖任何文件）
        //   ② 术语表的"不翻译表"（用户可追加）—— 一处维护，翻译和朗读自动一致
        if (BUILTIN_EMOTICONS.contains(s.toLowerCase(java.util.Locale.ROOT))) {
            return false;
        }
        if (Glossary.lookup(s).keepOriginal()) {
            return false;
        }
        // 有"能读出来的东西"才读 —— 交给 ScriptDetector.Profile 统一判断（数字算数）。
        // 这里以前自己写了一遍"字母/假名/韩文/西里尔/汉字"，漏了数字，
        // 和"数字读英文"的规则打架。现在全项目只有一处判断文本构成。
        if (!ScriptDetector.profile(s).hasSpeakable()) {
            return false;
        }
        return true;
    }

    public static boolean shouldSpeak(String text, String targetLanguage, boolean enabled) {
        return shouldSpeak(text, targetLanguage, enabled, true, DEFAULT_MIN_LENGTH, DEFAULT_MAX_LENGTH);
    }

    /**
     * <b>这段文本是不是"英语"</b> —— 朗读"只读英语"用的判据。
     *
     * <p>两条都要满足：
     * <ol>
     *   <li>{@link ScriptDetector#profile} 认为是拉丁字母，且没有汉字/假名/谚文/西里尔 ✓</li>
     *   <li><b>没有变音符号</b>（{@code à é ơ ư ñ ö} …）✗ ——
     *       这是关键 ✗：<b>越南语就是拉丁字母</b>，光看"是不是拉丁"会把
     *       {@code xin chào} 当成英语 ✗，于是又会用英文嗓音念越南语 ✓（正是要避免的）</li>
     * </ol>
     *
     * <p>顺带：{@code café} 也不算英语 ✓（有 é）—— 用英文嗓音念它同样是怪声 ✓
     */
    public static boolean isEnglishText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        ScriptDetector.Profile p = ScriptDetector.profile(text);
        if (!p.latin() || p.han() || p.kana() || p.hangul() || p.cyrillic()) {
            return false;
        }
        return !ScriptDetector.hasAccentedLatinLetters(text);
    }

    /**
     * 内置表情 / 拟声（{@code awa}、{@code qwq}、{@code hhh} …）—— 念出来没意义。
     *
     * <p>这是内置的那份；玩家在术语表里额外列的走 {@link Glossary#keepOriginal()}。
     * 两处判断一起用在 {@code ChatStudyPipeline.planSpeech} 里 —— 合并决策时漏搬过这一条，
     * 结果 {@code awa} 被念了出来，被测试当场抓住。
     */
    public static boolean isEmoticon(String s) {
        return s != null && BUILTIN_EMOTICONS.contains(s.trim().toLowerCase(java.util.Locale.ROOT));
    }

    // ---- 防重复（同一个消息被处理两次时别读两遍）----

    private final Map<String, Long> recentlySpoken = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 256;
        }
    };

    private final long dedupeWindowMs;

    public SpeechPolicy() {
        this(4000);
    }

    /** @param dedupeWindowMs 这么多毫秒内，同样的话不重复读 */
    public SpeechPolicy(long dedupeWindowMs) {
        this.dedupeWindowMs = dedupeWindowMs;
    }

    /** 带防重复判断。同一句话在窗口内只会放行一次。 */
    public boolean allow(String text) {
        if (text == null) {
            return false;
        }
        String key = text.trim();
        long now = System.currentTimeMillis();
        Long last = recentlySpoken.get(key);
        if (last != null && now - last < dedupeWindowMs) {
            return false;
        }
        recentlySpoken.put(key, now);
        return true;
    }

    public void reset() {
        recentlySpoken.clear();
    }

    /** 便于统计。 */
    public Map<String, Long> snapshot() {
        return new HashMap<>(recentlySpoken);
    }
}
