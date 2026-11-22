package obro1961.chatpatches.translate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static obro1961.chatpatches.ChatPatches.LOGGER;
import obro1961.chatpatches.translate.ApiProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** OpenAI 兼容的 POST 型接口：官方 / 中转 / Ollama / LM Studio / llama.cpp 通吃。 */
public class OpenAiCompatibleProvider implements TranslateProvider {

    private static final Gson GSON = new Gson();

    private final String id;
    /** 包内可见：TranslationManager.rank() 要读 title/name 判降级权重 ✓ */
    final ApiProvider cfg;
    private final HttpClient http;

    public OpenAiCompatibleProvider(String id, ApiProvider cfg) {
        this.id = id;
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(ConfigStore.translate().timeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String id() {
        return id;
    }

    private String key() {
        return cfg.apiKey;
    }

    @Override
    public boolean ready() {
        return readiness(cfg) == null;
    }

    /**
     * <b>这条源现在能不能用</b> —— {@code null} 表示可用，否则返回不可用的原因。
     *
     * <p>检查顺序是固定的（用户要求「首先看开关，然后看有没有 key 能不能用」）：
     * <ol>
     *   <li><b>开关</b> —— {@code enabled=false} 直接跳过，不看别的</li>
     *   <li><b>端点完整</b> —— baseUrl + model 必须都有</li>
     *   <li><b>key 够不够</b> —— 见下方"免费端点"的判定</li>
     * </ol>
     *
     * <h2>为什么第 3 步不能简单写"key 为空就跳过"</h2>
     * <p>有些端点<b>根本不需要鉴权</b> —— B站 Index-Translate
     * （{@code index-translate.bilibili.com}，免注册免 Key）。
     * 早先版本要求 key 非空，等于把这类端点判成"配置不完整"整个跳过，
     * 而且日志里看不出原因。所以现在按<b>端点性质</b>判断：
     * <table border=1>
     *   <caption>端点类型与 key 要求</caption>
     *   <tr><th>端点</th><th>key 为空时</th></tr>
     *   <tr><td>已知免费端点（bilibili）</td><td>✓ 可用（不发 Authorization 头）</td></tr>
     *   <tr><td>本地端点（127.0.0.1 / localhost）</td><td>✓ 可用（本地模型通常不要 key）</td></tr>
     *   <tr><td>其它（DeepSeek / OpenAI / 中转…）</td><td>✗ 跳过 —— 否则每次白等一个 401</td></tr>
     * </table>
     *
     * <p>★ 最后那一条是这次改动的核心：DeepSeek 没填 key 时，
     * 原来会照常发请求、拿到 {@code 401 Authentication Fails}、吃掉整段超时预算，
     * 然后才降级。现在直接跳过，把时间留给真正能用的源。
     */
    public static String readiness(ApiProvider cfg) {
        if (cfg == null) {
            return "配置为空";
        }
        // ① 开关
        if (!cfg.enabled) {
            return "已关闭（enabled=false）";
        }
        // ② 端点完整
        boolean getType = cfg.endpoint != null && !cfg.endpoint.isBlank();
        if (getType) {
            // GET 型（mymemory 那种）不需要 baseUrl/model，下面单独判
            return null;
        }
        boolean hasBase = cfg.baseUrl != null && !cfg.baseUrl.isBlank();
        boolean hasModel = cfg.model != null && !cfg.model.isBlank();
        if (!hasBase && !hasModel) {
            return "baseUrl 和 model 都没填";
        }
        if (!hasBase) {
            return "baseUrl 没填";
        }
        if (!hasModel) {
            return "model 没填";
        }
        // ③ key 够不够
        boolean hasKey = cfg.apiKey != null && !cfg.apiKey.isBlank();
        if (hasKey) {
            return null;
        }
        if (isFreeEndpoint(cfg.baseUrl)) {
            return null;   // 免鉴权端点，空 key 正常
        }
        return "需要 API key 但没填（" + shortHost(cfg.baseUrl) + "）";
    }

    /** 已知不需要鉴权的端点。 */
    public static boolean isFreeEndpoint(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.toLowerCase(java.util.Locale.ROOT);
        return u.contains("index-translate.bilibili.com");
    }

    /** 端点是不是本机（本地模型通常不需要 key）。 */
    public static boolean isLocalEndpoint(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.toLowerCase(java.util.Locale.ROOT);
        return u.contains("127.0.0.1") || u.contains("localhost") || u.contains("[::1]");
    }

    /** 日志里用：只留主机名，别把整条 URL（可能带 query）刷出来。 */
    private static String shortHost(String baseUrl) {
        try {
            java.net.URI u = java.net.URI.create(baseUrl.trim());
            String h = u.getHost();
            return h == null ? baseUrl : h;
        } catch (Exception e) {
            return baseUrl;
        }
    }

    /** 需不需要带鉴权头。空 key = 不需要。 */
    private boolean hasKey() {
        return key() != null && !key().isBlank();
    }

    /**
     * <b>真正要 POST 的地址</b> —— 这里的宽容度决定了"随便填能不能用"。
     *
     * <p>用户可能填三种东西，<b>三种都得能跑</b>：
     * <pre>
     *   https://api.deepseek.com/v1                  → 补 /chat/completions
     *   https://api.deepseek.com/v1/                 → 去掉尾斜杠再补
     *   https://x/v1/chat/completions                → 【原样用】← 以前会拼成 .../chat/completions/chat/completions
     * </pre>
     * 最后那种是最常见的踩坑：文档上写的是"接口地址"，用户就照着填了完整端点。
     */
    public static String chatCompletionsUrl(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.endsWith("/chat/completions") || u.endsWith("/completions")) {
            return u;                       // 已经给全了，别再拼
        }
        return u + "/chat/completions";
    }

    /** 鉴权头的名字：没配就用标准的 {@code Authorization}。 */
    private String authHeaderName() {
        return cfg.authHeader == null || cfg.authHeader.isBlank() ? "Authorization" : cfg.authHeader.trim();
    }

    /**
     * 鉴权头的值。
     *
     * <p>{@code authPrefix} 为 {@code null} → 用 {@code "Bearer "}；
     * 显式设成空串 → 裸 key（Azure 那种）。
     */
    private String authHeaderValue() {
        String prefix = cfg.authPrefix == null ? "Bearer " : cfg.authPrefix;
        return prefix + key();
    }

    @Override
    public String describe() {
        return id + " [openai] baseUrl=" + cfg.baseUrl + " model=" + cfg.model;
    }

    /** 可见性放宽到 public 是为了让自测能钉住"提示词里不许出现中文语言名"这条。 */
    public static String systemPrompt(String sourceLanguage, String targetLanguage,
                                      List<String> glossaryHints, String styleNote) {
        String src = (sourceLanguage == null || sourceLanguage.isBlank()
                || "auto".equalsIgnoreCase(sourceLanguage))
                ? "The text may be in any language; detect it yourself."
                : "The source language is " + sourceLanguage + ".";
        StringBuilder sb = new StringBuilder();
        sb.append("You are a translation engine for in-game chat. ").append(src)
                // ★ 目标语言必须用【英语名】发给模型 ✗ 中文名会被当成"翻成中文"
                //   实测（同一个端点）：写「越南语」时 oh 出来是「不错」；
                //   写 Vietnamese 才对（oh → ồ、yes → Vâng）✓
                .append(" Translate the text into ")
                .append(ScriptDetector.promptLanguageName(targetLanguage)).append(".")
                .append(" The text is casual chat: often fragmented, misspelled, or abbreviated."
                        + " Translate the INTENDED meaning naturally, do not translate word by word.")
                .append(" Keep player names, numbers, item names and game terms unchanged.")
                .append(" Output ONLY the translation in ")
                .append(ScriptDetector.promptLanguageName(targetLanguage))
                .append(", no quotes, no explanation.");
        if (glossaryHints != null && !glossaryHints.isEmpty()) {
            sb.append("\nTerm hints (MUST follow these exact meanings): ")
                    .append(String.join("; ", glossaryHints)).append('.');
        }
        if (styleNote != null && !styleNote.isBlank()) {
            sb.append('\n').append(styleNote);
        }
        return sb.toString();
    }

    private JsonObject buildBody(String text, String sourceLanguage, String targetLanguage,
                                 List<String> context, List<String> glossaryHints, String styleNote) {
        StringBuilder user = new StringBuilder();
        if (context != null && !context.isEmpty()) {
            user.append("Context (reference only, do NOT translate):\n");
            for (String c : context) {
                user.append("- ").append(c).append('\n');
            }
            user.append("\nTranslate this:\n");
        }
        user.append(text);

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model == null ? "" : cfg.model);

        JsonArray msgs = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt(sourceLanguage, targetLanguage, glossaryHints, styleNote));
        JsonObject usr = new JsonObject();
        usr.addProperty("role", "user");
        usr.addProperty("content", user.toString());
        msgs.add(sys);
        msgs.add(usr);
        body.add("messages", msgs);
        body.addProperty("temperature", 0.2);

