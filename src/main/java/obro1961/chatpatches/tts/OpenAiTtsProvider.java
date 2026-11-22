package obro1961.chatpatches.tts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import obro1961.chatpatches.translate.ApiProvider;
import obro1961.chatpatches.translate.ConfigStore;
import obro1961.chatpatches.translate.ProbeResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * OpenAI 兼容的 TTS（{@code POST /audio/speech}）。
 *
 * <p><b>关键：让它返回 wav 或 pcm。</b>
 * 默认是 mp3，而 Java 没有 MP3 解码器 —— 要么在 mod 里塞一个解码器（体积大），
 * 要么让服务端给 wav/pcm，Java 原生就能播。这里选后者。
 *
 * <p>请求体：
 * <pre>
 * { "model":"tts-1", "input":"要读的话", "voice":"alloy",
 *   "response_format":"wav", "speed":1.0 }
 * </pre>
 */
public class OpenAiTtsProvider implements TtsProvider {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ApiProvider cfg;

    public OpenAiTtsProvider(ApiProvider cfg) {
        this.cfg = cfg;
    }

    @Override
    public String id() {
        return cfg.name;
    }

    @Override
    public boolean ready() {
        return cfg.enabled
                && !"local".equalsIgnoreCase(cfg.type)
                && cfg.baseUrl != null && !cfg.baseUrl.isBlank()
                && cfg.ttsModel != null && !cfg.ttsModel.isBlank();
    }

    @Override
    public String describe() {
        return id() + " [openai-tts] baseUrl=" + cfg.baseUrl + " model=" + cfg.ttsModel
                + " voice=" + cfg.voice + " fmt=" + fmt();
    }

    private String fmt() {
        return cfg.format == null || cfg.format.isBlank() ? "wav" : cfg.format;
    }

    @Override
    public boolean rawPcm() {
        return "pcm".equalsIgnoreCase(fmt());
    }

    @Override
    public byte[] synthesize(String text) throws Exception {
        return synthesize(text, cfg.voice);
    }

    /** 用指定嗓音合成（{@code voice} 为空时退回配置里的）。 */
    public byte[] synthesize(String text, String voice) throws Exception {
        int timeout = ConfigStore.translate() == null ? 20000 : ConfigStore.translate().timeoutMs;

        JsonObject body = new JsonObject();
        body.addProperty("model", (cfg.ttsModel == null || cfg.ttsModel.isBlank()) ? "kokoro" : cfg.ttsModel);
        body.addProperty("input", text);
        body.addProperty("voice", voice == null || voice.isBlank() ? "alloy" : voice);
        body.addProperty("response_format", fmt());
        if (cfg.speed > 0 && cfg.speed != 1.0) {
            body.addProperty("speed", cfg.speed);
        }
        if (cfg.extraBody != null) {
            for (Map.Entry<String, Object> e : cfg.extraBody.entrySet()) {
                body.add(e.getKey(), GSON.toJsonTree(e.getValue()));
            }
        }

        String base = trimSlash(cfg.baseUrl);
        // 地址已带 /v1 就直接接 /audio/speech，否则补 /v1（Kokoro / piper_server / OpenAI 官方都这样）
        String speechUrl = base.endsWith("/v1") ? base + "/audio/speech" : base + "/v1/audio/speech";
        HttpRequest req = HttpRequest.newBuilder(URI.create(speechUrl))
                .timeout(Duration.ofMillis(timeout))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (cfg.apiKey == null ? "" : cfg.apiKey))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        // ★ 注意收的是字节数组（音频），不是字符串
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            String msg = new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8);
            throw new IllegalStateException("HTTP " + resp.statusCode() + " "
                    + (msg.length() > 200 ? msg.substring(0, 200) : msg));
        }
        byte[] audio = resp.body();
        if (audio == null || audio.length == 0) {
            throw new IllegalStateException("服务端返回了空音频");
        }
        return audio;
    }

    @Override
    public ProbeResult probe() {
        long t0 = System.currentTimeMillis();
        try {
            // 拿一小段试合成，最实在
            byte[] a = synthesize("ok");
            return a.length > 0
                    ? ProbeResult.ok(System.currentTimeMillis() - t0, "拿到 " + a.length + " 字节音频")
                    : ProbeResult.fail("音频为空", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return ProbeResult.fail(e.toString(), System.currentTimeMillis() - t0);
        }
    }

    static String trimSlash(String s) {
        if (s == null) {
            return "";
        }
        String r = s.trim();
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }
}
