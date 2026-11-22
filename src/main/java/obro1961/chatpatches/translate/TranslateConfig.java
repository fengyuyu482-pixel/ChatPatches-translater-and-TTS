package obro1961.chatpatches.translate;

import java.util.ArrayList;
import java.util.List;

/**
 * 翻译配置块。
 *
 * <p>现在<b>全部放文件</b>（{@code config/chatpatches/translate.json}），
 * 以后要进游戏界面的话，用 YACL 的"分组 + 按钮"做服务商列表即可（分组=一个服务商）。
 */
public class TranslateConfig {

    /** 总开关。 */
    public boolean enabled = true;

    /**
     * <b>自动翻译</b>（入站、自动）：关掉后只是"不再自动翻"，
     * 右键「翻译这条」仍可手动翻（快捷面板的开关只动这个字段）。
     */
    public boolean autoEnabled = true;

    /**
     * <b>要不要翻译自己说的话（入站方向）。</b>
     *
     * <p>默认 {@code false} = 不翻 —— 自己打的字自己当然看得懂。
     *
     * <h2>为什么默认关</h2>
     * <p>你不翻自己的话，但服务器会<b>把你发出去的话回显给你</b>
     * （尤其是出站翻译之后：你打中文 → 发出去是英文 → 服务器把英文回显给你）。
     * 那条回显如果走入站翻译，就会被<b>翻回中文</b> —— 白跑一趟，而且更糟的是
     * <b>你原本打的中文从没出现在屏幕上过</b>（用户原话：「相当于我原文消失了」）。
     *
     * <p>所以你打的话，屏幕上应该先出现「原文 + ↳ 译文」（出站那条路径负责），
     * 回显则应该被认出来、不再翻。
     *
     * <h2>什么时候该打开</h2>
     * <ul>
     *   <li>你在用<b>别人视角</b>看自己说的话（录视频、测服）</li>
     *   <li>你发的语言和目标语言不一样，想确认翻译效果</li>
     * </ul>
     *
     * <p><b>打开后的风险</b>：出站已经翻过一次，回显再翻一次 —— 可能出现
     * 「中文 → 英文 → 中文」的往返失真。除非你明确需要，否则保持关闭。
     */
    public boolean translateOwnMessages = false;

    /**
     * 源语言；<b>默认写明「英语」</b>（用户要求「严格按照配置文件写」）。
     *
     * <p>★ 原来默认 {@code auto} = 让模型自己认 ✗ ——
     * 那意味着每条消息都要先猜一次，正是用户说的「自动检测语言」，
     * 也是 {@code à/é} 那类判断反复出错的地方 ✗。
     * 现在写明白：MIA 上绝大多数聊天就是英语 ✓。
     *
     * <p>想恢复"让模型自己认"就填 {@code auto}（保留为<b>显式选择</b> ✓）。
     */
    public String sourceLanguage = "英语";

    /** 目标语言 —— <b>任意语言都可以改</b>，中英只是默认值。 */
    public String targetLanguage = "简体中文";

    /**
     * <b>出站：我打的是哪门语言</b> —— 与对照表<b>锁死</b>（用户要求）。
     *
     * <pre>
     *   "中文"   → 打的话里有汉字才翻译 → 发英文；纯英文/数字/表情原样发 ✓
     *   "越南语" → 有越南语字符才翻译 ✓
     *   "auto"   → 老行为：按字符猜"这像不像外语"（显式选择才生效）
     * </pre>
     *
     * <p><b>为什么要有它</b>：原来是<b>隐藏的启发式</b> ✗ ——
     * 「{@code Chào nhé} 算不算外语」这种判断反复出错，
     * 而且配置里<b>看不出来</b>这个决定在哪儿做 ✗。
     * 现在把语言写进配置 ✓，判据只有一条：
     * <b>文本里有没有【这里声明的语言】的字符</b> ✓ —— 出 bug 时改配置就能改行为 ✓
     */
    public String outgoingSourceLanguage = "中文";

    /** 当前用哪个服务商（填**槽位号**；老配置里是 {@link ApiProvider#name}，启动时自动迁移）；留空则按列表顺序第一个能用的。 */
    public String activeProvider = "";

    /**
     * <b>槽位号的历史最高水位</b>（只增不减）。
     *
     * <p>★ 为什么必须有它（自测抓出来的 ✗）：光用"现有最大值 + 1"**会复用删掉的号** ✗ ——
     * 比如有 7、8、9 三条，删掉 9 之后最大值变回 8 ✗ → 新加的又是 9 ✗ →
     * 而老配置里可能还存着"用 9 号" ✓ → **指到了新加的那条** ✗
     *
     * <p>所以发号必须看这个<b>水位</b> ✓：只增不减 ✓ 删掉谁都不影响 ✓
     */
    public int nextSlot = 0;