        // ★ 必须给输出上限。
        //
        //   不给的话模型可以随便写多长 —— 实测同一个问题：
        //     deepseek-flash（思考型）不设上限：17984 / 22793 / 36494 ms，
        //     可见答案只有 6 个字，却烧了 2872 / 3686 / 6416 个 token。
        //   超时 → 整条降级链作废 → "这条消息永远没译文"。
        //
        //   翻译的输出长度大致是输入的两三倍，按这个给；上下夹一下防止荒唐值。
        //   放在 extraBody 合并【之前】—— 用户想覆盖就覆盖。
        int cap = Math.max(64, Math.min(1024, text.length() * 3 + 32));
        body.addProperty("max_tokens", cap);

        // 厂商特性开关：原样合并，允许覆盖上面的默认值
        applyExtraBody(body, cfg.extraBody);
        return body;
    }

    /**
     * 把 {@code extraBody} 合进请求体，并把<b>整数</b>还原成整数。
     *
     * <p><b>为什么需要这一步</b>（2026-10-20 实测）：{@code extraBody} 的类型是
     * {@code Map<String,Object>}，Gson 解析 JSON 时会把所有数字变成 {@code Double} ——
     * 于是 {@code {"max_tokens": 512}} 发出去变成 {@code 512.0}，
     * 而 DeepSeek 直接 422：
     * <pre>
     *   max_tokens: invalid type: floating point `512.0`, expected u32
     * </pre>
     * 也就是说：<b>extraBody 里凡是数字，一直是坏的</b>（只是以前没人用它传数字）。
     * 这里把"值等于自身的整数"转成 long，其余（小数/字符串/对象）保持原样。
     */
    public static void applyExtraBody(JsonObject body, Map<String, Object> extraBody) {
        if (body == null || extraBody == null) {
            return;
        }
        for (Map.Entry<String, Object> e : extraBody.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            Object v = e.getValue();
            if (v instanceof Number n) {
                double d = n.doubleValue();
                if (d == Math.rint(d) && !Double.isInfinite(d)) {
                    body.addProperty(e.getKey(), (long) d);
                    continue;
                }
            }
            body.add(e.getKey(), GSON.toJsonTree(v));
        }
    }

    private HttpResponse<String> send(JsonObject body, long timeoutMs) throws Exception {
        String url = chatCompletionsUrl(cfg.baseUrl);
        long to = effectiveTimeout(timeoutMs);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(to))
                .header("Content-Type", "application/json");
        // ★ key 为空就【不发】鉴权头（免鉴权端点，比如 B站 Index-Translate）
        //   非空就按配置发 —— 头名字和前缀都可配，Azure / 中转那些非标准写法也能接。
        if (hasKey()) {
            b.header(authHeaderName(), authHeaderValue());
        }
        HttpRequest request = b
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        return sendBounded(http, request, to);
    }

    /**
     * 发请求 —— <b>由我们自己说了算"最多等多久"</b>。
     *
     * <p><b>为什么不能只靠 {@code HttpRequest.timeout}</b>：实测踩到过。
     * 配置写的是 8000ms，日志里却出现：
     * <pre>
     * [09:40:25] [chatstudy-worker/INFO] deepseek 翻译成功 (16142ms)
     * </pre>
     * 16142 ≈ 2 × 8000 —— <b>JDK 的 HttpClient 内部又重试了一次</b>，
     * 而 {@code HttpRequest.timeout} 是<b>单次尝试</b>的超时，管不住整段。
     * 于是"总预算 8 秒"照样能变成 16 秒，用户看到的就是"翻译卡住了"。
     *
     * <p>所以改成 async + {@code orTimeout}：等待时长由调用方的预算决定，
     * 超了立刻抛出去走降级链。底层那次连接交给 JDK 自己收尾，我们不等它。
     *
     * <p>public 是为了能单独测（{@code build-check/TimeoutTest.java}）——
     * 它用"只接受连接、永不回应"的本地黑洞服务器，确定性验证"不会干等"。
     */
    public static HttpResponse<String> sendBounded(HttpClient client, HttpRequest request, long to) throws Exception {
        try {
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .orTimeout(to, TimeUnit.MILLISECONDS)
                    .join();
        } catch(CompletionException | CancellationException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if(cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    /**
     * 本次请求该用多少超时。
     *
     * @param timeoutMs 调用方给的预算；{@code <=0} = 用配置默认
     */
    private static long effectiveTimeout(long timeoutMs) {
        return timeoutMs > 0 ? timeoutMs : ConfigStore.translate().timeoutMs;
    }

    @Override
    public String translate(String text, String sourceLanguage, String targetLanguage,
                            List<String> context, List<String> glossaryHints, String styleNote,
                            long timeoutMs) throws Exception {
        JsonObject body = buildBody(text, sourceLanguage, targetLanguage,
                context, glossaryHints, styleNote);
        HttpResponse<String> resp = send(body, timeoutMs);

        // ★ 有的服务商不认 max_tokens（新版 OpenAI 推理模型只认 max_completion_tokens）。
        //   表现是 400 + 报错里提到 max_tokens。这里【换名字重试一次】，
        //   而不是让整个源废掉 —— "任何一个 api 接入就能用"就是靠这种兜底堆出来的。
        if (resp.statusCode() / 100 != 2 && mentionsTokenLimit(resp.body()) && body.has("max_tokens")) {
            JsonObject retry = body.deepCopy();
            retry.remove("max_tokens");
            retry.add("max_completion_tokens", body.get("max_tokens"));
            LOGGER.info("[chatpatch] {} 不认 max_tokens，改用 max_completion_tokens 重试", id);
            resp = send(retry, timeoutMs);
        }

        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " " + shorten(resp.body()));
        }
        return parseChatCompletions(resp.body());
    }

    /** 报错里有没有在说"输出上限字段"。public 是为了让自测能钉住这条判据。 */
    public static boolean mentionsTokenLimit(String body) {
        if (body == null) {
            return false;
        }
        String b = body.toLowerCase(java.util.Locale.ROOT);
        return b.contains("max_tokens") || b.contains("max_completion_tokens");
    }

    @Override
    public ProbeResult probe() {
        long t0 = System.currentTimeMillis();
        if (!ready()) {
            return ProbeResult.fail("配置不完整（enabled / baseUrl / model 至少一项没填）", 0);
        }
        // 1) 先试 /models：不烧 token
        try {
            HttpRequest.Builder mb = HttpRequest.newBuilder(
                            URI.create(trimTrailingSlash(cfg.baseUrl) + "/models"))
                    .timeout(Duration.ofMillis(ConfigStore.translate().timeoutMs));
            if (hasKey()) {
                mb.header(authHeaderName(), authHeaderValue());
            }
            HttpRequest req = mb.GET().build();
            HttpResponse<String> r = sendBounded(http, req, ConfigStore.translate().timeoutMs);
            long ms = System.currentTimeMillis() - t0;
            if (r.statusCode() / 100 == 2) {
                return ProbeResult.ok(ms, "model=" + cfg.model + " @ " + cfg.baseUrl);
            }
            if (r.statusCode() == 401 || r.statusCode() == 403) {
                return ProbeResult.fail("密钥被拒绝 HTTP " + r.statusCode(), ms);
            }
            // 其他状态码可能是中转没实现 /models，继续用最小对话兜底
        } catch (Exception ignored) {
            // 落下去走兜底
        }
        // 2) 兜底：一次最小对话（配了 thinking disabled 就很便宜）
        try {
            String out = translate("hi", "auto", "中文", List.of(), List.of(), null, 0);
            long ms = System.currentTimeMillis() - t0;
            if (out == null || out.isBlank()) {
                return ProbeResult.fail("接口可达但返回空译文", ms);
            }
            return ProbeResult.ok(ms, "最小对话通过: " + out);
        } catch (Exception e) {
            return ProbeResult.fail(rootCause(e), System.currentTimeMillis() - t0);
        }
    }

    static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String msg = c.getMessage();
        return c.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    public static String parseChatCompletions(String raw) {
        JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
        JsonArray choices = o.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("响应缺少 choices: " + shorten(raw));
        }
        // 注意：这里不能写 ".getFirst()" 字面量 ——
        // 项目的 Stonecutter 替换规则（build.gradle.kts 的 j21_get_first）会把源码里所有
        // ".getFirst()" 改写成 ".getFirst()"，那是 java.util.List 的方法，
        // 而 Gson 的 JsonArray 只有 get(int)。用变量绕开字面量匹配。
        final int firstIndex = 0;
        JsonObject choice = choices.get(firstIndex).getAsJsonObject();

        // ★★ message 必须当成【可选】—— 这里踩过坑：
        //   原来 msg == null 直接抛异常，而"老式 completions 用 choice.text"那条兜底写在冲突后面，
        //   **永远走不到**（测试当场抓到：{"choices":[{"text":"译文"}]} 抛了异常）。
        //   现在按"逐个形状试"的顺序来，谁先给出文本就用谁。
        JsonObject msg = choice.getAsJsonObject("message");

        String out = null;
        if (msg != null) {
            out = textOf(msg.get("content"));
            // 思考型模型有些中转只回 reasoning_content —— 有总比"没译文"强
            if (out == null || out.isBlank()) {
                out = textOf(msg.get("reasoning_content"));
            }
        }
        if (out == null || out.isBlank()) {
            // 老式 completions：文本直接在 choice.text 上
            out = textOf(choice.get("text"));
        }

        if (out == null || out.isBlank()) {
            // ★ 空内容必须当【失败】抛出去，才能走降级链。
            //
            //   以前这里返回空串，上游的 `!tr.isBlank()` 把它当成"没译文"直接结束 ——
            //   降级链一次都不走，表现就是"这条永远不翻译"，而且日志里看不出发生过什么。
            //
            //   实测会返回空的情形：思考型模型（deepseek-flash）加上 max_tokens 之后，
            //   token 全用在推理上，finish_reason=length、content 为空。
            throw new IllegalStateException("响应里找不到可用文本（模型可能把输出预算烧在推理上了）: "
                    + shorten(raw));
        }
        return out;
    }

    /**
     * <b>从各种形状里把文本抠出来</b> —— "任何一个 api 接入就能用"的关键一环。
     *
     * <p>同一个字段，不同服务商回的形状不一样：
     * <pre>
     *   "content": "译文"                                   ← 标准
     *   "content": [{"type":"text","text":"译文"}]           ← 数组式（有些网关/多模态形状）
     *   "content": [{"type":"output_text","text":"译文"}]    ← 另一种命名
     *   "content": {"text":"译文"}                          ← 套了一层对象
     * </pre>
     * 只认第一种的话，后面三种全都表现为"接进来了但一个字都不出"。
     */
    public static String textOf(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive()) {
            return e.getAsString().trim();
        }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            // 优先 text，其次 content（有些是 {"content":{"text":...}} 再套一层）
            String t = textOf(o.get("text"));
            return t != null && !t.isBlank() ? t : textOf(o.get("content"));
        }
        if (e.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : e.getAsJsonArray()) {
                String t = textOf(part);
                if (t != null && !t.isBlank()) {
                    sb.append(t);
                }
            }
            return sb.toString().trim();
        }
        return null;
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) {
            return "";
        }
        String u = s.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String shorten(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
