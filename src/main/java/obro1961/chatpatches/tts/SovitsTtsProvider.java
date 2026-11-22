package obro1961.chatpatches.tts;

import com.google.gson.JsonObject;
import obro1961.chatpatches.translate.ApiProvider;
import obro1961.chatpatches.translate.ProbeResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 原生 GPT-SoVITS TTS（<b>不依赖 GAG 配置文件</b>）。
 *
 * <p>对接 <code>POST {baseUrl}/tts</code>，每次请求都带参考音频 —— 接口硬要求，
 * 实测不带会返回 400 {@code ref_audio_path is required}。
 *
 * <p>配置映射：
 * <ul>
 *   <li>地址 → {@code baseUrl}</li>
 *   <li>模型名 → 仅显示/日志（{@code /tts} 没这个字段，模型由服务端加载）</li>
 *   <li>参考音频 → {@code ref_audio_path}（必填）</li>
 *   <li>参考文本 → {@code prompt_text}（可空，实测可空）</li>
 * </ul>
 */
public class SovitsTtsProvider implements TtsProvider {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ApiProvider cfg;

    public SovitsTtsProvider(ApiProvider cfg) {
        this.cfg = cfg;
    }

    @Override
    public String id() {
        return cfg.name;
    }

    @Override
    public boolean ready() {
        return cfg.enabled && cfg.baseUrl != null && !cfg.baseUrl.isBlank();
    }

    @Override
    public String describe() {
        return "GPT-SoVITS " + cfg.baseUrl;
    }

    @Override
    public byte[] synthesize(String text) throws Exception {
        return synthesize(text, "zh");
    }

    /** @param langHint zh / en（决定 text_lang） */
    public byte[] synthesize(String text, String langHint) throws Exception {
        String textLang = extra("text_lang");
        if (textLang.isBlank() || "auto".equalsIgnoreCase(textLang)) {
            textLang = "en".equalsIgnoreCase(langHint) ? "en" : "zh";
        }
        String promptLang = extra("prompt_lang");
        if (promptLang.isBlank()) {
            promptLang = "auto";
        }

        String refAudio = cfg.voice == null ? "" : cfg.voice.trim();
        if (refAudio.isEmpty()) {
            throw new IllegalStateException("参考音频未填写（本地朗读 → 参考音频）");
        }

        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("text_lang", textLang);
        body.addProperty("ref_audio_path", refAudio);
        body.addProperty("prompt_text", extra("prompt_text"));
        body.addProperty("prompt_lang", promptLang);
        body.addProperty("text_split_method", "cut5");
        body.addProperty("batch_size", 1);
        body.addProperty("media_type", "wav");
        body.addProperty("streaming_mode", false);

        String base = cfg.baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/tts"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            String rb = new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8);
            obro1961.chatpatches.ChatPatches.LOGGER.warn("[chatpatch] GPT-SoVITS HTTP {} body={} resp={}",
                    resp.statusCode(), body, rb.length() > 400 ? rb.substring(0, 400) : rb);
            throw new IllegalStateException("GPT-SoVITS HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private String extra(String key) {
        if (cfg.extraBody == null) {
            return "";
        }
        Object v = cfg.extraBody.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    @Override
    public ProbeResult probe() {
        try {
            HttpResponse<Void> r = HTTP.send(HttpRequest.newBuilder(URI.create(cfg.baseUrl))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
            return ProbeResult.ok(0, "HTTP " + r.statusCode());
        } catch (Exception e) {
            return ProbeResult.fail(e.toString(), 0);
        }
    }
}
