package obro1961.chatpatches.tts;

import obro1961.chatpatches.translate.ApiProvider;
import obro1961.chatpatches.translate.ProbeResult;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Piper 本地 CLI TTS：起进程、把文本写进 stdin、读 stdout 的 wav。
 *
 * <p>配置（都存在 ApiProvider.extraBody 里）：
 * <ul>
 *   <li>{@code piper_exe} → piper 可执行文件路径</li>
 *   <li>{@code piper_model} → .onnx 模型路径</li>
 * </ul>
 */
public class PiperTtsProvider implements TtsProvider {

    private final ApiProvider cfg;

    /** 是否走 HTTP：地址字段或“程序路径”里写了 http 都算。 */
    public static boolean hasHttp(ApiProvider p) {
        String u = effectiveUrl(p);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    /** 实际使用的 URL：只看「地址」字段。 */
    public static String effectiveUrl(ApiProvider p) {
        return p == null || p.baseUrl == null ? "" : p.baseUrl.trim();
    }
    public PiperTtsProvider(ApiProvider cfg) {
        this.cfg = cfg;
    }

    @Override
    public String id() {
        return cfg.name;
    }

    @Override
    public boolean ready() {
        return cfg.enabled && !exe().isBlank() && !model().isBlank();
    }

    @Override
    public String describe() {
        return "Piper " + model();
    }

    @Override
    public byte[] synthesize(String text) throws Exception {
        return synthesize(text, "zh");
    }

    public byte[] synthesize(String text, String langHint) throws Exception {
        String exe = exe();
        String model = model();
        if (exe.isBlank() || model.isBlank()) {
            throw new IllegalStateException("Piper 未配置：需要程序路径 + 模型路径");
        }
        if (!Files.isRegularFile(Path.of(exe))) {
            throw new IllegalStateException("Piper 程序不存在: " + exe);
        }
        if (!Files.isRegularFile(Path.of(model))) {
            throw new IllegalStateException("Piper 模型不存在: " + model);
        }

        ProcessBuilder pb = new ProcessBuilder(exe, "--model", model, "--output_file", "-");
        pb.redirectErrorStream(false);
        Process proc = pb.start();

        try (OutputStream stdin = proc.getOutputStream()) {
            stdin.write(text.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var in = proc.getInputStream()) {
            in.transferTo(out);
        }

        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IllegalStateException("Piper 超时（30s）");
        }
        byte[] wav = out.toByteArray();
        if (wav.length < 44) {
            String err = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("Piper 无输出: " + (err.length() > 200 ? err.substring(0, 200) : err));
        }
        return wav;
    }

    private String exe() {
        return extra("piper_exe");
    }

    private String model() {
        return extra("piper_model");
    }

    private String extra(String key) {
        if (cfg.extraBody == null) {
            return "";
        }
        Object v = cfg.extraBody.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    @Override
    public ProbeResult probe() {
        boolean ok = Files.isRegularFile(Path.of(exe())) && Files.isRegularFile(Path.of(model()));
        return ok ? ProbeResult.ok(0, "路径存在") : ProbeResult.fail("路径不存在", 0);
    }
}