package obro1961.chatpatches.translate;

import java.util.Map;

/**
 * 一个"服务商"条目 —— <b>翻译和朗读共用这一个形状</b>。
 *
 * <p>设计要点：<b>看字段就知道这家有什么能力</b>
 * <ul>
 *   <li>有 {@link #model} / {@link #baseUrl} → 能当翻译的 "api" 源</li>
 *   <li>有 {@link #endpoint}（带 <code>{text}</code> 占位）→ 能当翻译的 "GET" 源（MyMemory 那种）</li>
 *   <li>有 {@link #ttsModel} → 能当朗读源</li>
 *   <li>{@link #type} = {@code local} → 本地引擎（系统语音），不需要 baseUrl/key</li>
 * </ul>
 * 所以界面上"选朗读服务商"时，只列出有 {@code ttsModel} 的那些，不用另写能力表。
 */
public class ApiProvider {

    // ---- 公共 ----

    /**
     * <b>槽位号 —— 这条服务商的"身份证"</b>（分配后<b>永不复用</b>）。
     *
     * <p>★ <b>为什么要有它</b>（用户要求「换成槽位」）：
     * 原来 {@link #name} 一个字段兼了两个职责 ✗ ——
     * 既是"机读身份"（{@code PROVIDERS} 按它建 Map、{@code activeProvider} 存它），
     * 又是"人读显示名"（界面上写"在线"、"免费槽位1"）。后果三条：
     * <pre>
     *   ① 名字空着 → rebuild() 直接 continue → 用户填了免费槽位也不生效 ✗
     *   ② 改名字   → activeProvider 里存的旧名字失配 ✗（要专门写 rename() 兜）
     *   ③ 名字认不出 → 看门狗 labelFor() 静默不监控 ✗
     * </pre>
     *
     * <p>现在：<b>身份看 {@code slot}</b> ✓，<b>显示看 {@link #title}</b> ✓ ——
     * 用户想怎么改名字都行 ✓，不影响任何功能 ✓
     *
     * <p>分配规则：加载配置时给 {@code slot <= 0} 的补齐 ✓，
     * 取"现有最大值 + 1" —— <b>删掉某条后它的号不会给别人</b> ✓（留洞 ✓）
     */
    public int slot = 0;

    /** 标识名（<b>仅用于显示/兼容旧配置</b>；身份看 {@link #slot}）。 */
    public String name = "";
    /** 给人看的标题，比如 {@code 免费：xxx} / {@code 付费：xxx}。留空就用 name。 */
    public String title = "";
    /** 关掉就不参与。 */
    public boolean enabled = true;

    /**
     * <b>代码支不支持这一条</b>（§6.4）。
     *
     * <p>{@code false} = 界面上保留、配置里能看到，但运行时<b>跳过并写日志</b> ——
     * 用来表达"本地模型只留接口、还没实现"这类状态（R6 ④）。
     *
     * <p>为什么不直接删掉这条：用户配置里已经写着的源，删掉会让"我明明配了"变成谜团；
     * 留一个<b>明确的</b> {@code implemented:false}，日志会告诉你"这条还没实现，被跳过了"。
     */
    public boolean implemented = true;
    /**
     * {@code api} = 走网络接口；{@code local} = 本地引擎（如系统自带语音），不需要 baseUrl/apiKey。
     */
    public String type = "api";

    // ---- 连接（api 型才需要）----

    public String baseUrl = "";
    public String apiKey = "";

    /**
     * <b>鉴权头名字</b>；留空 = 用标准值 {@code Authorization}。
     *
     * <p>为什么可配置：不是每家都用标准头 ——
     * Azure OpenAI 走 {@code api-key}，有些网关用 {@code x-api-key}。
     * 写死一个的话，这些家的 key 怎么填都调不通。
     */
    public String authHeader = "";

    /**
     * <b>鉴权头前缀</b>；留空 = 用标准值 {@code "Bearer "}（注意含尾随空格）。
     *
     * <p>Azure 那种是裸 key，前缀要显式设成空串 —— 所以这里区分
     * {@code null}（用默认）和 {@code ""}（显式不要前缀）。
     */
    public String authPrefix = null;

    // ---- 翻译用 ----

    /** OpenAI 兼容型的模型名。 */
    public String model = "";
    /** GET 型接口模板，必须含 {@code {text}} 占位；非空即代表这是 GET 型。 */
    public String endpoint = "";
    /** 额外塞进请求体的字段（比如 DeepSeek 的 {@code {"thinking":{"type":"disabled"}}}）。 */
    public Map<String, Object> extraBody;

    // ---- 朗读用 ----

    /** TTS 模型名；<b>有值即代表这家能朗读</b>。 */
    public String ttsModel = "";
    /** 音色。 */
    public String voice = "";
    /** 音频格式；<b>优先 wav/pcm</b>，这样 Java 原生就能播，不用带解码器。 */
    public String format = "wav";
    /** 语速。 */
    public double speed = 1.0;

    public ApiProvider() {
    }

    /** 是不是 GET 型翻译源。 */
    public boolean isGetType() {
        return endpoint != null && !endpoint.isBlank();
    }

    /** 能不能当翻译源。 */
    public boolean canTranslate() {
        return isGetType() || (baseUrl != null && !baseUrl.isBlank() && model != null && !model.isBlank());
    }

    /** 能不能当朗读源。 */
    public boolean canSpeak() {
        if ("local".equalsIgnoreCase(type)) {
            return true; // 本地引擎只要有名字/音色就能用
        }
        // 本地 HTTP 引擎（GPT-SoVITS 等）不需要 ttsModel，有 loopback 地址就算可用
        if (baseUrl != null && (baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost"))) {
            return true;
        }
        return ttsModel != null && !ttsModel.isBlank();
    }

    /** 给人看的名字。 */
    public String displayTitle() {
        return title == null || title.isBlank() ? name : title;
    }

    @Override
    public String toString() {
        return "ApiProvider{" + name + ", type=" + type + ", translate=" + canTranslate() + ", speak=" + canSpeak() + "}";
    }
}
