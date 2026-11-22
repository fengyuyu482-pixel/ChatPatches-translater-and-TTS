package obro1961.chatpatches.tts;

import java.util.Locale;

/**
 * 在线 TTS 的"按语言选嗓音"。
 *
 * <p>配置里的 {@code voice} 可以是一句话（固定嗓音），也可以是
 * {@code "zh=zh_CN-huayan-medium;en=en_US-lessac-medium"} 这样的映射：
 * 中文消息用中文嗓音、英文消息用英文嗓音。
 *
 * <p>为什么要这个：一个 provider 通常同时支持多语言（比如本地 Piper 服务），
 * 但固定嗓音会导致"中文用英文嗓音念"或反过来。映射后，同一个 provider 就能
 * 按消息语言自动切换，不用为每种语言各配一条 provider。
 *
 * <p>解析规则：
 * <ul>
 *   <li>没有 {@code =} → 原样返回（固定嗓音，兼容旧配置）；</li>
 *   <li>有 {@code =} → 按 {@code ;} 或 {@code ,} 切分，键取语言前缀（{@code zh-CN} → {@code zh}）；</li>
 *   <li>找不到对应语言 → 用 {@code default=} 项；再没有就用第一项。</li>
 * </ul>
 */
public final class VoiceSpec {

    private VoiceSpec() {
    }

    /** 按语言取嗓音；{@code lang} 传 {@code zh-CN}/{@code en-US} 这类都行。 */
    public static String pick(String spec, String lang) {
        if (spec == null || spec.isBlank()) {
            return spec;
        }
        String s = spec.trim();
        if (s.indexOf('=') < 0) {
            return s;
        }
        String want = norm(lang);
        String first = null;
        String fallback = null;
        for (String part : s.split("[;,]")) {
            int i = part.indexOf('=');
            if (i <= 0) {
                continue;
            }
            String k = norm(part.substring(0, i));
            String v = part.substring(i + 1).trim();
            if (v.isEmpty()) {
                continue;
            }
            if (first == null) {
                first = v;
            }
            if (k.equals("default") || k.equals("all") || k.equals("*")) {
                fallback = v;
            }
            if (k.equals(want)) {
                return v;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        return first == null ? s : first;
    }

    private static String norm(String lang) {
        if (lang == null) {
            return "";
        }
        String t = lang.trim().toLowerCase(Locale.ROOT);
        int cut = t.length();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '-' || c == '_' || c == ' ') {
                cut = i;
                break;
            }
        }
        return t.substring(0, cut);
    }
}