    /** 服务商列表，<b>顺序 = 优先级</b>，前面失败自动往后降级。 */
    public List<ApiProvider> providers = new ArrayList<>();

    /**
     * <b>出站翻译优先用哪个源。</b>
     *
     * <p>实测：出站走大模型（deepseek）要 3.9~23.9 秒且质量不稳（同一句"奶龙"翻出
     * Milk Dragon / Healer Dragon / Nailong / nailong 四种），而 {@code mymemory}
     * 这种专门的翻译接口只要 430~700 毫秒。
     *
     * <p>你自己发消息时是在<b>等</b>的（出站必须等译文回来才能发），所以速度优先。
     * 入站不受影响，仍然走 activeProvider（大模型的译文更自然）。
     *
     * <p>留空 = 和入站用同一套顺序。
     */
    public String outgoingProvider = "mymemory";

    /**
     * 单次请求超时（毫秒）。
     *
     * <p>从 20000 调到 8000：聊天翻译用不了 20 秒，而超时是<b>每个 provider 各自算</b>的 ——
     * 降级链上有几个源就可能等几个 20 秒。实测因此出现过 40 秒的等待。
     *
     * <p><b>2026-10-13 又从 8000 调到 4000</b>：B站那个免费源开始限流（实测大量
     * {@code request timed out}），而它<b>健康时只要 300~1000ms</b> ✗ ——
     * 给它 8 秒纯属浪费：每条消息都要先干等 8 秒才降级，用户看到的是
     * "消息半天不出译文"，像是坏了 ✗。
     *
     * <p>4000 的依据：正常响应 &lt; 1.2s，健康时留 3 倍余量；不健康时少等一半 ✓。
     * 网络特别差的可以自己往大调（这个值本来就是给用户调的）。
     */
    public int timeoutMs = 4000;

    /** 撞 429（限流）后，这个源先冷却多久；连续撞会翻倍。 */
    /**
     * <b>语言别名表</b>（M6）：每项 {@code "别名=规范名"}，例如 {@code "歪果话=英语"}。
     * 内置已覆盖 中/英/俄/西/法/越/日/韩 的常见写法（见 {@link Language}），
     * 这里只补"内置没有的自定义叫法"。
     */
    public List<String> languages = new ArrayList<>();

    /**
     * 调试日志开关（M8）：打开后每一条"没翻译"都会写一行原因
     * （类型不对 / 纯符号 / 已是目标语言 / 不是源语言 / 术语表拦了 / 自动翻译关了）。
     * 默认关，正常玩不会刷屏。
     */
    public boolean debug = false;

    public int rateLimitBackoffMs = 20000;

    /** 冷却上限（连续撞很多次也不会超过它）。 */
    public int rateLimitBackoffMaxMs = 120000;

    /** GET 型接口两次请求的最小间隔（毫秒），防限流。 */
    public int minIntervalMs = 800;

    /** 出站翻译：自己打中文时是否翻成英文再发出去。 */
    public boolean translateOutgoing = true;

    /**
     * <b>点击后翻译</b>（R16，用户 2026-10-20：「把点击后翻译加入我们的模组，作为一个选项」）。
     *
     * <p>{@code false}（默认）= 现在这样：别人说话自动翻。
     *
     * <p>{@code true} = <b>不自动翻</b>；右键某条消息 →「翻译这条」才翻。
     * 好处：省 token、刷屏时不会一次打一串 API。
     *
     * <p>两种模式下「翻译这条」都在菜单里（自动模式下也能手动补翻一条）。
     * 译文仍走同一套落盘（{@code translation} + {@code refSeq} + {@code lang}），
     * 所以历史、导出、灌回都不受影响。
     */
    public boolean translateOnClick = false;

	/** 连接检测总开关：关掉后不探任何服务、不提示。 */
	public boolean connectCheckEnabled = true;

	/** 免费 API 总开关（bilibili / mymemory / 免费槽）；关掉后这三类一律不参与。 */
	public boolean freeApiEnabled = true;

	/** 自动检测本机服务：开=定期探端口，探到就启用。关=完全不检测。 */
	public boolean autoDetectService = false;

	/** 自动检测间隔（秒）。 */
	public int autoDetectIntervalSec = 30;

	/** 是否翻译系统消息（服务器公告/进出服提示）；关闭时只翻命中 systemKeywords 的。 */
	public boolean translateSystemMessages = false;

