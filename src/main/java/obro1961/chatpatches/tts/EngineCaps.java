package obro1961.chatpatches.tts;

import obro1961.chatpatches.translate.ApiProvider;

import java.util.Locale;
import java.util.Set;

/**
 * 引擎能力表：<b>静态声明</b>，不靠运行时试错。
 *
 * <p>这里回答三个问题：
 * <ul>
 *   <li>这引擎会哪些语言（{@link #langs}）</li>
 *   <li>要不要按语言切段（{@link #segment}）——只有“换语言=换音色/换嗓音”的引擎才需要</li>
 *   <li>能同时发几个请求（{@link #concurrency}）</li>
 * </ul>
 *
 * <p>实测依据（同一句混合文本）：
 * Piper 3 段并行 220ms、系统语音 3 段并行 0.74s、SoVITS 并行 4.9s（比整句慢）、
 * Kokoro 中文直接 500 → 只声明英文。
 */
public final class EngineCaps {

    /** 语言码集合。 */
    public record Caps(Set<String> langs, boolean segment, int concurrency) {

        public boolean supports(String lang) {
            return lang == null || langs.contains(lang);
        }
    }

    private static final Set<String> ALL = Set.of("zh", "en", "ja", "ko", "yue", "ru", "es", "fr", "de");

    private EngineCaps() {
    }

    /** 查表；未知类型按“全语言、整句、单发”处理。 */
    public static Caps of(ApiProvider p) {
        if (p == null || p.type == null) {
            return new Caps(ALL, false, 1);
        }
        return switch (p.type.toLowerCase(Locale.ROOT)) {
            // Piper：多线程，换语言靠换音色 → 分段 + 4 并行
            case "piper" -> new Caps(ALL, true, 4); // 语音包由服务端 /health 决定，不写死语言
            // 系统语音：进程隔离，启动是瓶颈 → 分段 + 4 并行
            case "system", "local" -> new Caps(Set.of("zh", "en"), true, 4);
            // GPT-SoVITS：单实例串行，但支持跨语言 → 整句一次
            case "sovits" -> new Caps(Set.of("zh", "en", "ja", "ko", "yue"), false, 1);
            // Kokoro：Python 单 worker；实测中文 500 → 只声明英文
            case "kokoro" -> new Caps(Set.of("en"), false, 1);
            // OpenAI 兼容等：全语言，整句一次（避免云端并发限流）
            default -> new Caps(ALL, false, 1);
        };
    }
}
