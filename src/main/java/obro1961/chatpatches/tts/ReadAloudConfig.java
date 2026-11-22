package obro1961.chatpatches.tts;

import obro1961.chatpatches.translate.ApiProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 朗读配置块。跟翻译块<b>同一套骨架</b>（providers 列表 + activeProvider + type=api/local）。
 */
public class ReadAloudConfig {

    /**
     * 配置格式版本（迁移用，§6.5 规则 4）。
     *
     * <p><b>2 = D9 之后</b>：老文件里没有这个键 → 说明是 D9 之前写的，
     * 里面的 {@code onlyWhenTranslated: true} 是<b>旧默认值</b>，不是用户的选择，
     * 加载时一次性改 false 并把这个键补上。补上之后就不会再动它。
     *
     * <p>★ 注意 Gson 的坑（10-13 踩过）：缺键时字段保留<b>初始值</b>，
     * 所以这里写 2 会让老文件"看起来已经是 2" —— 因此迁移判定必须看<b>文件里有没有这个键</b>
     * （见 {@code ConfigStore.migrateReadAloudDefaults}），不能用字段值判。
     */
    public int schemaVersion = 2;

    /**
     * 总开关。
     *
     * <p><b>默认开</b>（2026-10-12 从"默认关"改过来的）。
     *
     * <p>原来的理由没错（朗读会出声，不该一装上就响），但代价是把人坑了：
     * 分发出去之后，全新安装的人拿到 {@code enabled=false}，
     * 而<b>右键菜单的手动朗读不受这个开关管</b> —— 于是现象是
     * <b>"右键能读、自动不读"</b>，看起来像 bug，其实只是配置默认值。
     * 用户实测反馈原话：「它那边可以右键主动朗读，不能自动朗读」。
     *
     * <p>何况出声范围本来就有限：{@link #onlyWhenTranslated} 默认 <b>false</b>（D9，
     * 朗读与翻译解耦）；要"只读需要翻译的那几条"，把它改 true 即可。
     * 真嫌吵：把 {@code enabled} 改 false（或游戏里打 {@code /chatstudy} 看当前状态）。
     */
    /**
     * 自动朗读总开关。<b>2026-10-21 用户要求出厂默认改成关</b>：
     * 萌新第一次进服不该被自动念话吓到；想开的人去 2-readaloud.json 打开。
     * 右键「朗读这条」不受这个开关影响（手动永远有效）。
     */
    public boolean enabled = false;

    // ★ 这里原来有个 `public String what = "原文";`（读原文/读译文）—— 已删除。
    //
    // 它是个死配置：`readOriginal()` 全文零调用，`planSpeech` 永远读原文，
    // 实测的 readAloud.json 里写着 "原文" 却从来没被读过。挂着一个不生效的开关比没有更糟 ——
    // 用户以为改了它有用。
    //
    // 更关键的是：**"读译文"和现有设计是冲突的**，不是漏接线而已。
    // 编排是「收到消息立刻朗读原文，不等翻译」，而译文是几百毫秒后才异步回来的。
    // 要读译文就得把朗读推迟到译文到达 —— 那会改变体验（现在是"听到就知道有人说话"）。
    // 所以真要支持它，是一个独立的特性设计，不是"把字段接上"。

    /**
     * 只读"需要翻译的那些"原文 —— 跟着目标语言自动变。
     *
     * <p>目标中文时：英文消息读、中文消息不读（不然很吵）。
     * 目标改成 English 后自动反过来。
     *
     * <p><b>★ 2026-10-20 默认改 false（D9：朗读与翻译解耦）。</b>
     * 用户原话：「翻译跟朗读分开运行，朗读只读原文」。
     * 默认 true 的实际后果是：<b>翻译失败/超时的那几条就不读了</b> ——
     * 断网进服会整场静音，而"有没有人说话"跟翻译成不成功毫无关系。
     * 现在默认 false：原文一到就判读，翻译自己跑自己的。
     */
    /** 读原文（名字+原文）。 */
    public boolean readOriginal = true;

    /** 读译文（在原文之后）。 */
    public boolean readTranslation = false;

