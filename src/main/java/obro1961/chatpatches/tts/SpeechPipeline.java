package obro1961.chatpatches.tts;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 朗读管线：唯一决定「怎么念」的地方。
 *
 * <ol>
 *   <li>查 {@link EngineCaps}：不支持这句的语言 → 返回 {@link Result#unsupported()}（调用方换下一个引擎）</li>
 *   <li>需要分段 → 按语言切段、过滤不支持段；不需要 → 整句一次</li>
 *   <li>按 {@code concurrency} 并行或串行合成</li>
 *   <li>统一 24000 单声道拼接</li>
 * </ol>
 *
 * <p>这是唯一的入口，不再有 if (piper) / if (sovits) 散落在调用方。
 */
public final class SpeechPipeline {

    /** 单段合成器：入参（文本, 语言码），返回音频字节（WAV）。 */
    @FunctionalInterface
    public interface Synth {
        byte[] synth(String text, String lang) throws Exception;
    }

    /** 合成结果：要么有 PCM，要么明确“不支持”，要么失败。 */
    public record Result(byte[] pcm, boolean unsupported) {

        public static Result ok(byte[] pcm) {
            return new Result(pcm, false);
        }

        public static Result skipped() {
            return new Result(null, true);
        }

        public static Result failed() {
            return new Result(null, false);
        }
    }

    private SpeechPipeline() {
    }

    public static Result speak(String text, EngineCaps.Caps caps, Synth synth) {
        return speak(text, caps, synth, null);
    }

    /** @param hintLang 显式语言（原文=源语言、译文=目标语言）；null 则自动判断 */
    public static Result speak(String text, EngineCaps.Caps caps, Synth synth, String hintLang) {
        if (text == null || text.isBlank()) {
            return Result.failed();
        }

        // 语言集跟翻译配置走：配的是中英，就只在这两种之间换段
        java.util.Set<String> dialogLangs = obro1961.chatpatches.translate.DialogLanguages.current();
        List<SpeechSegmenter.Segment> segs = SpeechSegmenter.split(text, dialogLangs);
        boolean anySupported = segs.isEmpty() || caps.supports(segs.get(0).lang());
        for (SpeechSegmenter.Segment s : segs) {
            if (caps.supports(s.lang())) {
                anySupported = true;
                break;
            }
        }
        if (!segs.isEmpty() && !anySupported) {
            return Result.skipped();
        }

        // 整句引擎：不切段，直接用主语言发一次
        if (!caps.segment() || segs.size() <= 1) {
            String lang = (hintLang != null && caps.supports(hintLang)) ? hintLang : dominantLang(segs);
            try {
                byte[] pcm = AudioUtil.toPcm24k(synth.synth(text, lang));
                return pcm == null || pcm.length == 0 ? Result.failed() : Result.ok(pcm);
            } catch (Exception e) {
                return Result.failed();
            }
        }

        // 分段引擎：先看是否所有段都支持
        List<SpeechSegmenter.Segment> usable = new ArrayList<>();
        for (SpeechSegmenter.Segment s : segs) {
            if (caps.supports(s.lang())) {
                usable.add(s);
            }
        }
        if (usable.isEmpty()) {
            return Result.skipped();
        }
        // ★ 有段不支持时绝不丢段（否则会出现“只念了英文名、中文没念”）
        if (usable.size() < segs.size()) {
            String whole = (hintLang != null && caps.supports(hintLang)) ? hintLang : dominantLang(segs);
            String fallbackLang = caps.supports(whole) ? whole : usable.get(0).lang();
            try {
                byte[] pcm = AudioUtil.toPcm24k(synth.synth(text, fallbackLang));
                if (pcm != null && pcm.length > 0) {
                    return Result.ok(pcm);
                }
            } catch (Exception ignored) {
            }
            return Result.skipped();
        }
        if (usable.size() == 1) {
            try {
                byte[] pcm = AudioUtil.toPcm24k(synth.synth(usable.get(0).text(), usable.get(0).lang()));
                return pcm == null || pcm.length == 0 ? Result.failed() : Result.ok(pcm);
            } catch (Exception e) {
                return Result.failed();
            }
        }

        List<byte[]> parts = collect(usable, caps.concurrency(), synth);
        if (parts.isEmpty()) {
            return Result.failed();
        }
        if (parts.size() < usable.size()) {
            // 有段失败：退回整句，避免吞字
            try {
                byte[] whole = AudioUtil.toPcm24k(synth.synth(text, usable.get(0).lang()));
                if (whole != null && whole.length > 0) {
                    return Result.ok(whole);
                }
            } catch (Exception ignored) {
            }
        }
        return Result.ok(AudioUtil.concat(parts));
    }

    /** 按并发度收集各段 PCM（保持原顺序，段间 60ms）。 */
    private static List<byte[]> collect(List<SpeechSegmenter.Segment> segs, int concurrency, Synth synth) {
        List<byte[]> parts = new ArrayList<>();
        int n = Math.max(1, Math.min(concurrency, segs.size()));

        if (n == 1) {
            for (SpeechSegmenter.Segment s : segs) {
                byte[] pcm = safe(s, synth);
                if (pcm != null) {
                    parts.add(pcm);
                    parts.add(AudioUtil.silence(60));
                }
            }
            return parts;
        }

        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<byte[]>> futures = new ArrayList<>();
            for (SpeechSegmenter.Segment s : segs) {
                futures.add(pool.submit(() -> safe(s, synth)));
            }
            for (Future<byte[]> f : futures) {
                try {
                    byte[] pcm = f.get();
                    if (pcm != null) {
                        parts.add(pcm);
                        parts.add(AudioUtil.silence(60));
                    }
                } catch (Exception ignored) {
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return parts;
    }

    /** 整句引擎用哪种语言：按各语言字符数取最多的那个，而不是第一段。 */
    private static String dominantLang(List<SpeechSegmenter.Segment> segs) {
        if (segs == null || segs.isEmpty()) {
            return "zh";
        }
        // 整句引擎（SoVITS/Kokoro）只能选一种语言。
        // 只要里面有汉字就优先中文：中文被当成英文念会直接听不出来。
        java.util.Set<String> dialog = obro1961.chatpatches.translate.DialogLanguages.current();
        boolean zhAllowed = dialog == null || dialog.contains("zh");
        for (SpeechSegmenter.Segment s : segs) {
            if ("zh".equals(s.lang()) && zhAllowed) {
                return "zh";
            }
        }
        java.util.Map<String, Integer> weight = new java.util.HashMap<>();
        for (SpeechSegmenter.Segment s : segs) {
            int n = 0;
            for (int i = 0; i < s.text().length(); i++) {
                if (Character.isLetter(s.text().charAt(i))) {
                    n++;
                }
            }
            weight.merge(s.lang(), n, Integer::sum);
        }
        return weight.entrySet().stream().max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey).orElse("zh");
    }

    private static byte[] safe(SpeechSegmenter.Segment s, Synth synth) {
        try {
            return AudioUtil.toPcm24k(synth.synth(s.text(), s.lang()));
        } catch (Exception e) {
            return null;
        }
    }
}
