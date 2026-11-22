package obro1961.chatpatches.tts;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一句话按“字符体系”切成若干段，每段带一个语言码，供多语言 TTS 使用。
 *
 * <p>例：{@code 这局 GG 了，next round 再来} →
 * {@code [这局(zh), GG(next)(en), 了，(zh), 再来(zh)]}（相邻同语言会合并）。
 *
 * <p>切分粒度是“连续同语言片段”，不是逐字，避免过度切换。
 */
public final class SpeechSegmenter {

    /** 一段文本 + 语言码。 */
    public record Segment(String text, String lang) {
    }

    /** 小于这个长度的片段会被并入相邻段，避免“ei”“OK”被单独切出来。 */
    /** 少于这个字母数（含汉字）的片段会被并入更长的邻居。 */
    private static final int MIN_LETTERS = 1;

    private SpeechSegmenter() {
    }

    public static List<Segment> split(String text) {
        return split(text, null);
    }

    /**
     * 按“对话语言集”切分：只在这些语言之间换段，其它文字归到第一个语言。
     *
     * @param allowed 允许的语言码（来自翻译配置）；null/空 = 全自动检测
     */
    public static List<Segment> split(String text, java.util.Set<String> allowed) {
        List<Segment> raw = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return raw;
        }
        StringBuilder buf = new StringBuilder();
        String cur = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String cls = classify(c);
            if (cls == null) {
                // 空格/标点/emoji 不计入语言，跟着当前段走
                if (buf.length() > 0) {
                    buf.append(c);
                }
                continue;
            }
            if (cur == null) {
                cur = cls;
            } else if (!cur.equals(cls)) {
                raw.add(new Segment(buf.toString(), cur));
                buf.setLength(0);
                cur = cls;
            }
            buf.append(c);
        }
        if (buf.length() > 0 && cur != null) {
            raw.add(new Segment(buf.toString(), cur));
        }
        if (Boolean.getBoolean("chatstudy.segdebug")) {
            for (Segment s : raw) {
                System.out.println("raw [" + s.lang() + "] " + s.text());
            }
        }
        List<Segment> merged = refineLatin(mergeShort(raw));
        return allowed == null || allowed.isEmpty() ? merged : constrain(merged, allowed);
    }

    /** 连续同语言合并；过短片段并入“更长的邻居”，语言跟邻居走。 */
    private static List<Segment> mergeShort(List<Segment> in) {
        List<Segment> out = new ArrayList<>();
        for (Segment s : in) {
            if (!out.isEmpty() && out.get(out.size() - 1).lang().equals(s.lang())) {
                Segment last = out.remove(out.size() - 1);
                out.add(new Segment(last.text() + s.text(), last.lang()));
            } else {
                out.add(s);
            }
        }
        for (int i = 0; i < out.size() && out.size() > 1; i++) {
            Segment s = out.get(i);
            if (letters(s.text()) > MIN_LETTERS) {
                continue;
            }
            int prev = i - 1;
            int next = i + 1;
            int target;
            if (prev >= 0 && next < out.size()) {
                target = letters(out.get(prev).text()) >= letters(out.get(next).text()) ? prev : next;
            } else if (prev >= 0) {
                target = prev;
            } else {
                target = next;
            }
            Segment t = out.get(target);
            String merged = target < i ? t.text() + s.text() : s.text() + t.text();
            out.set(target, new Segment(merged, t.lang()));
            out.remove(i);
            i = Math.max(-1, Math.min(target, i) - 1);
        }
        // 收尾再合并一次相邻同语言（合并过程中可能产生新的邻接）
        List<Segment> fin = new ArrayList<>();
        for (Segment s : out) {
            if (!fin.isEmpty() && fin.get(fin.size() - 1).lang().equals(s.lang())) {
                Segment last = fin.remove(fin.size() - 1);
                fin.add(new Segment(last.text() + s.text(), last.lang()));
            } else {
                fin.add(s);
            }
        }
        return fin;
    }

    private static int letters(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }
    /**
     * 拉丁字母段的语言细化 —— 直接复用翻译那边的 ScriptDetector，
     * 不再另写一套判断（越南语声调符号、西语倒问号、法语 ç/œ 这些记号它已经认了）。
     */
    private static List<Segment> refineLatin(List<Segment> in) {
        List<Segment> out = new ArrayList<>();
        for (Segment s : in) {
            if (!"en".equals(s.lang())) {
                out.add(s);
                continue;
            }
            String refined;
            if (obro1961.chatpatches.translate.ScriptDetector.isVietnamese(s.text())) {
                refined = "vi";
            } else if (obro1961.chatpatches.translate.ScriptDetector.hasSpanishMarks(s.text())) {
                refined = "es";
            } else if (obro1961.chatpatches.translate.ScriptDetector.hasFrenchMarks(s.text())) {
                refined = "fr";
            } else {
                refined = "en";
            }
            out.add(new Segment(s.text(), refined));
        }
        return out;
    }
    /** 把语言集之外的语言并到集内第一个（通常是源语言），不新增语言。 */
    private static List<Segment> constrain(List<Segment> in, java.util.Set<String> allowed) {
        String fallback = allowed.iterator().next();
        List<Segment> out = new ArrayList<>();
        for (Segment s : in) {
            String lang = allowed.contains(s.lang()) ? s.lang() : fallback;
            if (!out.isEmpty() && out.get(out.size() - 1).lang().equals(lang)) {
                Segment last = out.remove(out.size() - 1);
                out.add(new Segment(last.text() + s.text(), lang));
            } else {
                out.add(new Segment(s.text(), lang));
            }
        }
        return out;
    }
    /** 返回语言码（zh/en/ko/ja/ru/other），空白与标点返回 null。 */
    public static String classify(char c) {
        if (Character.isWhitespace(c)) {
            return null;
        }
        if (isCjk(c)) {
            return "zh";
        }
        if (isHangul(c)) {
            return "ko";
        }
        if (isKana(c)) {
            return "ja";
        }
        if (isCyrillic(c)) {
            return "ru";
        }
        String byScript = SCRIPTS.get(Character.UnicodeScript.of(c));
        if (byScript != null) {
            return byScript;
        }
        if (Character.isLetter(c)) {
            return "en";
        }
        return null; // 标点、数字、emoji
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript s = Character.UnicodeScript.of(c);
        return s == Character.UnicodeScript.HAN;
    }

    private static boolean isKana(char c) {
        Character.UnicodeScript s = Character.UnicodeScript.of(c);
        return s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA;
    }

    private static boolean isHangul(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL;
    }

    private static boolean isCyrillic(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC;
    }

    /** 其它文字体系 → 语言码。拉丁字母一律 en（无法只凭字母区分西/法/德）。 */
    private static final java.util.Map<Character.UnicodeScript, String> SCRIPTS = new java.util.HashMap<>();

    static {
        SCRIPTS.put(Character.UnicodeScript.THAI, "th");
        SCRIPTS.put(Character.UnicodeScript.ARABIC, "ar");
        SCRIPTS.put(Character.UnicodeScript.HEBREW, "he");
        SCRIPTS.put(Character.UnicodeScript.DEVANAGARI, "hi");
        SCRIPTS.put(Character.UnicodeScript.BENGALI, "bn");
        SCRIPTS.put(Character.UnicodeScript.TAMIL, "ta");
        SCRIPTS.put(Character.UnicodeScript.TELUGU, "te");
        SCRIPTS.put(Character.UnicodeScript.KANNADA, "kn");
        SCRIPTS.put(Character.UnicodeScript.MALAYALAM, "ml");
        SCRIPTS.put(Character.UnicodeScript.GUJARATI, "gu");
        SCRIPTS.put(Character.UnicodeScript.GURMUKHI, "pa");
        SCRIPTS.put(Character.UnicodeScript.SINHALA, "si");
        SCRIPTS.put(Character.UnicodeScript.GREEK, "el");
        SCRIPTS.put(Character.UnicodeScript.ARMENIAN, "hy");
        SCRIPTS.put(Character.UnicodeScript.GEORGIAN, "ka");
        SCRIPTS.put(Character.UnicodeScript.MYANMAR, "my");
        SCRIPTS.put(Character.UnicodeScript.KHMER, "km");
        SCRIPTS.put(Character.UnicodeScript.LAO, "lo");
        SCRIPTS.put(Character.UnicodeScript.TIBETAN, "bo");
        SCRIPTS.put(Character.UnicodeScript.MONGOLIAN, "mn");
        SCRIPTS.put(Character.UnicodeScript.ETHIOPIC, "am");
        SCRIPTS.put(Character.UnicodeScript.THAANA, "dv");
    }
}