    /**
     * <b>只朗读英语原文</b>（用户要求，默认开）。
     *
     * <p>为什么需要它：我们的朗读读的是<b>原文</b> ✓，而系统里可用的嗓音只有
     * {@code zh-CN} / {@code en-US} 两档 ✗（{@code LocalTts.voiceLanguageFor}）——
     * 拿英文嗓音念越南语/俄语就是怪声 ✗。
     * 实测那种怪声用户已经听过了（{@code cảm ơn} 用 Microsoft Zira 念）。
     *
     * <p>打开后的效果：<b>只念英语</b> ✓ ——
     * 中文、越南语、俄语、日文、韩文原文都<b>不念</b> ✗
     * （中文其实有 Huihui 能念 ✓，但这条开关是"只读英语"的字面意思 ✓，
     *   想保留中文把它设成 {@code false} 即可 ✓）
     *
     * <p>判据：拉丁字母 + <b>没有变音符号</b>（{@code à é ơ ư} 一律不算英语 ✗）
     * —— 越南语就是拉丁字母 ✗，光看"是不是拉丁"分不出来，必须排除变音符 ✓。
     */
    public int minLength = 2;
    public int maxLength = 300;

    /**
     * <b>朗读专用</b>的替换表：缩写 → 念出来用的话。
     *
     * <p><b>为什么要跟翻译术语表分开</b>：翻译术语表是给"看"的（{@code wb} → 欢迎回来），
     * 而朗读要的是"能念出来的词"。拿中文替换去念英文嗓音 = 中英夹杂，实测被吐槽过。
     * 所以这里独立一份，而且<b>默认填英文</b> —— 让英文嗓音念真词，而不是一个字母一个字母念
     * （{@code wb} 会被念成 "double-u bee"）。
     *
     * <p>匹配规则：词边界 + 不区分大小写。{@code wb} 会换，{@code wbwb} 里的不会。
     * <b>留空 = 不做任何替换，老实念原文。</b>
     */
    public java.util.Map<String, String> replacements = defaultReplacements();

    /**
     * 默认替换表 —— 键取自你术语表里真实在用的那批缩写，
     * 值是我按英语母语玩家会怎么念写的。
     */
    public static java.util.Map<String, String> defaultReplacements() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("wb", "welcome back");
        m.put("ty", "thank you");
        m.put("thx", "thanks");
        m.put("tysm", "thank you so much");
        m.put("yw", "you're welcome");
        m.put("np", "no problem");
        m.put("sry", "sorry");
        m.put("mb", "my bad");
        m.put("gg", "good game");
        m.put("gj", "good job");
        m.put("nc", "nice");
        m.put("ez", "easy");
        m.put("pog", "poggers");
        m.put("rip", "rest in peace");
        m.put("hehe", "he he");
        m.put("haha", "ha ha");
        m.put("lol", "laughing out loud");
        m.put("idk", "I don't know");
        m.put("imo", "in my opinion");
        m.put("btw", "by the way");
        m.put("afk", "away from keyboard");
        m.put("brb", "be right back");
        m.put("o/", "hello");
        m.put("\\o", "hello");
        return m;
    }

    /**
     * 要不要朗读<b>自己发出</b>的消息。
     *
     * <p>服务器会把你发的消息原样广播回来，那条和别人的消息长得一样。关上就不会听见自己说话。
     *
     * <p><b>默认开</b>：一是听一遍能确认译文对不对；二是"不想听自己"应该由你决定，
     * 不该由我替你关掉 —— 上一版就是擅自关掉了，结果表现为"朗读整个不工作了"，
     * 而那 2 分钟里其实只有你一个人在说话。
     */
    public boolean speakOwnMessages = true;

	/** 是否朗读系统消息（服务器公告/进出服提示等）。默认关。 */
	public boolean speakSystemMessages = false;

    /** 0.0 ~ 2.0。 */
    public float volume = 1.0f;

    /** 当前用哪个引擎（填 {@link ApiProvider#name}）；留空则用列表里第一个能用的。 */
    public String activeProvider = "";

    /**
     * <b>槽位号历史最高水位</b>（只增不减）—— 跟 {@code TranslateConfig.nextSlot} 同一套规则 ✓。
     *
     * <p>朗读侧也要发号 ✓：翻译和朗读必须用<b>同一套身份规则</b> ✗ 否则又是两套，改一处忘一处。
     */
    public int nextSlot = 0;

    /** 引擎列表，顺序 = 优先级，前面失败自动往后降级。 */
    public List<ApiProvider> providers = new ArrayList<>();

    public int timeoutMs = 20000;

    /** 音频缓存条数（同一句话不重复请求）。 */
    public int cacheSize = 64;
}
