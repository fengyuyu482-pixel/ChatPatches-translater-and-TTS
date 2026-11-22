package obro1961.chatpatches.chatstudy;

import obro1961.chatpatches.ChatPatches;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * <b>本地语音引擎</b> —— 用系统自带语音，断网可用、不花钱、不需要 key。
 *
 * <p>实现思路：调 Windows 的 {@code System.Speech}（PowerShell，系统自带）把文本合成成
 * <b>WAV</b> 文件，再把文件读回来交给 {@link obro1961.chatpatches.tts.AudioPlayer} 播放。
 *
 * <p><b>为什么走"生成 wav 再自己播"而不是让它直接出声</b>：
 * 直接出声就没法排队、没法调音量、没法停 —— 人多时会几句叠在一起。
 * 走文件我们就完全掌握播放时机。
 *
 * <p>非 Windows 或者系统没有语音包时返回 null，上层会安静地跳过。
 */
public final class LocalTts {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private LocalTts() {
    }

    /**
     * @param text  要读的文本
     * @param voice 语音名（留空 = <b>按文本语言自动挑</b>）
     * @return WAV 字节；失败返回 null
     */
    public static byte[] synthesize(String text, String voice) {
        return synthesize(text, voice, null);
    }

    /**
     * <b>解析出真正要用的嗓音名</b>（{@code SelectVoice} 认的纯名字）。
     *
     * <p>抽成 public 是为了让日志和自测都能看到"实际用了哪个" ——
     * 只打配置里的"自动"查不出问题：{@code SelectVoice} 找不到指定嗓音时
     * <b>不报错</b>，PowerShell 会静默退回<b>系统默认嗓音</b>继续念。
     *
     * <p>实测代价：这台机器默认是中文 Huihui，要求英文嗓音从来没生效过 ——
     * {@code en} 和 {@code zh} 合成出来的字节<b>一模一样</b>，{@code 2333} 被念成"两千三百三十三"。
     * 分发之后"他那边朗读是怪声 / 念错语言"这类反馈，全靠这一行定位。
     */
    public static String resolveVoice(String voice, String text, String langHint) {
        if (voice == null || voice.isBlank()) {
            voice = pickVoiceFor(text, langHint);
        }
        return bareVoiceName(voice);
    }

