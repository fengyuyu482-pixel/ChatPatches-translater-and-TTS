package obro1961.chatpatches.translate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static obro1961.chatpatches.ChatPatches.LOGGER;
import obro1961.chatpatches.translate.ApiProvider;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** GET 式接口：endpoint 里用 {text} 占位，请求时替换成 URL 编码后的正文。 */
public class SimpleGetProvider implements TranslateProvider {

    private static final String[] PREFERRED_KEYS = {
            "translatedtext", "trans_result", "translation", "translated", "trans", "dst",
            "text", "result", "msg", "data"
    };

    private final String id;
    /** 包内可见：TranslationManager.rank() 要读 title/name 判降级权重 ✓ */
    final ApiProvider cfg;
    private final HttpClient http;

    private volatile long lastCall = 0L;

    public SimpleGetProvider(String id, ApiProvider cfg) {
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

    @Override
    public boolean ready() {
        return cfg.enabled && cfg.endpoint != null && cfg.endpoint.contains("{text}");
    }

    @Override
    public String describe() {
        return id + " [simple-get] endpoint=" + cfg.endpoint;
    }

    /**
     * GET 式接口。endpoint 里可用这些占位符：
     * <ul>
     *   <li>{@code {text}} —— URL 编码后的正文（必须）</li>
     *   <li>{@code {source}} / {@code {target}} —— 语言代码，如 {@code zh-CN}、{@code en}</li>
     *   <li>{@code {langpair}} —— URL 编码的 {@code 源|目标}</li>
     * </ul>
     *
     * <p><b>{@code {langpair}} 是后加的，之前方向只能写死在 endpoint 里</b>：
     * mymemory 配的是 {@code langpair=en|zh-CN}，拿它做出站（中文→英文）方向就是反的，
     * 接口会把你发的中文原样还回来。加了占位符，同一个源两个方向都能用。
     *
     * <p>术语提示和风格要求仍然忽略 —— GET 式接口没地方放这些。
     */
    @Override
    public String translate(String text, String sourceLanguage, String targetLanguage,
                            List<String> context, List<String> glossaryHints, String styleNote,
                            long timeoutMs) throws Exception {
        throttle();
        String url = buildUrl(text, sourceLanguage, targetLanguage);
        if (url == null) {
            // ★ 注册表说这门语言 GET 接口翻不了 → 抛异常让降级链换下一个源。
            //   不能返回 null（上游会当成"成功但空译文"，降级链就不走了）
            throw new IllegalStateException("该 GET 接口不支持 " + targetLanguage
                    + "（注册表里没有 langpair 代码）");
        }
        long effective = timeoutMs > 0 ? timeoutMs : ConfigStore.translate().timeoutMs;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(effective))
                .header("User-Agent", "chatstudy/0.1")
                .GET()
                .build();
        // 走共用的"我们说了算"版本 —— JDK 的 HttpRequest.timeout 只管单次尝试，
        // 内部重试一次就是 2 倍时长（实测 8000ms 配置跑出 16142ms）。
        HttpResponse<String> resp = OpenAiCompatibleProvider.sendBounded(http, request, effective);
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return unwrap(resp.body());
    }

    /**
     * 替换 endpoint 里的占位符。public 是为了能单独测（拼错 URL 会静默返回原文）。
     *
     * @return 填好的 URL；<b>语言不被支持时返回 {@code null}</b> —— 调用方必须跳过，
     *         不能拿 {@code null} 拼进 URL（会请求到 {@code langpair=null|null} 然后被当成译文）
     */
    public static String buildUrlStatic(String endpoint, String text, String sourceLanguage, String targetLanguage) {
        String src = langCode(sourceLanguage);
        // ★ GET 式接口不接受 "auto"。
        //
        // mymemory 收到 langpair=auto|en 会回一句
        //   "'AUTO' IS AN INVALID SOURCE LANGUAGE . EXAMPLE: LANGPAIR=EN|IT ..."
        // 而且 HTTP 状态码还是 200 —— 于是这串报错会被当成译文，替换掉你要发的话发出去。
        // 正文就在手上，直接按字面判断源语言，别指望接口自己猜。
        if (src == null || "auto".equals(src)) {
            src = detectLangCode(text);
        }
        String dst = langCode(targetLanguage);
        if (src == null || dst == null) {
            // ★ 注册表说这门语言 GET 接口翻不了（没有 langpair 代码）→ 不拼 URL
            return null;
        }
        return endpoint
                .replace("{text}", URLEncoder.encode(text, StandardCharsets.UTF_8))
                .replace("{source}", URLEncoder.encode(src, StandardCharsets.UTF_8))
                .replace("{target}", URLEncoder.encode(dst, StandardCharsets.UTF_8))
                .replace("{langpair}", URLEncoder.encode(src + "|" + dst, StandardCharsets.UTF_8));
    }

