package obro1961.chatpatches.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static obro1961.chatpatches.ChatPatches.LOGGER;
import obro1961.chatpatches.translate.TranslateConfig;
import obro1961.chatpatches.translate.ApiProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * 厂家自动检测。
 *
 * <p>关键设计：<b>不靠正则下结论</b>。很多厂家都用 {@code sk-} 前缀，只看前缀必然误判。
 * 流程是 正则提名候选 → 真打 {@code GET {baseUrl}/models} 验证 → 从返回的模型列表挑一个可用型号。
 * {@code /models} 是 GET，不消耗 token。
 */
public final class VendorDetector {

    /**
     * 一个已知厂家。keyPattern 只用来缩小候选范围，不作为结论。
     *
     * @param specificity 形态置信度：2=专属前缀/精确形态，1=较宽，0=裸 sk- 通配。
     *                    探测按 2→1→0 分级，某一级命中就立刻返回，不再往下等超时。
     */
    public record Vendor(String id, String title, String baseUrl, Pattern keyPattern, List<String> preferredModels,
                         int specificity) {
    }

    /** 一次真实命中的探测结果。 */
    public record Hit(Vendor vendor, String baseUrl, List<String> models, String chosenModel, long ms) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 顺序敏感：越靠前越优先。 */
    public static final List<Vendor> VENDORS = List.of(
            new Vendor("deepseek", "DeepSeek 官方", "https://api.deepseek.com/v1",
                    Pattern.compile("^sk-[0-9a-f]{32}$"),
                    // ★ deepseek-chat 排第一：deepseek-flash 是"思考型"模型，
                    //   推理会烧光 token（实测 18~36 秒，或加上限后直接返回空），做不了聊天翻译。
                    List.of("deepseek-chat", "deepseek-flash", "deepseek-v4-pro", "deepseek-reasoner"), 0),
            new Vendor("openai", "OpenAI 官方", "https://api.openai.com/v1",
                    Pattern.compile("^sk-(proj-)?[A-Za-z0-9_-]{20,}$"),
                    List.of("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "gpt-3.5-turbo"), 1),
            new Vendor("openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
                    Pattern.compile("^sk-or-v1-.*"),
                    List.of("openai/gpt-4o-mini", "deepseek/deepseek-chat", "google/gemini-2.0-flash-001"), 2),
            new Vendor("groq", "Groq", "https://api.groq.com/openai/v1",
                    Pattern.compile("^gsk_.*"),
                    List.of("llama-3.3-70b-versatile", "llama-3.1-8b-instant"), 2),
            new Vendor("xai", "xAI Grok", "https://api.x.ai/v1",
                    Pattern.compile("^xai-.*"),
                    List.of("grok-2-latest", "grok-beta"), 2),
            new Vendor("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4",
                    Pattern.compile("^[0-9a-f]{32}\\.[A-Za-z0-9]+$"),
                    List.of("glm-4-flash", "glm-4-air", "glm-4-plus"), 2),
            new Vendor("google", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
                    Pattern.compile("^AIza[A-Za-z0-9_-]{30,}$"),
                    List.of("gemini-2.0-flash", "gemini-1.5-flash"), 2),
            // 以下几家都用裸 sk- 开头，只能靠真探测区分，放最后一级
            new Vendor("moonshot", "月之暗面 Kimi", "https://api.moonshot.cn/v1",
                    Pattern.compile("^sk-.*"),
                    List.of("moonshot-v1-8k", "moonshot-v1-32k"), 0),
            new Vendor("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1",
                    Pattern.compile("^sk-.*"),
                    List.of("Qwen/Qwen2.5-7B-Instruct", "deepseek-ai/DeepSeek-V3"), 0),
            new Vendor("dashscope", "阿里百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    Pattern.compile("^sk-.*"),
                    List.of("qwen-turbo", "qwen-plus", "qwen-max"), 0)
    );

    private VendorDetector() {
    }

    public static String resolveKey(ApiProvider p) {
        return p.apiKey;
    }

    /**
     * 按形态置信度分级探测：2 → 1 → 0。某一级有命中就立刻返回，不再等下一级超时。
     *
     * <p>为什么这么做：DeepSeek 这种精确形态 417ms 就能确认，
     * 但如果同时去等 openai/月之暗面/硅基流动的通配探测超时，整体会被拖到 8 秒以上。
     */
    public static List<Hit> detect(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }
        List<Vendor> candidates = new ArrayList<>();
        for (Vendor v : VENDORS) {
            if (v.keyPattern().matcher(apiKey).matches()) {
                candidates.add(v);
            }
        }
        for (int level = 2; level >= 0; level--) {
            List<Vendor> group = new ArrayList<>();
            for (Vendor v : candidates) {
                if (v.specificity() == level) {
                    group.add(v);
                }
            }
            if (group.isEmpty()) {
                continue;
            }
            List<Hit> hits = probeGroup(group, apiKey);
            if (!hits.isEmpty()) {
                LOGGER.info("[chatpatch] 厂家探测：置信度 {} 命中 {} 家，不再往下探", level, hits.size());
                return hits;
            }
        }
        return List.of();
    }

    private static List<Hit> probeGroup(List<Vendor> group, String apiKey) {
        Map<String, Hit> hits = new LinkedHashMap<>();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (Vendor v : group) {
            tasks.add(CompletableFuture.runAsync(() -> {
                Hit h = probe(v, apiKey);
                if (h != null) {
                    synchronized (hits) {
                        hits.put(v.id(), h);
                    }
                }
            }));
        }
        try {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } catch (Exception ignored) {
            // 单个探测内部已兜底
        }
        List<Hit> out = new ArrayList<>();
        for (Vendor v : group) {
            Hit h = hits.get(v.id());
            if (h != null) {
                out.add(h);
            }
        }
        return out;
    }

    private static Hit probe(Vendor v, String apiKey) {
        long t0 = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(trim(v.baseUrl()) + "/models"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() / 100 != 2) {
                return null;
            }
            List<String> ids = parseModelIds(r.body());
            if (ids.isEmpty()) {
                return null;
            }
            return new Hit(v, v.baseUrl(), ids, choose(v, ids), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> parseModelIds(String body) {
        List<String> ids = new ArrayList<>();
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            JsonArray data = o.getAsJsonArray("data");
            if (data == null) {
                data = o.getAsJsonArray("models");
            }
            if (data == null) {
                return ids;
            }
            for (JsonElement e : data) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonElement id = e.getAsJsonObject().get("id");
                if (id == null) {
                    id = e.getAsJsonObject().get("name");
                }
                if (id != null && !id.isJsonNull()) {
                    ids.add(id.getAsString());
                }
            }
        } catch (Exception ignored) {
            // 解析不了就当没拿到
        }
        return ids;
    }

    /** 优先选厂家推荐列表里真实存在的型号，否则退回第一个。 */
    private static String choose(Vendor v, List<String> ids) {
        for (String want : v.preferredModels()) {
            for (String id : ids) {
                if (id.equalsIgnoreCase(want)) {
                    return id;
                }
            }
        }
        return ids.getFirst();
    }

    /** 把探测结果写回一个 provider。返回是否发生了改动。 */
    public static boolean apply(ApiProvider p, Hit hit) {
        boolean changed = false;
        if (!hit.baseUrl().equals(p.baseUrl)) {
            p.baseUrl = hit.baseUrl();
            changed = true;
        }
        if (p.model == null || p.model.isBlank() || !containsIgnoreCase(hit.models(), p.model)) {
            p.model = hit.chosenModel();
            changed = true;
        }
        if (p.title == null || p.title.isBlank() || p.title.equals(p.name)
                || p.title.startsWith("付费：") || p.title.startsWith("免费：")) {
            p.title = "付费：" + hit.vendor().title();
            changed = true;
        }
        return changed;
    }

    /**
     * 启动时自动补全：只处理类型是 POST 型、有 key、且 baseUrl 为空的条目。
     * 已填 baseUrl 的一律不覆盖（避免把用户手配的中转干掉）。
     */
    public static boolean autoApply(TranslateConfig cfg) {
        boolean changed = false;
        if (cfg.providers == null) {
            return false;
        }
        for (ApiProvider p : cfg.providers) {
            if (p == null || !p.enabled || p.isGetType()) {
                continue;
            }
            if (p.baseUrl != null && !p.baseUrl.isBlank()) {
                continue;
            }
            String key = resolveKey(p);
            if (key == null || key.isBlank()) {
                continue;
            }
            List<Hit> hits = detect(key);
            if (hits.isEmpty()) {
                LOGGER.warn("[chatpatch] 自动检测：{} 的 key 没能匹配到任何可用厂家", p.name);
                continue;
            }
            if (apply(p, hits.getFirst())) {
                LOGGER.info("[chatpatch] 自动检测：{} -> {} ({})", p.name, hits.getFirst().vendor().title(), p.baseUrl);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean containsIgnoreCase(List<String> list, String s) {
        for (String x : list) {
            if (x.equalsIgnoreCase(s)) {
                return true;
            }
        }
        return false;
    }

    private static String trim(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
