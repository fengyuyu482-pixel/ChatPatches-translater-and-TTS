package obro1961.chatpatches.tts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 按语言挑音色。
 *
 * <p>音色字段可以填多个（逗号分隔），例如：
 * {@code zh_CN-huayan-medium,en_US-lessac-medium}。
 * 会按消息语言（zh/en/ja…）挑前缀匹配的那个。
 *
 * <p>留空时读服务端 {@code GET /health}：支持 Kokoro 的 {@code voices} 映射，
 * 也支持 Piper 的 {@code models} 数组。
 */
public final class ServiceVoices {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private ServiceVoices() {
    }

    /** @return 选中的音色；实在选不出返回 null。 */
    public static String pick(String baseUrl, String configured, String lang) {
        String want = lang == null ? "" : lang.toLowerCase(Locale.ROOT);

        List<String> candidates = new ArrayList<>();
        if (configured != null) {
            for (String s : configured.split(",")) {
                if (!s.isBlank()) {
                    candidates.add(s.trim());
                }
            }
        }
        if (!candidates.isEmpty()) {
            for (String c : candidates) {
                if (matches(c.toLowerCase(Locale.ROOT), want)) {
                    return c;
                }
            }
            // ★ 配置的音色没有一种匹配这个语言（常见：只配了英文音色却要念中文）
            //   先去问服务端要一个匹配的，再退回第一个 —— 否则中文会被英文音色念成乱码
            for (String remote : listFromHealth(baseUrl)) {
                if (matches(remote.toLowerCase(Locale.ROOT), want)) {
                    return remote;
                }
            }
            return candidates.get(0);
        }

        // 没填：问服务端要列表
        List<String> remote = listFromHealth(baseUrl);
        for (String c : remote) {
            if (matches(c.toLowerCase(Locale.ROOT), want)) {
                return c;
            }
        }
        return remote.isEmpty() ? null : remote.get(0);
    }

    private static boolean matches(String voice, String lang) {
        if (lang.isBlank()) {
            return false;
        }
        String two = lang.length() >= 2 ? lang.substring(0, 2) : lang;
        return voice.startsWith(two + "_") || voice.startsWith(two + "-") || voice.contains(two + "_")
                || ("zh".equals(two) && (voice.contains("cmn") || voice.contains("yue")))
                || ("ja".equals(two) && voice.contains("jv"));
    }

    private static List<String> listFromHealth(String baseUrl) {
        List<String> out = new ArrayList<>();
        if (baseUrl == null || baseUrl.isBlank()) {
            return out;
        }
        String base = baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        try {
            HttpResponse<String> r = HTTP.send(
                    HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                return out;
            }
            JsonObject root = JsonParser.parseString(r.body()).getAsJsonObject();
            if (root.has("voices") && root.get("voices").isJsonObject()) {
                for (String k : root.getAsJsonObject("voices").keySet()) {
                    out.add(root.getAsJsonObject("voices").get(k).getAsString());
                }
            }
            if (root.has("models") && root.get("models").isJsonArray()) {
                JsonArray arr = root.getAsJsonArray("models");
                for (JsonElement e : arr) {
                    out.add(e.getAsString());
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