    /**
     * 按正文里的字符猜语言代码。
     *
     * <p>只做"手上确定的几种"，其余一律当英文 —— 猜错的代价比不猜小：
     * 送 {@code auto} 进去会被接口直接拒绝。
     */
    public static String detectLangCode(String text) {
        // ★ 越南语必须先判 —— 它是拉丁字母，profile().langCode() 会把它当英文 ✗
        //   （GET 型接口收到 en 就会按英文翻，越南语目标根本到不了）
        if (ScriptDetector.isVietnamese(text)) {
            return "vi";
        }
        // 其余判断统一交给 ScriptDetector.Profile。
        return ScriptDetector.profile(text).langCode();
    }

    private String buildUrl(String text, String sourceLanguage, String targetLanguage) {
        return buildUrlStatic(cfg.endpoint, text, sourceLanguage, targetLanguage);
    }

    /**
     * 语言 → GET 接口要的代码（{@code langpair=fr|zh-CN} 这种）。
     *
     * <h2>★ 全部查注册表，不再硬编码</h2>
     * <p>旧实现是一串 {@code if (s.contains("中文"))}，只认 6 种语言 ——
     * 目标设成法语（{@code fr_fr}）时一个都不命中，原样把 {@code fr_fr} 拼进 URL，
     * mymemory 直接回：
     * <pre>
     *   'FR_FR' IS AN INVALID TARGET LANGUAGE . EXAMPLE: LANGPAIR=EN|IT
     *   USING 2 LETTER ISO OR RFC3066 LIKE ZH-CN.
     * </pre>
     * 而且它<b>返回 HTTP 200</b>，那句报错差点被当成译文发到公聊
     * （靠 {@link #sanitize} 的"全大写报错"形状判据才拦下来）。
     *
     * <p>现在：注册表里每条语言都带 {@code providers.mymemory}（{@code zh-CN} /
     * {@code fr-FR} / {@code null}）。
     * <ul>
     *   <li>有值 → 直接用（这就是 langpair 要的写法）</li>
     *   <li>{@code null} → 这项服务翻不了这门语言，返回 {@code null} 让调用方跳过</li>
     *   <li>认不出语言 → 返回 {@code null}（不编造代码）</li>
     * </ul>
     *
     * @return 语言代码；{@code null} 表示这门语言不能用 GET 接口翻
     */
    public static String langCode(String lang) {
        if (lang == null || lang.isBlank()) {
            return null;
        }
        if ("auto".equalsIgnoreCase(lang)) {
            return "auto";
        }
        String code = Language.mymemoryCode(lang);
        if (code == null || code.isBlank()) {
            LOGGER.debug("[chatpatch] GET 接口不支持语言「{}」（注册表里没有它的 langpair 代码）", lang);
            return null;
        }
        return code;
    }

    @Override
    public ProbeResult probe() {
        long t0 = System.currentTimeMillis();
        if (!ready()) {
            return ProbeResult.fail("endpoint 未填，或缺少 {text} 占位", 0);
        }
        try {
            String out = translate("hello", "auto", "中文", List.of(), List.of(), null, 0);
            long ms = System.currentTimeMillis() - t0;
            if (out == null || out.isBlank()) {
                return ProbeResult.fail("接口可达但返回空", ms);
            }
            return ProbeResult.ok(ms, "样例译文: " + out);
        } catch (Exception e) {
            return ProbeResult.fail(OpenAiCompatibleProvider.rootCause(e), System.currentTimeMillis() - t0);
        }
    }

    /** 同一 provider 串行限速，避免被公共接口封。 */
    private synchronized void throttle() throws InterruptedException {
        long wait = lastCall + ConfigStore.translate().minIntervalMs - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastCall = System.currentTimeMillis();
    }