    /** 出站翻译的目标语言。 */
    public String outgoingTargetLanguage = "English";

    /**
     * 系统消息的<b>关键句白名单</b>。
     *
     * <p>系统播报<b>默认一律不翻</b>。默认只留一类 —— <b>服务器公告（重启 / 关服）</b>，
     * 因为那是唯一"不知道会出事"的：服务器要关了你还不知道，东西就没了。
     *
     * <p>以下这些<b>按需求特意不翻</b>（它们都出现过在你的记录里）：
     * <ul>
     *   <li>遗物提示：{@code You hear whispers that ... has obtained a Grade III relic.}</li>
     *   <li>传送预警：{@code You will be teleported in 5 seconds, please stand still…}</li>
     *   <li>成就播报：{@code ... has made the advancement [...]}</li>
     *   <li>死亡播报：{@code X was doomed to fall in the Forest of Temptation}</li>
     *   <li>进服退服：{@code + ┃ 玩家名} / {@code - ┃ 玩家名}</li>
     * </ul>
     * 想看哪个就自己往下面加 —— <b>写句子片段，别写单词</b>。
     *
     * <p><b>为什么别写单词</b>：匹配是"不区分大小写的子串包含"。
     * 写 {@code "event"} 会命中 {@code "eventually"}，写 {@code "goal"} 更是满地都是。
     * 写 {@code "has made the advancement"} 就只命中成就播报那一条。
     *
     * <p><b>留空 = 所有系统消息都不翻。</b>
     */
    public List<String> systemKeywords = new ArrayList<>(defaultSystemKeywords());

    /**
     * <b>哪些命令是"带频道的发言"</b> —— 这些命令后面的正文要走出站翻译。
     *
     * <p><b>为什么需要</b>：有些服务器的全球频道要打成命令发（{@code /g 内容}），
     * 命令走 {@code ALLOW_COMMAND}，<b>不经过聊天的出站闸门</b> ——
     * 于是打出去的中文既没被翻译、也没被改造回显。
     * 而本地频道往往是"切模式"（切完直接打字 = 普通聊天），所以能正常工作。
     * 用户实测就是这个现象：<b>{@code /l} 可以、{@code /g} 不行</b>。
     *
     * <p><b>为什么必须白名单</b>：不能见到命令就翻。
     * {@code /msg Bob 你好} 如果把 {@code Bob 你好} 整段拿去翻，会把收信人名字也翻了。
     * 只认这里列出的命令词，而且<b>只翻命令词之后的那一整段</b>。
     *
     * <p>老配置没有这个字段（Gson 读成 {@code null}）→ 用默认值，见 {@link #defaultChannelCommands()}。
     * 显式留空 {@code []} = 不拦任何命令。
     */
    public List<String> channelCommands = new ArrayList<>(defaultChannelCommands());

    /** 常见频道命令词。用户可按自己服务器的 {@code /help} 增删。 */
    public static List<String> defaultChannelCommands() {
        return List.of("g", "gc", "global", "l", "lc", "local", "ch", "channel");
    }

    /**
     * 默认关键句（从玩家真实记录里挖出来的，不是拍的）。
     *
     * <p><b>为什么要有这个方法</b>：老配置文件里没有 {@code systemKeywords} 这个字段，
     * Gson 会把它读成 {@code null}。如果代码直接判空就当成"不用白名单"，
     * 功能会<b>静默失效</b> —— 玩家配了也不会生效，还查不出原因。
     * 所以这里区分两种情况：
     * <ul>
     *   <li>{@code null}（字段缺失，老配置）→ 用这份默认值</li>
     *   <li>{@code []}（显式留空）→ 系统消息全不翻</li>
     * </ul>
     */
    public static List<String> defaultSystemKeywords() {
        return List.of(
                // 只留「服务器要关了」这一类 —— 不知道会掉线，这条最要命。
                //
                // 遗物提示 / 传送预警 / 成就 / 死亡播报 / 进服退服 都【按需求不翻】。
                //
                // ⚠️ 2026-10-12 我曾把"遗物提示 + 传送预警"加进这个默认表（用户当时说了句
                //    "直接默认吧"），随后用户澄清是误会 —— 他真正在问的是另一条 wb 聊天消息
                //    （那是分类 bug，见 SPEAKER_PREFIX）。所以这里恢复原样。
                //    **教训：反转用户之前明确写下的需求，必须再确认一次，不能只凭一句"对"。**
                "server will restart",
                "server is restarting",
                "restarting in",
                "server will shut down",
                "server is shutting down",
                "shutting down in",
                "server will close",
                "closing in"
        );
    }
}