    /** @param langHint 上层定好的嗓音语言（zh/en）；留空则按文本自己判断 */
    public static byte[] synthesize(String text, String voice, String langHint) {
        if (!WINDOWS || text == null || text.isBlank()) {
            return null;
        }
        voice = resolveVoice(voice, text, langHint);
        // ★ SelectVoice 只认【纯名字】，不认我们日志里那份"名字 / 文化"的展示串。
        //
        // 传展示串（"Microsoft Zira Desktop / en-US"）会报
        //   "Cannot set voice. No matching voice is installed or the voice was disabled."
        // 而 PowerShell 不会因此中止 —— 后面的 $s.Speak() 照跑，用【系统默认语音】。
        // 这台机器默认是中文 Huihui，于是：
        //   · "要求英文嗓音"从来没有生效过（实测 en 和 zh 合成出的字节完全相同）
        //   · 2333 被念成"两千三百三十三"
        //   · 英文消息用中文嗓音读，听着像怪声
        voice = bareVoiceName(voice);
        Path wav = null;
        try {
            wav = Files.createTempFile("chatpatch-tts-", ".wav");

            String safe = text.replace("'", "''");
            String voicePart = (voice == null || voice.isBlank())
                    ? ""
                    : "$s.SelectVoice('" + voice.replace("'", "''") + "');";

            String script = String.join(" ",
                    "Add-Type -AssemblyName System.Speech;",
                    "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;",
                    voicePart,
                    "$s.Rate = 0;",
                    "$s.SetOutputToWaveFile('" + wav.toAbsolutePath().toString().replace("'", "''") + "');",
                    "$s.Speak('" + safe + "');",
                    "$s.Dispose();");

            // ★ 用参数数组传，避免 PowerShell 把脚本里的字符吃掉
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            // 把输出读掉，否则管道满了会卡住
            try (var in = proc.getInputStream()) {
                in.readAllBytes();
            }
            if (!proc.waitFor(15, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                ChatPatches.LOGGER.warn("[chatpatch] 系统语音合成超时");
                return null;
            }
            if (proc.exitValue() != 0) {
                ChatPatches.LOGGER.warn("[chatpatch] 系统语音合成失败，退出码 {}", proc.exitValue());
                return null;
            }
            if (!Files.isRegularFile(wav)) {
                return null;
            }
            return Files.readAllBytes(wav);
        } catch (Exception e) {
            ChatPatches.LOGGER.warn("[chatpatch] 系统语音异常: {}", e.toString());
            return null;
        } finally {
            if (wav != null) {
                try {
                    Files.deleteIfExists(wav);
                } catch (IOException ignored) {
                    // 临时文件删不掉无所谓
                }
            }
        }
    }

    /**
     * 按文本语言挑一个系统语音。
     *
     * <p><b>为什么需要</b>：系统里中文和英文语音各有一个（实测这台机器是
     * {@code Microsoft Huihui / zh-CN} 和 {@code Microsoft Zira / en-US}）。
     * 不挑的话用的是系统默认 —— 英文消息被中文嗓音念出来是怪声，
     * 反过来也一样。
     *
     * <p>语音列表只查一次并缓存（查一次要 400ms 起一个 PowerShell，不能每条都查）。
     */
    static String pickVoiceFor(String text) {
        return pickVoiceFor(text, null);
    }

    static String pickVoiceFor(String text, String langHint) {
        java.util.List<String> voices = installedVoices();
        if (voices.isEmpty()) {
            return "";
        }
        String want = (langHint == null || langHint.isBlank()) ? voiceLanguageFor(text) : langHint;
        for (String v : voices) {
            if (v.toLowerCase(java.util.Locale.ROOT).contains(want)) {
                return v;
            }
        }
        return voices.getFirst();
    }

    /**
     * 这段文本该用中文嗓音还是英文嗓音。
     *
     * <p>规则只有一条：<b>有汉字 / 假名 → 中文，其余一律英文</b>。
     *
     * <p><b>为什么纯数字也要走英文</b>：{@code 2333}、{@code 666}、{@code 67} 这些在游戏里
     * 是英文社区的用法，用中文嗓音会念成"两千三百三十三"；英文嗓音才念成
     * "two three three three"。纯数字、纯符号、纯英文都算"其余"。
     *
     * <p>抽成 public static 是为了能单独测 —— 判错的代价是"数字被念成中文"，
     * 而这种错在代码里看是看不出来的，必须真的合成一次才知道。
     */
    public static String voiceLanguageFor(String text) {
        // 判断统一交给 ScriptDetector.Profile，别在这儿再写一遍字形检查。
        return obro1961.chatpatches.translate.ScriptDetector.profile(text).isCjk() ? "zh" : "en";
    }

    /**
     * 从展示串里取出 SelectVoice 认的纯名字。
     *
     * <p>{@link #installedVoices()} 返回的是 {@code "Microsoft Zira Desktop / en-US"}
     * （给人看的，带语言），而 {@code SelectVoice()} 只认 {@code "Microsoft Zira Desktop"}。
     * 传错不会报错到中止，只会静默退回默认语音 —— 见 {@code synthesize} 里的注释。
     */
    static String bareVoiceName(String voice) {
        if (voice == null || voice.isBlank()) {
            return "";
        }
        int i = voice.indexOf(" / ");
        return (i > 0 ? voice.substring(0, i) : voice).trim();
    }

    /** 系统里装了哪些语音（"名字" 形式，含语言）。带缓存。 */
    static java.util.List<String> installedVoices() {
        if (VOICE_CACHE != null) {
            return VOICE_CACHE;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        if (WINDOWS) {
            try {
                String script = "Add-Type -AssemblyName System.Speech; "
                        + "(New-Object System.Speech.Synthesis.SpeechSynthesizer).GetInstalledVoices() "
                        + "| ForEach-Object { $_.VoiceInfo.Name + ' / ' + $_.VoiceInfo.Culture }";
                ProcessBuilder pb = new ProcessBuilder(
                        "powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                byte[] raw;
                try (var in = proc.getInputStream()) {
                    raw = in.readAllBytes();
                }
                proc.waitFor(10, TimeUnit.SECONDS);
                for (String line : new String(raw, StandardCharsets.UTF_8).split("\\R")) {
                    String t = line.trim();
                    if (!t.isEmpty()) {
                        out.add(t);
                    }
                }
            } catch (Exception e) {
                ChatPatches.LOGGER.warn("[chatpatch] 读系统语音列表失败: {}", e.toString());
            }
        }
        VOICE_CACHE = out;
        return out;
    }

    private static volatile java.util.List<String> VOICE_CACHE = null;

    /** 系统里有哪些语音（自检用）。 */
    public static String listVoices() {
        java.util.List<String> v = installedVoices();
        return v.isEmpty() ? "（没读到语音，或非 Windows）" : String.join("\n", v);
    }
}