    /** 有的接口返回纯文本，有的返回 JSON；两种都尽量捞出来。 */
    private static String unwrap(String body) {
        if (body == null) {
            return null;
        }
        String s = body.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).trim();
        }
        if (s.startsWith("{")) {
            try {
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                String preferred = findByPreferredKey(o);
                if (preferred != null) {
                    return sanitize(preferred);
                }
                String any = firstString(o);
                if (any != null) {
                    return sanitize(any);
                }
            } catch (Exception ignored) {
                // 不是 JSON 就按纯文本处理
            }
        }
        return sanitize(s);
    }

    /**
     * 把"接口报错"当失败返回，别当译文。
     *
     * <p>mymemory 这类公共接口出错时<b>照样返回 HTTP 200</b>，正文是一句全大写英文报错。
     * 不拦的话它会一路走到聊天栏 —— 实测见过：
     * {@code 'AUTO' IS AN INVALID SOURCE LANGUAGE . EXAMPLE: LANGPAIR=EN|IT ...}
     * 然后被当成"译文"替换掉玩家真正要说的话发出去。返回 null 让降级链接着试下一个源。
     *
     * <p>★★ <b>2026-10-13 补漏</b>：上面那张表漏了最常见的一句 ——
     * 用户实测「我发出去的越南语叫我选择两个语言」✗，
     * 公聊上出现的是 <b>{@code PLEASE SELECT TWO DISTINCT LANGUAGES}</b> ✗ ——
     * 它就是 mymemory 在来源语言不合法时返回的正文（HTTP 200 ✓），
     * 而我们<b>没认出来</b> ✗ → 当成译文发出去了 ✗。
     *
     * <p>教训：这种"白名单式识别"永远会漏 ✗ ——
     * 所以除了补词，下面还加了一条<b>形状判据</b>兜底 ✓：
     * 公共接口的报错<b>几乎都是一句全大写、没有小写字母的英文短句</b> ✓，
     * 而正常译文不会是那样（`I DON'T KNOW` 这种会有撇号和大写组合，但很少整句全大写 ✓）。
     */
    static public String sanitize(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.toUpperCase(Locale.ROOT);
        if (t.contains("INVALID SOURCE LANGUAGE")
                || t.contains("INVALID LANGPAIR")
                || t.contains("INVALID TARGET LANGUAGE")
                // ★★ 漏掉的那句（用户实际撞到的）
                || t.contains("DISTINCT LANGUAGES")
                || t.contains("SELECT TWO")
                || t.contains("PLEASE SELECT")
                || t.contains("MYMEMORY WARNING")
                || t.contains("QUERY LENGTH LIMIT")
                || t.contains("NO QUERY SPECIFIED")
                || t.contains("YOU USED ALL AVAILABLE FREE TRANSLATIONS")) {
            LOGGER.warn("[chatpatch] 接口返回的是报错不是译文，已丢弃: {}", s);
            return null;
        }
        // ★★ 形状兜底：整句全大写（有字母、无小写）+ 够长 → 判为接口报错 ✗
        //
        //   为什么要有这条：上面的白名单是"见过的都列上" ✗ ——
        //   本轮就漏了最常见的一句（PLEASE SELECT TWO DISTINCT LANGUAGES ✗）。
        //   公共接口的报错有个共同形状：**整句全大写、没有一个小写字母** ✓；
        //   正常译文不会那样（即使翻出 "I DON'T KNOW"，也有撇号、且很少整句全大写 ✓）。
        if (looksLikeErrorShout(s)) {
            LOGGER.warn("[chatpatch] 接口返回的像是一句全大写报错，已丢弃: {}", s);
            return null;
        }
        return s;
    }

    /**
     * 这句是不是"接口的全大写报错"。
     *
     * <p>判据（都满足才算）：
     * <ol>
     *   <li>长度 ≥ 25 —— ★ 这个阈值是**被自测逼出来的**：
     *       一开始写 12，结果把正常译文 {@code "I DON'T KNOW"} 也杀了 ✗
     *       （那是合法译文，输入全大写时译文也可能全大写 ✓）。
     *       真正的接口报错都是**整句长文** ✓：
     *       {@code PLEASE SELECT TWO DISTINCT LANGUAGES}(35)、
     *       {@code 'AUTO' IS AN INVALID SOURCE LANGUAGE …}(80+)</li>
     *   <li>至少有 20 个字母</li>
     *   <li><b>一个小写字母都没有</b> —— 这是关键形状 ✓</li>
     * </ol>
     */
    static public boolean looksLikeErrorShout(String s) {
        if (s == null || s.trim().length() < 25) {
            return false;
        }
        int letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') {
                return false;   // 有小写 → 不是那种报错 ✓
            }
            if (c >= 'A' && c <= 'Z') {
                letters++;
            }
        }
        return letters >= 20;
    }

    private static String findByPreferredKey(JsonObject o) {
        for (String wanted : PREFERRED_KEYS) {
            for (String k : o.keySet()) {
                if (!k.toLowerCase(Locale.ROOT).equals(wanted)) {
                    continue;
                }
                JsonElement v = o.get(k);
                if (v == null || v.isJsonNull()) {
                    continue;
                }
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                    return v.getAsString();
                }
                if (v.isJsonObject()) {
                    String nested = findByPreferredKey(v.getAsJsonObject());
                    if (nested != null) {
                        return nested;
                    }
                }
            }
        }
        return null;
    }

    private static String firstString(JsonObject o) {
        for (String k : o.keySet()) {
            JsonElement v = o.get(k);
            if (v == null || v.isJsonNull()) {
                continue;
            }
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                return v.getAsString();
            }
            if (v.isJsonObject()) {
                String nested = firstString(v.getAsJsonObject());
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
