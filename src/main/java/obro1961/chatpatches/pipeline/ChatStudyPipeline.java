package obro1961.chatpatches.pipeline;

import obro1961.chatpatches.chatlog.LogEntry;
import obro1961.chatpatches.translate.Glossary;
import obro1961.chatpatches.translate.ScriptDetector;
import obro1961.chatpatches.translate.TranslateConfig;
import obro1961.chatpatches.tts.ReadAloudConfig;
import obro1961.chatpatches.tts.SpeechPolicy;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 决策大脑 —— <b>把"翻译 / 朗读 / 落盘"三件事串起来</b>。
 *
 * <p><b>为什么单独抽出来做成泛型</b>：它本身不需要 Minecraft。
 * 消息的句柄用泛型 {@code M} 表示（生产环境是那条 `GuiMessage`，测试里随便什么对象），
 * 所有跟 MC 相关的动作都通过 {@link Sink} 回调出去。
 * 好处是<b>这套决策逻辑能独立编译、独立测试</b>，MC 那边只剩一层薄壳。
 *
 * <p>一条消息进来后的顺序（<b>朗读不走翻译的下游</b>）：
 * <pre>
 * 收到消息 ──┬─→ 立刻落盘
 *            ├─→ 立刻朗读原文（不等翻译，快）
 *            └─→ 异步翻译 → 译文回来后挂到那条消息上
 * </pre>
 */
public final class ChatStudyPipeline<M> {

    /** 把结果作用到真实世界（生产环境 = Minecraft；测试 = 记录调用）。 */
    public interface Sink<M> {
        /**
         * 把译文挂到这条消息上（生产环境：替换成"原文 + 换行 + 译文"的第二行）。
         *
         * @param refLine 原文那条记录落在文件的<b>第几行</b>（1 起）；{@code <= 0} = 不知道。
         *                生产环境会把它写进译文记录的 {@code ref} 字段 ——
         *                这样"哪条译文属于哪条消息"永远对得上，
         *                不依赖"译文是不是恰好落在下一行"（译文是异步回来的，会插队）。
         */
        void attachTranslation(M handle, String translation, int refLine);

        /**
         * 落一条记录到磁盘。
         *
         * @return 这条记录落在文件的<b>第几行</b>（1 起）；不知道就返回 {@code -1}。
         */
        int persist(LogEntry entry);

        /**
         * 朗读一条消息。
         *
         * @param speaker 说话的人，可能为 {@code null}（系统消息没有名字）。
         *                <b>必须显式传进来，不能省。</b>第一版只传了文本，于是"这句话是不是
         *                我自己刚发的"就只能靠"文本里含不含我发出去的内容"去猜 ——
         *                那是启发式：要设时间窗口、别人复读同一句会被误伤。说话人本来就在手上。
         * @param text    清洗好的朗读文本
         * @param voiceLang {@link Speech#voiceLang()} —— 用中文还是英文嗓音。
         *                <b>必须传下去</b>：不加这个参数时它被丢掉，下游
         *                {@code LocalTts} 只好把同一份判断重算一遍。
         */
        void speak(String speaker, String text, String voiceLang);

        /**
         * 说话人是不是本机玩家。
         *
         * <p>这条信息只有 MC 侧知道（要跟 {@code Minecraft.getInstance().player} 比），
         * 但"不读自己的话"是<b>朗读决策的一部分</b> —— 所以要由 Sink 提供、由
         * {@link #planSpeech} 使用，而不是让 {@code ChatStudyBridge.speak()} 在事后否决。
         * 事后否决 = 决策有两个出口，那正是这条规则以前被写两遍的原因。
         */
        boolean isSelf(String speaker);

        /** 往聊天栏贴一行提示（自检、报错用）。 */
        void notice(String text);
    }

    /** 翻译能力（生产环境用 {@code TranslationManager}，测试里塞假的）。 */
    public interface Translator {
        /** 入站翻译；失败返回 null。 */
        String inbound(String text);

        /** 出站翻译；失败返回 null。 */
        String outbound(String text, String targetLanguage);
    }

    private final Sink<M> sink;
    private final Translator translator;
    private final Executor executor;
    private final SpeechPolicy speech;

    private TranslateConfig translateCfg;
    private ReadAloudConfig speechCfg;

    /**
     * 消息组件 → 它的 {@code seq}（R16 点击后翻译要用：手动译文也得挂准 {@code refSeq}）。
     *
     * <p>用 {@link java.util.IdentityHashMap} 而不是 HashMap：{@link Component} 的
     * {@code equals} 是<b>结构相等</b>，同一句话被两个人说会判等 —— 那就挂到别人头上去了。
     * 这里要的是"同一条消息的那一个实例"。
     */
    private final java.util.Map<M, Integer> seqByHandle = new java.util.IdentityHashMap<>();

    /** 记下"这条消息的 seq"（自动/手动译文都据此落 {@code refSeq}）。 */
    /** 映射最多留这么多条；超了就丢一部分老映射（丢了只是 refSeq 退回"下一行"配对）。 */
    private static final int SEQ_MAP_MAX = 4096;

    /** 兜底索引：组件纯文本 → seq。用于"对象被替换过/剥过译文"后仍能找回 seq。 */
    private final java.util.Map<String, Integer> seqByText = new java.util.LinkedHashMap<>();

    public void registerHandle(M handle, int seq) {
        if (handle != null && seq > 0) {
            seqByHandle.put(handle, seq);
            String txt = handleText(handle);
            if (!txt.isEmpty()) {
                if (seqByText.size() > SEQ_MAP_MAX) {
                    seqByText.clear();
                }
                seqByText.put(txt, seq);
            }
            trimSeqMap();
        }
    }

    /**
     * 把映射裁剪到上限以内。
     *
     * <p>为什么要上限：键是 {@code Component}，而我们是<b>强引用</b>保存。
     * 聊天栏会裁旧消息，但映射不裁就等于"旧消息永远不释放" —— 一个慢泄漏。
     * 2026-10-21 那次 2.8 GB 内存/卡死排查里，先把这条兜住。
     */
    private void trimSeqMap() {
        if (seqByHandle.size() <= SEQ_MAP_MAX) {
            return;
        }
        int drop = seqByHandle.size() - SEQ_MAP_MAX / 2;
        java.util.Iterator<M> it = seqByHandle.keySet().iterator();
        while (drop-- > 0 && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
    /**
     * 取 handle 的纯文本。<b>不 import Component</b> —— 本类刻意不依赖 Minecraft
     * （离线自测要能单独编译 pipeline），所以对 Component 用反射调 getString()。
     */
    private static String handleText(Object handle) {
        if (handle instanceof CharSequence cs) {
            return cs.toString().trim();
        }
        try {
            Object s = handle.getClass().getMethod("getString").invoke(handle);
            if (s instanceof CharSequence cs2) {
                return cs2.toString().trim();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public int seqOf(M handle) {
        if (handle == null) {
            return -1;
        }
        Integer v = seqByHandle.get(handle);
        if (v != null) {
            return v;
        }
        // ★ 兜底：对象身份对不上（组件被复制/剥过译文）时按纯文本找。
        //   实测：单人里删自己刚发的消息，右键拿到的是 withoutTranslation 的副本，
        //   identity 查不到 → 原来会误判成"没有 seq、不写 delete 记号"。
        Integer byText = seqByText.get(handleText(handle));
        return byText == null ? -1 : byText;
    }

    public ChatStudyPipeline(Sink<M> sink, Translator translator, Executor executor) {
        this(sink, translator, executor, new SpeechPolicy());
    }

    public ChatStudyPipeline(Sink<M> sink, Translator translator, Executor executor, SpeechPolicy speech) {
        this.sink = sink;
        this.translator = translator;
        this.executor = executor;
        this.speech = speech;
    }

    public void setConfig(TranslateConfig t, ReadAloudConfig a) {
        this.translateCfg = t;
        this.speechCfg = a;
    }

    // ---- 入站 ----

    /**
     * <b>入站的一条消息 —— 源头能给的「结构」都带上。</b>
     *
     * <p><b>为什么要有它</b>：以前入口只收一个「渲染好的整行字符串」，说话人是谁、
     * 正文是哪段，全靠事后从字符串里<b>反推</b>。反推必然补不完 ——
     * 实测踩到：本服资源包把 {@code chat.type.text} 改写成了 {@code "%s%s"}，
     * 渲染出来就是 {@code Xiaoliyu_23333}，名字和内容之间<b>一个分隔符都没有</b>，
     * 想从这种串里把正文切出来是不可能的。
     *
     * <p>可这些事实在<b>渲染之前</b>一直是结构化的：说话人在
     * {@code MessageData.sender()} 里，正文在 {@code TranslatableContents} 的参数表里。
     * <b>渲染串只用于显示，不参与任何解析。</b>
     *
     * @param type     源头类型：chat / system / boundary / death / join
     * @param time     HH:mm:ss
     * @param speaker  结构化说话人（发送者资料）。拿不到才是 {@code null}
     * @param content  <b>结构化正文</b> —— 纯内容，绝不含名字/前缀。
     *                 非空时【绝不】再切字符串；{@code null} 表示源头也给不出，才落到唯一那处兜底
     * @param rendered 渲染好的整行 —— <b>只用于落盘和朗读</b>
     * @param compJson <b>那条消息的 Component JSON</b>（由 MC 侧序列化好传进来，本类不认识 Minecraft）。
     *                 落盘时一起存，灌回历史时靠它还原样式；
     *                 {@code null} = 没有（旧记录 / 序列化失败），灌回时回落到纯文本
     */
    public record Incoming(String type, String time, String speaker, String content,
                           String rendered, String compJson, String uuid, String source) {

        /** 兼容 5 参用法（不带 Component JSON）—— 老调用点和测试继续有效。 */
        public Incoming(String type, String time, String speaker, String content, String rendered) {
            this(type, time, speaker, content, rendered, null, null, LogEntry.SOURCE_SERVER);
        }

        /** 兼容 6 参用法（带 Component JSON、不带 UUID）—— M2b 之前的老调用点继续有效。 */
        public Incoming(String type, String time, String speaker, String content,
                        String rendered, String compJson) {
            this(type, time, speaker, content, rendered, compJson, null, LogEntry.SOURCE_SERVER);
        }

        /** 兼容 7 参用法（不带来源）—— 默认服务器来源。 */
        public Incoming(String type, String time, String speaker, String content,
                        String rendered, String compJson, String uuid) {
            this(type, time, speaker, content, rendered, compJson, uuid, LogEntry.SOURCE_SERVER);
        }

        public boolean isMod() {
            return LogEntry.SOURCE_MOD.equals(source);
        }
    }

    /**
     * 一条消息进来。
     *
     * @param handle 这条消息的句柄（译文回来时用它定位）
     * @param in     结构化输入，见 {@link Incoming}
     * @return 落盘的那条记录（方便调用方/测试断言）
     */
    public LogEntry onIncoming(M handle, Incoming in) {
        if (in != null && in.isMod()) {
            return null; // 本模组插入的消息：不记录、不翻译、不朗读
        }
        final String type = in.type();
        final String name = in.speaker();
        final String text = in.rendered();
        // ★ 本模组自己发的状态提示不进流水线：不记录、不翻译、不朗读
        if (in != null && in.rendered() != null && in.rendered().contains("[ChatPatch]")) {
            return null;
        }

        LogEntry entry = new LogEntry();
        entry.type = type;
        entry.source = in.source();
        entry.time = in.time();
        entry.name = name;
        entry.text = text;
        // ★ 结构一起存：灌回历史时靠它还原颜色/悬浮/点击。只有 text 的话，灌回来是"素的"。
        entry.comp = in.compJson();
        // ★ M2b：把玩家 UUID 一起落盘（取不到就留 null，不写假值）。
        //   为什么现在就写（而不是"以后需要再说"）：名字可以改、可以重名，
        //   想按玩家回溯历史时，只有 UUID 是稳定身份。数据源头本来就有（GameProfile），
        //   不写才是浪费 —— 以后补就只能从旧数据里永远缺失。
        entry.uuid = (in.uuid() == null || in.uuid().isBlank()) ? null : in.uuid();
        // ★ 记下这条落在第几行 —— 译文回来时要把它写进译文记录的 ref，
        //   这样"哪条译文属于哪条消息"永远对得上（译文是异步回来的，会插队）。
        final int refLine = sink.persist(entry);
        // ★ R16：把 handle → seq 记下来，右键「翻译这条」时才有 refSeq 可挂
        registerHandle(handle, refLine);

        String body = stripTimestamp(text);        // 去掉时间戳的纯文本
        String plain = plain(body);
        if (plain.isBlank()) {
            return entry;
        }

        // ★ 已经挂过译文的行不再处理（译文是同一消息的第二行：原文\n↳ 译文）。
        if (plain.indexOf(TRANSLATION_MARK) >= 0) {
            return entry;
        }

        // ★ 入口解析一次 —— 之后读/翻/日志/isSelf 全部引用这一份，谁都不再自己补一手。
        //   以前 speaker 在朗读那算一次、翻译那又算一次，两次算法还不一样（一个有兜底一个没有），
        //   结果名字跟着进了翻译器。消息的形状只该被解析一次。
        //
        //   ★ 底层重设计：{@code in.content()} 是源头给的结构化正文。有它就绝不去切字符串 ——
        //   资源包、客户端语言、config.name 怎么变都影响不到它。
        Parsed parsed = parse(name, in.content(), plain);

        // ① 朗读 —— 【一次算完】：读不读、读什么、什么嗓音
        //    传 parsed.speaker() 而不是原始的 name：服务器消息常常拿不到发送者资料，
        //    说话人是靠兜底从正文抠出来的。用解析后的那份，"不读自己的话"才对那类消息也生效。
        Speech plan = planSpeech(type, parsed.speaker(), plain);
        if (plan != null && speech.allow(plan.text())) {
            sink.speak(parsed.speaker(), plan.text(), plan.voiceLang());
        }

        // ② 翻译 —— 只翻玩家聊天，别的（死亡播报/进服/系统提示）不碰
        //
        //   ★ 自己说的话默认不做入站翻译（{@code translateOwnMessages=false}）。
        //
        //   为什么：你说中文 → 出站翻成英文发出去 → 服务器把**英文**原样回显给你。
        //   那条回显如果走入站翻译，就会被**翻回中文** —— 白跑一趟：屏上又变回中文，
        //   而你原本打的中文从没出现过（用户原话：「相当于我原文消失了」）。
        //
        //   自己打的字不需要别人替你翻；原文 + ↳ 英文 由出站那条路径在本地显示
        //   （见 ChatStudyBridge.sendTranslated）。别人说的话照翻，不受影响。
        //   ★ 现在这个行为可配置（用户要求「加个按钮到配置」）：
        //     1-translate.json 的 translateOwnMessages = true 就照翻自己的话。
        //
        //   ★ R16（点击后翻译）：开了 translateOnClick 就【不自动翻】——
        //   右键菜单「翻译这条」才翻，省 token、刷屏时不打一串 API。
        boolean self = sink.isSelf(parsed.speaker());
        boolean skipSelf = self && (translateCfg == null || !translateCfg.translateOwnMessages);
        if (!autoTranslateDisabled()
                && shouldTranslate(type, plain) && !skipSelf) {
            final String content = parsed.content();
            if (content.isBlank()) {
                return entry;
            }
            executor.execute(() -> {
                try {
                    String tr = translator.inbound(content);
                    if (tr != null && !tr.isBlank() && !tr.equals(content)) {
                        // 译文只挂翻译结果，不带名字：名字在上面的原文行里已经有了，
                        // 再重复一遍是噪音。翻译器收到的本来也只有内容。
                        sink.attachTranslation(handle, tr, refLine);
                        speakTranslation(parsed.speaker(), tr);
                    }
                } catch (Exception e) {
                    sink.notice("§c[chatpatch] 翻译失败: §7" + e);
                }
            });
        }
        return entry;
    }

    /**
     * 兼容旧签名的入口 —— <b>源头给不出结构化正文</b>时走它。
     *
     * <p>和结构化入口走的是<b>同一条</b>解析路径（{@code content == null} → 落到唯一那处兜底），
     * 所以老调用点和老测试继续有效，行为不变。生产环境用结构化入口。
     */
    public LogEntry onIncoming(M handle, String type, String time, String name, String text) {
        return onIncoming(handle, new Incoming(type, time, name, null, text));
    }

    /**
     * 把一行拆成「名字前面的部分」和「要说的话」。
     *
     * <p><b>翻译只送后半段</b> —— 名字和名字上的颜色/前缀统统不进去。
     * 拼接任务本身（比如 MIA 的 {@code [emojy:gifs/...] Noobinator0_0: wb}）
     * 也在这里被切开，所以表情占位符不会污染译文。
     *
     * @return {@code [前缀, 内容]}；找不到名字时前缀为空、内容就是整行
     */
    public static String[] splitNameAndContent(String body, String name) {
        if (body == null) {
            return new String[]{"", ""};
        }
        if (name == null || name.isBlank()) {
            return new String[]{"", body.trim()};
        }
        int i = body.indexOf(name);
        if (i < 0) {
            return new String[]{"", body.trim()};
        }
        int c = i + name.length();
        // 名字后面通常紧跟冒号（可能带空格）
        while (c < body.length()
                && (body.charAt(c) == ':' || body.charAt(c) == '：' || body.charAt(c) == ' ')) {
            c++;
        }
        return new String[]{
                body.substring(0, c).trim(),
                body.substring(Math.min(c, body.length())).trim()
        };
    }

    /**
     * <b>名字之前允许出现的「噪声」</b> —— 全是"不是正文内容"的东西：
     *
     * <ul>
     *   <li>{@code §} 颜色码</li>
     *   <li><b>私用区图标 {@code U+E000–U+F8FF}</b> —— 资源包的表情/徽记。
     *       MIA 每条消息的名字前面都挂着三个（实测码点 {@code U+E002 U+E00E U+E113 U+E103}）。
     *       <b>它们长得像空格，肉眼看不出来</b>，但正则里不是空白 —— 这里栽过一次</li>
     *   <li>{@code [...]} 占位符，比如 {@code [emojy:gifs/elmoburn@gui]}</li>
     *   <li>空白</li>
     * </ul>
     */
    private static final java.util.regex.Pattern SPEAKER_NOISE = java.util.regex.Pattern.compile(
            "^(?:§[0-9a-fk-orA-FK-OR]|[\\uE000-\\uF8FF]|\\[[^\\]]*\\]|\\s)+");

    /** 削掉名字前面的噪声（见 {@link #SPEAKER_NOISE}）。public 是为了能单独测。 */
    public static String stripSpeakerNoise(String s) {
        return s == null ? "" : SPEAKER_NOISE.matcher(s).replaceFirst("");
    }

    /**
     * 「名字: 正文」—— 剥掉噪声之后，名字必须在<b>开头</b>。
     *
     * <p><b>为什么必须锚在开头</b>：不锚的话 {@code Welcome to the server: have fun}
     * 会把 {@code server} 当成玩家名 —— 这条系统播报就会被判成"玩家聊天"、
     * 还会把 {@code have fun} 送去翻译。
     *
     * <p><b>为什么噪声要先削掉、而不是靠"任意位置"匹配绕过去</b>：
     * 一开始我把这条写成"任意位置"（宽松），它能歪打正着地跳过私用区图标；
     * 后来收严成"锚定开头 + 只容忍空白/§/方括号"，就把 MIA 那种带图标的消息全判漏了 ——
     * <b>表现为"朋友发的 wb 不翻译"</b>。正确做法是：<b>先显式削掉噪声，再锚定</b>。
     *
     * <p><b>★ 名字字符集必须够宽</b>（2026-10-12 修）：
     * MIA 给赞助玩家的名字是<b>装饰过的</b> —— 全角字母、爱心、emoji 都有，实测这条：
     * <pre>{@code Ｌｙｓａｗｅｎ❤: wb}</pre>
     * 而原来的字符集是 {@code [\w\u4e00-\u9fff]} —— 只认 ASCII 字母数字 + 中日韩汉字 ✗。
     * 全角字母 {@code Ｌｙｓ…} 是 Unicode 字母、但<b>不是 {@code \w}</b>；
     * {@code ❤} 更是符号。于是<b>整条判不上 → 判成系统消息 → 白名单里没有 wb → 不翻译</b>。
     * 用户报的正是这个：「Lysawen 的 wb 那句话没有翻译」，
     * 而普通 ASCII 名字的朋友发同样的 wb 是好的 —— 差别只在名字。
     *
     * <p>现在按"玩家名可能长什么样"来收：<b>Unicode 字母/数字/下划线 + 符号类（emoji、♥）
     * + 私用区（MIA 的图标）</b>，并允许 MIA 给退坑玩家挂的 {@code (archive)} 后缀。
     *
     * <p>安全性没变：名字里<b>不含空格</b>、且必须<b>紧跟冒号</b>，
     * 所以 {@code Welcome to the server: have fun} 依然判不上（{@code Welcome} 后面是空格不是冒号）✓
     */
    public static final java.util.regex.Pattern SPEAKER_PREFIX = java.util.regex.Pattern.compile(
            "^([\\p{L}\\p{N}_\\p{So}\\uE000-\\uF8FF]{1,24})"
                    + "(?:\\s*\\(archive\\))?"
                    + "\\s*[:：]\\s*\\S");

    /**
     * 这条消息是谁说的。
     *
     * <p>优先用消息自带的发送者资料；没有（很多服务器消息经过别的 mod 改写后就丢了）
     * 就从正文的 {@code 名字: } 前缀里抠 —— 用的是 {@link #SPEAKER_PREFIX}，
     * 和 {@link #classifyMessage} 同一条。
     *
     * <p>需要在朗读前就知道说话人，是为了让上层能判断"这是不是我自己发的" ——
     * 服务器会把自己的消息原样广播回来，那条和别人的消息长得一模一样。
     */
    /**
     * 「名字: 正文」里<b>带空格的名字</b>也要能抠出来。
     *
     * <p>MIA 允许名字带空格，实测：{@code 🍁𝓐𝔂𝓪 𝓦𝓮𝓷𝓦𝓮𝓷🍁: hi tam}、{@code aya wenwen: hi}。
     * {@link #SPEAKER_PREFIX} 要求名字里不含空格（那是为了挡住
     * {@code Welcome to the server: have fun} 这类系统播报），所以这里单独给一条宽松规则。
     *
     * <p><b>什么时候可以用它</b>：只有<b>已经确定这条是玩家聊天</b>时才用
     * （比如组件里发现了 {@code /mia profile} 那个结构信号）。
     * 拿它去盲判会把系统播报也判成聊天 —— 这条只做"已知是聊天，把名字抠出来"。
     *
     * <p>判据：<b>第一个「冒号 + 空格」之前</b>的部分就是名字（限 2-32 字符，避免误吃长句）。
     */
    public static String speakerAllowSpaces(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        String s = stripSpeakerNoise(plain);
        int i = -1;
        // ★ 判据是【第一个冒号（半角或全角）】—— 不再要求冒号后面必须跟空白。
        //   一开始要求了，结果 `aya wenwen：hi`（全角冒号、后面没空格）抠不出来 ✗，
        //   是测试把这条逼出来的。反正这条规则只在"已经确定是玩家聊天"时才会被调用，
        //   宽松一点没有风险。
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == ':' || c == '：') {
                i = k;
                break;
            }
        }
        if (i < 2 || i > 32) {
            return null;
        }
        String name = s.substring(0, i).trim();
        return name.isEmpty() ? null : name;
    }

    /**
     * 这个 kind 是不是"玩家聊天"。
     *
     * <p>抽出来是因为：判成玩家聊天之后，抠名字要用<b>另一条</b>宽松规则
     * （{@link #speakerAllowSpaces}）—— 两处都得同意"这就是聊天"。
     */
    public static boolean isChat(String kind) {
        return LogEntry.TYPE_CHAT.equals(kind);
    }

    /** 玩家聊天点击命令的前缀特征（MIA 专用；换服务器只改这里）。 */
    private static final String[] PLAYER_CHAT_CLICK_HINTS = {"/mia profile"};

    /**
     * <b>系统播报句式</b>（数据驱动 —— 从真实记录里统计出来的）。
     *
     * <p>★ <b>为什么要这张表</b>（用户的思路，比我原来的写法对）：
     * 我原来想"列玩家名字的特征" ✗ —— 但 MIA 允许玩家随便装饰名字，
     * <b>那是无限的</b>（连着三轮：{@code Ｌｙｓａｗｅｎ❤} → {@code 🍁𝓐𝔂𝓪 𝓦𝓮𝓷𝓦𝓮𝓷🍁} → {@code aya wenwen}）。
     * 反过来<b>列系统播报的句式</b>才是<b>有限</b>的 ✓ —— 服务器就那么几种播报。
     *
     * <p>数据来源：把本机 7 个聊天记录里的 <b>819 条 {@code type=system}</b> 全捞出来聚类，
     * 得到的就是下面这些句式（括号里是实测条数，2026-10-14）：
     * <pre>
     *   + ┃ 名字 / - ┃ 名字（含 (archive)）                   265  加入 / 退出
     *   X died in … / was slain by … / fell from a high place …
     *   was doomed to fall … / froze to death / suffocated …    ~380  死亡（MC 标准句式）
     *   You hear whispers that X has obtained a Grade N relic.    16  遗物
     *   X has made the advancement [ … ]                          22  成就
     *   You have changed to channel global.                        8  频道
     *   Welcome X to Mine in Abyss                                 9  欢迎
     *   Sorry, but you can't … / You do not have permission …    ~15  提示
     *   [Minecraft服务器] / [新的世界]                             32  客户端前缀
     * </pre>
     *
     * <p>剩余那 74 条（去重后）「名字: 内容」形状的，<b>人工核对过全是玩家在说话</b> ✓
     * （含 Discord 桥接过来的）—— 所以 {@link #classifyMessage} 的规则是：
     * <b>先排除本表，剩下的像聊天就当聊天</b> ✓
     *
     * <p>新增播报时的做法：跑一遍记录聚类，把新句式加进来 ✓（别凭印象加 ✗）
     */
    private static final String[] SYSTEM_ANNOUNCEMENT_PATTERNS = {
            // ① 加入 / 退出：`+ ┃ 名字` / `- ┃ 名字`
            "^[+\\-]\\s*┃",
            // ② 死亡（MC 标准死亡消息 —— 服务器会套 MIA 的地名，所以句式本身不变）
            " died ", " died", " was slain by ", " was shot by ", " was killed by ",
            " was doomed to fall", " fell from a high place", " fell out of the water",
            " fell into the abyss", " fell to their death", " froze to death",
            " suffocated in a wall", " was burnt to a crisp", " bled to death",
            " was smashed by ", " died because of ", " was blown up by ",
            " was impaled by ", " starved to death", " withered away",
            " hit the ground too hard", " went up in flames", " burned to death",
            " tried to swim in lava", " was struck by lightning", " drowned",
            " experienced kinetic energy", " was poked to death",
            // ③ 遗物 / 成就 / 进度
            " hears whispers", " has made the advancement", " has completed the challenge",
            " has reached the goal", " has obtained a Grade",
            // ④ 频道 / 提示 / 传送
            " has changed to channel", " Sorry, but you can't", " You do not have permission",
            " possible positions cleared", " Teleportation complete", " Respawning at bonfire",
            " will be teleported in", " has been claimed by",
            // ⑤ 欢迎 / 服务器播报（"^Welcome" 走下面的正则 —— 别写 " Welcome " ✗ 它在行首）
            " to Mine in Abyss",
    };

    /**
     * <b>需要锚定的系统句式</b>（必须用正则 ✗ 不能用 {@code contains}）。
     *
     * <p>★ 第一版把 {@code "^[+\-] ┃"} 放进了上面那个字面表 ✗ ——
     * {@code contains} 是字面匹配、不认正则 ⇒ <b>永远匹配不上</b> ✗✓
     * 550 条夹具当场抓到：265 条「+ ┃ 名字」全判成了玩家聊天 ✗
     */
    private static final java.util.regex.Pattern[] SYSTEM_ANNOUNCEMENT_REGEX = {
            // 加入 / 退出：`+ ┃ 名字` / `- ┃ 名字`
            java.util.regex.Pattern.compile("^[+\\-]\\s*┃"),
            // 欢迎入服：`Welcome X to Mine in Abyss`（行首，避免误伤 "X: Welcome to my base"）
            java.util.regex.Pattern.compile("^Welcome\\b"),
            // 纯 `[前缀]` 整条（客户端加的频道名：`[Minecraft服务器]`、`[新的世界]`）
            java.util.regex.Pattern.compile("^\\[[^\\[\\]]{1,20}\\]$"),
    };

    /**
     * <b>这条 system 消息是不是"服务器播报"</b>（→ 不该翻译）。
     *
     * <p>判据全部来自 {@link #SYSTEM_ANNOUNCEMENT_PATTERNS}（数据统计得出 ✓）。
     * <b>命中就保持 system</b>；不命中才交给后面的"像聊天就当聊天" ✓
     *
     * <p>另外还兜一条纯形状：整条就是 {@code [xxx]} 这种<b>客户端频道前缀</b> ✓
     * （{@code [Minecraft服务器]}、{@code [新的世界]} —— 这种没有内容，翻不出东西）
     */
    public static boolean isSystemAnnouncement(String text) {
        if (text == null || text.isBlank()) {
            return true;   // 空的按播报处理，省得白跑一趟翻译
        }
        String t = text.trim();
        for (String p : SYSTEM_ANNOUNCEMENT_PATTERNS) {
            if (t.contains(p)) {
                return true;
            }
        }
        // ★ 需要锚定的句式单独走正则 ✗ —— 第一版把 "^[+\-] ┃" 塞进上面那个 contains()，
        //   结果【永远匹配不上】（contains 是字面匹配，不认正则）✗
        //   夹具（550 条真实消息）当场抓到，样本里 265 条「+ ┃ 名字」全判错 ✓
        for (java.util.regex.Pattern p : SYSTEM_ANNOUNCEMENT_REGEX) {
            if (p.matcher(t).find()) {
                return true;
            }
        }
        // ★★ 去噪后再试一遍锚定句式 ✗
        //
        //   为什么：MIA 每条消息前面都挂着资源包图标（`[emojy:gifs/…]`），
        //   而锚定句式（`^Welcome`、`^+ ┃`）要求它在【行首】✗ —— 被图标挡掉就漏了。
        //   实测抓到的就是这条：`ICONS + "Welcome to the server: have fun"` ✗
        //   被判成了玩家聊天（§34.5 那个老陷阱，回归测试又把它翻出来了 ✓）
        //
        //   注意：**只对去噪后的串试锚定句式** ✓ ——
        //   不能拿去噪后的串去试 `^[xxx]$`（那条要求原始就是纯方括号，去噪会变空 ✗）
        String stripped = stripSpeakerNoise(t);
        if (!stripped.equals(t) && !stripped.isBlank()) {
            for (java.util.regex.Pattern p : SYSTEM_ANNOUNCEMENT_REGEX) {
                if (p.matcher(stripped.trim()).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * <b>玩家发言的形状</b>：冒号前是名字、冒号后有内容。
     *
     * <p>★ 宽松版（允许名字里带<b>逗号、空格、emoji、全角字母</b>）——
     * 因为 {@link #classifyMessage} 会先过 {@link #isSystemAnnouncement} ✓，
     * 不会再把 {@code Welcome to the server: have fun} 那种当成玩家名 ✓
     * （那条由播报句式挡住 ✓）。实测 74 条这种形状的，全是玩家在说话 ✓
     */
    private static final java.util.regex.Pattern SPEAKER_RELAXED =
            java.util.regex.Pattern.compile("^[^:：\\n]{1,45}[:：]\\s*\\S");

    /**
     * <b>结构信号</b>：这条消息的组件里，有没有"玩家聊天"的特征。
     *
     * <p>MIA 的玩家聊天模板会在名字上挂 {@code click_event}：
     * <pre>{@code {"click_event":{"command":"/mia profile pargalgh","action":"run_command"}}}</pre>
     * 而系统播报（死亡、进服退服、遗物提示）没有。
     *
     * <p><b>为什么非要用它</b>：靠文本猜名字连着踩了三次坑 ——
     * {@code Ｌｙｓａｗｅｎ❤}（全角+爱心）、{@code 🍁𝓐𝔂𝓪 𝓦𝓮𝓷𝓦𝓮𝓷🍁}（数学粗斜体+emoji+空格）、
     * {@code aya wenwen}（空格）。每加宽一次字符集就来一个新的 ✗。
     * 名字长什么样是玩家自由，<b>按文本堵是堵不完的</b>；结构特征才稳定。
     *
     * <p>★ <b>判据是"序列化后的 JSON 里有没有这个命令"，不是去翻 Component 对象</b> ——
     * 这里踩过一次：第一版写的是 {@code c.getStyle().getClickEvent()} 再
     * {@code String.valueOf(click).contains("/mia profile")}，
     * 结果明明组件里有这个点击事件（JSON 里就写着），线上却<b>一条都没匹配上</b> ✗。
     * 那个 click_event 挂在很深的嵌套节点上，{@code ClickEvent} 的 toString 也不保证带命令。
     * 直接查我们本来就要序列化的那份 JSON —— <b>数据就在里面，何必再去猜对象形状</b>。
     *
     * @param compJson {@code componentJson(...)} 的产物；null/空安全
     */
    public static boolean looksLikePlayerChat(String compJson) {
        if (compJson == null || compJson.isEmpty()) {
            return false;
        }
        for (String hint : PLAYER_CHAT_CLICK_HINTS) {
            if (compJson.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    public static String resolveSpeaker(String name, String plain) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        // ★ 这里【只】用严格规则（名字不含空格）。
        //   宽松规则 speakerAllowSpaces 留给"已经确定是玩家聊天"的调用方 ——
        //   挂成兜底的话，系统播报也会被抠出一个假名字：
        //   Welcome to the server: have fun → 抠出「Welcome to the server」✗
        //   （这条是单元测试拦下来的，不是我先想到的）
        String body = stripSpeakerNoise(plain);
        // ★ 有的服务端渲染成「<名字> 内容」——老规则只认「名字: 内容」，这里补一条。
        java.util.regex.Matcher angle = java.util.regex.Pattern
                .compile("^<([^<>\\s]{1,40})>\\s*").matcher(body);
        if (angle.find()) {
            return angle.group(1);
        }
        java.util.regex.Matcher m = SPEAKER_PREFIX.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** 译文行的标记 —— 用来识别"这条已经挂过译文了"。 */
    public static final String TRANSLATION_MARK = "↳";

    /**
     * 去掉 Chat Patches 加在行首的时间戳。
     *
     * <p>它的时间戳格式是<b>可配置的</b>，所以这里按常见形态兜：{@code [HH:mm:ss]}、
     * {@code [HH:mm]}、{@code (HH:mm:ss)}，以及最前面可能带的颜色码。
     */
    public static String stripTimestamp(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceFirst("^(§[0-9a-fk-orA-FK-OR])*\\s*", "");   // 时间戳前面的颜色码
        s = s.replaceFirst("^\\s*[\\[\\(]\\s*\\d{1,2}:\\d{2}(?::\\d{2})?\\s*[\\]\\)]\\s*", "");
        s = s.replaceFirst("^(§[0-9a-fk-orA-FK-OR])*\\s*", "");             // 时间戳后面那个颜色码（§7[..] §f正文）
        return s;
    }

    /**
     * 朗读用的文本：<b>只有「名字 + 说的话」</b>。
     *
     * <p>三步：
     * <ol>
     *   <li>{@link #plain(String)} 剥掉所有 § 颜色码和私用区图标 —— 语音引擎读到 §f 会念出奇怪的东西</li>
     *   <li>补上名字（正文里通常已经带了，没带才补）</li>
     *   <li>可选：按术语表展开缩写（{@code wb} → "欢迎回来"），由
     *       {@link obro1961.chatpatches.tts.ReadAloudConfig#expandAbbreviations} 控制，<b>默认关</b></li>
     * </ol>
     */
    /** 实例版：用当前配置里的朗读替换表。 */
    public String speakable(String name, String bodyPlain) {
        return speakable(name, bodyPlain,
                speechCfg == null ? null : speechCfg.replacements);
    }

    /** 静态版：能单独测。 */
    public static String speakable(String name, String bodyPlain, java.util.Map<String, String> replacements) {
        String body = speechNormalize(applyReplacements(plain(bodyPlain), replacements));
        String who = speechNormalize(name);
        if (!who.isBlank() && !body.startsWith(who)) {
            body = who + ": " + body;
        }
        return body.trim();
    }

    /**
     * 朗读前的符号归一化 —— <b>下划线不能念</b>。
     *
     * <p>玩家名里到处都是下划线：{@code Xiaoliyu_}、{@code duck_master42938}、
     * {@code Noobinator0_0}。语音引擎会老老实实念成 "underscore"。
     * 下划线在游戏名里本来就当空格用（{@code duck_master} = "duck master"），
     * 所以这里换成空格，顺手把连续空白压成一个。
     */
    public static String speechNormalize(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s
                // 资源包表情占位符（MIA 的 "[emojy:gifs/elmoburn@gui]"）不该念出来。
                // 只削"带冒号且没空格"的那种，免得误伤 "[Voluntary Exile]" 这类成就名。
                .replaceAll("\\[[^\\]\\s]*:[^\\]\\s]*\\]", " ")
                // ★ 顺序要紧：Discord 的 <a?:name:id> 必须排在短代码规则【前面】。
                //
                // 踩过这个坑：<:cat:123456789> 里的 ":cat:" 先被短代码规则吃掉，
                // 剩下 "< 123456789>"，尖括号再被后面那刀削掉 —— 最后念出一串阿拉伯数字。
                //
                // Discord 桥接过来的表情/提及用的是它自己的语法，直接念就是一堆数字：
                //   <:cat:123456789>  自定义表情
                //   <a:dance:987654321>  动态表情
                //   <@123456789> / <@!123456789>  用户提及
                //   <#987654321>  频道提及
                .replaceAll("<a?:[A-Za-z0-9_]+:\\d+>", " ")
                .replaceAll("<[@#][!&]?\\d+>", " ")
                // 链接不念（Discord 图片链接能有一百多个字符）
                .replaceAll("https?://\\S+", " ")
                // ★ 下面几条是补的：光有上面那条挡不住"协议头已经没了 / 被截断"的链接。
                //
                //   实测（用户反馈「现在朗读会读链接」）念出来的是这么一串：
                //     now image picker lib temp 17cea015-e201-4105-97cf-6a0e50c05127.jpg?ex
                //     6ace3ad9 is 6acce959 hm a87c4458d3...
                //   也就是 https:// 那段已经被削掉了，剩下的 uuid + .jpg?ex= + 几十位哈希
                //   【不像链接】，于是被当普通文本一个字母一个字母念出来 ✗
                //
                //   所以按"链接会留下的形状"补刀：www 网址、裸域名、图片文件名、uuid、长哈希。
                //   共同特征：念出来对人类没有任何信息量。
                .replaceAll("www\\.[\\w.-]+\\S*", " ")
                .replaceAll("\\b[\\w-]+(?:\\.(?:com|net|org|cn|io|me|tv|xyz|gg|dev|app"
                        + "|jpg|jpeg|png|gif|webp|mp4|mp3|ogg))\\b\\S*", " ")
                // uuid（图片/资源的临时名）
                .replaceAll("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                        + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b", " ")
                // 长十六进制串（哈希 / 校验尾巴）
                .replaceAll("\\b[0-9a-fA-F]{16,}\\b", " ")
                // emoji 短代码（":blackw:"）也不能念 —— 它没有方括号，上面那条抓不到，
                // 会被当普通文本念成 "blackw"。
                // 要求冒号里至少有一个字母开头，否则会把时间戳 ":42:" 也吃掉。
                .replaceAll(":[a-zA-Z][a-zA-Z0-9_+\\-]{1,31}:", " ")
                // 下划线在游戏名里本来就当空格用
                .replace('_', ' ')
                // ★ 破坏性处理：只留"能念出来的东西"—— 字母 / 数字 / 基本标点（半角 + 全角）。
                //
                // 朗读是一次性的，结果不回写任何地方（不落盘、不显示），所以可以随便削。
                // 这一刀下去：┃ | ~ * ^ + = # @ $ % & 之类的符号全变空格 ——
                // 它们要么念不出来，要么会被语音引擎念成奇怪的东西。
                // 方括号也削掉：念 "[Voluntary Exile]" 时不需要那对括号。
                // \p{L} 覆盖了汉字/假名/韩文/西里尔，不用逐个列。
                // 全角标点必须一起留着 —— 只留半角的话中文句子会被削成"你好 世界"。
                .replaceAll("[^\\p{L}\\p{N} .,!?;:'\"()\\-，。！？；：、（）《》「」【】…—·]", " ")
                // 标点前面的空格收掉（下划线常常紧挨着冒号："Xiaoliyu_: hi" → "Xiaoliyu : hi"）
                .replaceAll("\\s+([:：,.!?;、，。！？；'\")])", "$1")
                // 连续空白压成一个
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    /**
     * 按替换表把缩写换成"能念出来的词"（{@code wb} → {@code welcome back}）。
     *
     * <p>为什么需要：语音引擎对 {@code wb} 会念成 "double-u bee"，对 {@code o/} 更是乱读。
     * 换成真词，英文嗓音念出来才像人话。
     *
     * <p>匹配用词边界 + 不区分大小写：{@code wb} 会换，{@code wbwb} 里的不会
     * （它两边都是字母，不构成独立单词）。
     */
    public static String applyReplacements(String text, java.util.Map<String, String> replacements) {
        // ★ 注意：这里【不能】因为 replacements 为空就早退 ——
        //   底表是术语表的展开表，它跟用户配不配 replacements 无关。
        //   早退的话，用户把 replacements 清空 = 连内置的 lmk / nvm 展开也一起没了。
        if (text == null || text.isBlank()) {
            return text;
        }
        // ★ 底表 = 术语表的「英英展开表」。
        //   替换表只有二十来条，而展开表有七十多条（lmk / nvm / tbh …）——
        //   用户的原话是「跟翻译一样展开缩写」，那就别让朗读只认一小撮。
        //   用户自己配的 replacements 优先覆盖底表：一份内置、一份可改，不是两套机制。
        java.util.Map<String, String> table = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> e
                : obro1961.chatpatches.translate.Glossary.expandTerms().entrySet()) {
            String v = e.getValue();
            if (v == null || v.isBlank()) {
                continue;
            }
            // 多说法只取第一个 —— 念出来不能是 "good game / congrats"
            int slash = v.indexOf('/');
            table.put(e.getKey(), (slash < 0 ? v : v.substring(0, slash)).trim());
        }
        if (replacements != null) {
            table.putAll(replacements);
        }
        if (table.isEmpty()) {
            return text;
        }
        String out = text;
        for (java.util.Map.Entry<String, String> e : table.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || k.isBlank() || v == null) {
                continue;
            }
            try {
                out = out.replaceAll("(?i)" + wordPattern(k),
                        java.util.regex.Matcher.quoteReplacement(v));
            } catch (Exception ignored) {
                // 单条规则出问题不该拖垮整句
            }
        }
        return out;
    }

    /**
     * 造一条"整个单词"的匹配规则。
     *
     * <p><b>纯字母/数字的键：每个字符允许重复</b> ——
     * 真人打缩写爱拖长音：{@code tyyyyy} / {@code gggg} / {@code lmaooo}。
     * 原来要求完全相等，于是 {@code tyyyyy} 匹配不上 {@code ty}，
     * 语音引擎把这串字母当生词念了出来（用户实测：「刚刚 tyyyy 直接读了」）。
     * 现在 {@code ty} 的规则是 {@code t{1,}y{1,}} → {@code tyyyyy} → {@code thank you} ✓，
     * 而 {@code wbwb} 两边都是字母、不构成独立单词，照样不换 ✓。
     *
     * <p><b>含符号的键（{@code o/}、{@code \o}）原样精确匹配</b> ——
     * 符号没有"拖长音"，硬套重复只会误伤。
     */
    private static String wordPattern(String key) {
        boolean plain = true;
        for (int i = 0; i < key.length(); i++) {
            if (!Character.isLetterOrDigit(key.charAt(i))) {
                plain = false;
                break;
            }
        }
        StringBuilder sb = new StringBuilder("(?<![\\p{L}\\p{N}])");
        if (plain) {
            for (int i = 0; i < key.length(); i++) {
                sb.append(java.util.regex.Pattern.quote(String.valueOf(key.charAt(i)))).append("{1,}");
            }
        } else {
            sb.append(java.util.regex.Pattern.quote(key));
        }
        sb.append("(?![\\p{L}\\p{N}])");
        return sb.toString();
    }

    /**
     * 该不该翻。
     *
     * <p><b>玩家聊天：照翻。</b>
     * <p><b>系统消息：只有命中「关键句白名单」（{@code translate.json} 的 {@code systemKeywords}）才翻。</b>
     *
     * <p><b>默认白名单只有「服务器要关了」这一类</b>（{@code server will restart} /
     * {@code shutting down in} …）—— 因为不知道会掉线才是真损失。
     * <b>遗物提示 / 死亡播报 / 进服退服 / 传送预警 / 成就，按需求都不翻。</b>
     *
     * <p>⚠️ 这段注释以前写的是"MIA 的遗物提示 …这类必须看懂的要翻"—— <b>和实际代码相反</b> ✗。
     * 用户看着 {@code You hear whispers that X has obtained a Grade III relic.} 没译文来问，
     * 一查才发现是注释停留在老版本设计上。**注释和代码矛盾，比没有注释更坏。**
     *
     * <p>想让某类系统消息也翻：往 {@code systemKeywords} 里加关键词即可，不用改代码。
     *
     * @param type chat / system / death / join / boundary …
     */
    /** 为什么没翻 —— 只用于日志/自测，不改变任何判定。 */
    public enum Skip {
        DISABLED("翻译总开关关着"),
        AUTO_OFF("自动翻译关了（R16 点击后翻译）"),
        EMPTY("空文本"),
        NO_LETTERS("纯数字/符号/表情"),
        NOT_CHAT("类型不对（不是玩家聊天）"),
        TARGET_LANG("已经是目标语言"),
        SELF("自己发的"),
        NOT_SOURCE_CHARS("不含出站源语言的字"),
        GLOSSARY_KEEP("术语表标记为不翻译");

        public final String label;

        Skip(String label) {
            this.label = label;
        }
    }

    /** 入站：为什么这条不翻（{@code null} = 要翻）。纯函数，便于自测。 */
    public static Skip skipReasonFor(String type, String plainText, TranslateConfig c) {
        if (c == null || !c.enabled) {
            return Skip.DISABLED;
        }
        if (!c.autoEnabled) {
            return Skip.AUTO_OFF;
        }
        if (plainText == null || plainText.isBlank()) {
            return Skip.EMPTY;
        }
        if (!hasLetters(plainText)) {
            return Skip.NO_LETTERS;
        }
        if (!LogEntry.TYPE_CHAT.equals(type) && !c.translateSystemMessages && !matchesSystemKeyword(plainText, c)) {
            return Skip.NOT_CHAT;
        }
        if (ScriptDetector.isTargetLanguage(plainText, c.targetLanguage)) {
            return Skip.TARGET_LANG;
        }
        return null;
    }

    /** 出站：为什么这句不用翻（{@code null} = 要翻）。纯函数，便于自测。 */
    public static Skip outgoingSkipReason(String text, String sourceLanguage) {
        if (text == null || text.isBlank()) {
            return Skip.EMPTY;
        }
        if (!ScriptDetector.hasCharsOf(text, sourceLanguage == null ? "auto" : sourceLanguage)) {
            return Skip.NOT_SOURCE_CHARS;
        }
        return null;
    }

    /** 只有 debug 打开时才打，避免正常玩刷屏。 */
    private void logSkip(Skip why, String text) {
        if (why == null || translateCfg == null || !translateCfg.debug) {
            return;
        }
        String t = text == null ? "" : text.replace('\n', ' ');
        if (t.length() > 60) {
            t = t.substring(0, 60) + "…";
        }
        obro1961.chatpatches.ChatPatches.LOGGER.info("[chatpatch] 不翻：{}  文本=[{}]", why.label, t);
    }
    public boolean shouldTranslate(String type, String plainText) {
        Skip why = skipReasonFor(type, plainText, translateCfg);
        logSkip(why, plainText);
        return why == null;
    }

    /**
     * 修正消息分类 —— <b>判断它到底是不是"玩家在说话"</b>。
     *
     * <p><b>为什么需要它</b>：Chat Patches 靠 {@code messageData} 缓存判断"是不是系统消息"
     * （{@code lastEmpty}）。但有些消息拿不到缓存（被别的 mod 改写过、经 ChatHeads 加过头像、
     * 或来自服务器插件），于是被误判成 system。实测一轮下来 {@code system:97 / chat:13} ——
     * 玩家说话全被记成系统消息，而翻译和朗读都以 {@code type==chat} 为前提，于是<b>一起失效</b>。
     *
     * <p><b>判据：正文开头出现 {@code 名字: } 就是玩家聊天</b> —— 用 {@link #SPEAKER_PREFIX}，
     * 和 {@link #resolveSpeaker} <b>同一条</b>。
     *
     * <p>「开头」不等于"只能紧挨着第一个字符"：{@code §} 颜色码、空白、以及资源包的表情占位符
     * 都允许出现在名字前面。MIA 的真实消息长这样：
     * <pre>{@code
     * [emojy:gifs/elmoburn@gui] Noobinator0_0: wb liyu o/
     * }</pre>
     *
     * <p><b>为什么必须是"开头"而不是"任意位置"</b>：任意位置的话
     * {@code Welcome to the server: have fun} 会把 {@code server} 当玩家名 ——
     * 系统播报被判成玩家聊天、{@code have fun} 还会被送去翻译。
     *
     * <p>抽成 public static（不依赖 Minecraft）是为了能拿真实消息单独测 ——
     * 这个方法判错的后果很直接：判漏 → 翻译和朗读全哑；判宽 → 系统播报也被翻。
     */
    public static String classifyMessage(String type, String sender, String plainBody) {
        return classifyMessage(type, sender, plainBody, false);
    }

    /**
     * 同上，但多一个<b>结构信号</b> {@code playerChatHint}。
     *
     * <p><b>★ 为什么必须加这个参数</b>：光靠文本猜名字永远猜不完。实测连着踩了三次：
     * <pre>
     *   Ｌｙｓａｗｅｎ❤             全角字母 + 爱心
     *   🍁𝓐𝔂𝓪 𝓦𝓮𝓷𝓦𝓮𝓷🍁        数学粗斜体 + emoji + 【空格】
     *   aya wenwen              普通字母 + 【空格】
     * </pre>
     * 每次都是"名字里有字符集没覆盖到的东西" → 判成系统消息 → 白名单里没这个词 → 不翻译。
     * 而 MIA 允许玩家随便装饰名字、还能带空格 —— <b>这条路按字符集堵是堵不住的</b>。
     *
     * <p>结构信号来自组件：MIA 的玩家聊天模板里，名字上挂着
     * {@code click_event} = {@code run_command: /mia profile <玩家>}。
     * 只要组件里有这个，就<b>一定是玩家聊天</b> —— 名字长什么样都无所谓。
     *
     * <p>「结构优先于文本」也是这整个项目一直在守的原则：能拿到结构化数据就别去解析字符串。
     *
     * @param playerChatHint 组件里发现了玩家聊天特征（见 {@code ChatStudyBridge.looksLikePlayerChat}）
     */
    public static String classifyMessage(String type, String sender, String plainBody, boolean playerChatHint) {
        if (!LogEntry.TYPE_SYSTEM.equals(type)) {
            return type;   // 已经是 chat / death / join …，不用管
        }
        // ★ 结构信号最可靠，优先
        if (playerChatHint) {
            return LogEntry.TYPE_CHAT;
        }
        // 有发送者资料 → 一定是玩家消息
        if (sender != null && !sender.isBlank()) {
            return LogEntry.TYPE_CHAT;
        }
        // ★★ 2026-10-14：先排除【系统播报句式】✗，再判"像不像聊天" ✓
        //
        //   为什么要这个顺序：MIA 的 Discord 桥接消息（还有把名字装饰过的玩家）
        //   名字里有逗号和空格 ✗，旧的严格 SPEAKER_PREFIX 匹配不上 → 判成 system → 不翻 ✗
        //   （用户实测：「为什么这次无法翻译 discord 的消息了」；
        //     7 个记录文件里 819 条 system，其中 74 条其实是玩家在说话 ✓）
        //
        //   但宽松判定会放过 `Welcome to the server: have fun` ✗ ——
        //   所以**先用数据统计出来的播报句式把它挡掉** ✓ 再放宽松规则 ✓
        //   （顺序不能反：宽规则先跑的话，播报就被当成聊天了 ✗）
        if (plainBody != null && isSystemAnnouncement(plainBody)) {
            return type;   // 确认是服务器播报 → 保持 system ✓
        }
        if (plainBody != null && SPEAKER_RELAXED.matcher(stripSpeakerNoise(plainBody)).find()) {
            return LogEntry.TYPE_CHAT;
        }
        // 老严格规则兜底（形状更窄，不会更宽）
        if (plainBody != null && SPEAKER_PREFIX.matcher(stripSpeakerNoise(plainBody)).find()) {
            return LogEntry.TYPE_CHAT;
        }
        return type;
    }

    /**
     * 这段文本里有没有"真正是文字"的字符。
     *
     * <p>纯数字（{@code 23}、{@code 666}）、纯符号（{@code !!!}）、纯表情都没有，
     * 送去翻译只会让模型硬编出一句话。翻译和朗读都要先过这一关。
     */
    public static boolean hasLetters(String s) {
        // 判断统一交给 ScriptDetector.Profile（那里还带 hasSpeakable / langCode / isCjk）。
        return ScriptDetector.profile(s).hasLetters();
    }

    /**
     * 系统消息正文里是否含配置里的关键词（不区分大小写）。
     *
     * <p>抽成 public static 是为了能单独测 —— 白名单误判的代价很高：
     * 漏了 → 玩家被传送走都不知道为什么；多了 → 又变成"翻译系统提示"那个老问题。
     */
    public static boolean matchesSystemKeyword(String text, TranslateConfig c) {
        if (text == null || text.isBlank() || c == null) {
            return false;
        }
        // null = 老配置里没这个字段 → 用默认白名单；[] = 显式留空 → 全不翻
        java.util.List<String> list = c.systemKeywords != null
                ? c.systemKeywords
                : TranslateConfig.defaultSystemKeywords();
        if (list.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String k : list) {
            if (k != null && !k.isBlank() && lower.contains(k.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 兼容旧签名（只看文本，不看类型）。 */
    public boolean shouldTranslate(String plainText) {
        return shouldTranslate(LogEntry.TYPE_CHAT, plainText);
    }

    /**
     * 该不该读 —— 跟翻译一个口径。
     *
     * <p>系统消息只有命中关键句才读。这其实很有用：
     * 你在挂机，耳朵听到 "You will be teleported in 5 seconds" 就知道该动了。
     */
    public boolean shouldSpeak(String type, String plainText) {
        ReadAloudConfig a = speechCfg;
        if (a == null || !a.enabled) {
            return false;
        }
        TranslateConfig c = translateCfg;
        if (!LogEntry.TYPE_CHAT.equals(type) && !a.speakSystemMessages && !matchesSystemKeyword(plainText, c)) {
            return false;
        }
        String target = c == null ? "简体中文" : c.targetLanguage;
        // ★ 带上"只读英语"（用户要求：非英语原文不念 ✗ 嗓音只有 zh/en）
        return SpeechPolicy.shouldSpeak(plainText, target, true,
                a.readOriginal, a.minLength, a.maxLength, false);
    }

    /**
     * 一条消息解析后的形状 —— <b>全文只在这里算一次</b>。
     *
     * <p><b>为什么要有它</b>：以前 {@code onIncoming} 拿到的是一份"半成品" ——
     * {@code name}（消息自带的发送者资料，很多服务器消息上是 {@code null}）
     * 和 {@code body}（整行文本，名字其实在里面）。于是每个用它的地方都得自己"补一手"：
     * 朗读那补一次、翻译那又补一次，两次算法还不一样 —— 结果译文里带上了名字。
     * 同一件事算两遍，迟早算岔。
     *
     * @param speaker 谁说的（资料 → 正文前缀，兜到底；真的没有则为 {@code null}）
     * @param text    清洗后的整行 —— <b>朗读用这个</b>
     * @param content 去掉名字和前缀的纯内容 —— <b>翻译只送这个</b>
     */
    public record Parsed(String speaker, String text, String content) {
    }

    /**
     * 解析一次，到处引用 —— <b>全项目唯一的解析入口</b>。
     *
     * @param name       结构化说话人，可 {@code null}
     * @param rawContent <b>结构化正文</b>，可 {@code null}。非空时【绝不】切字符串
     * @param plain      整行（已剥颜色码）
     */
    public static Parsed parse(String name, String rawContent, String plain) {
        String text = plain(plain);
        String speaker = resolveSpeaker(name, text);
        // ★ 两条路，只走一条：
        //   ① 源头给了正文 → 直接用。渲染成什么样、资源包怎么改、config.name 开不开，
        //      都影响不到它。这才是「解析一次」的意义。
        //   ② 源头没给（非 chat.type.* 的消息）→ 落到【唯一一处】兜底：按说话人从整行里切。
        String body = rawContent != null && !rawContent.isBlank()
                ? plain(rawContent)
                : splitNameAndContent(text, speaker)[1];
        return new Parsed(speaker, text, body);
    }

    /** 兼容旧签名（没有结构化正文 → 走兜底），保证老调用点走的是同一条路。 */
    public static Parsed parse(String name, String plain) {
        return parse(name, null, plain);
    }

    /** 朗读计划：读这段文本，用这个语言的嗓音（{@code zh} / {@code en}）。 */
    public record Speech(String text, String voiceLang) {
    }

    /**
     * <b>右键菜单「朗读这条」用</b>：把一整个聊天条目变成能念的文本 + 该用的嗓音。
     *
     * <p><b>为什么要单独一个方法</b>：右键拿到的是那条消息的<b>渲染串</b>，里面有三样不该念的东西：
     * <ol>
     *   <li><b>时间戳</b> {@code [14:02:26]} —— 耳朵不需要听"几点几分"</li>
     *   <li><b>译文那一行</b> —— 译文是挂在同一条消息里的<b>第二行</b>
     *       （以 {@code \n} 开头、带 {@link #TRANSLATION_MARK}）。不切掉就会
     *       "原文念完接着念译文"</li>
     *   <li>{@code §} 颜色码、资源包私用区图标、下划线、表情、链接 —— 交给 {@link #speakable} 清洗</li>
     * </ol>
     *
     * <p><b>实测踩到过</b>：以前这里直接把 {@code plain(getString())} 丢给合成，日志里就是
     * <pre>
     * [14:02:38] [chatstudy-tts/INFO] 合成: ... 文本=[[14:02:26] &lt;Xiaoliyu_&gt; 233333
     * </pre>
     * —— <b>时间戳被念了出来</b>，带译文的那几条还会接着念译文。
     *
     * @return {@code null} 表示这行没什么可念的
     */
    public Speech menuSpeech(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String body = stripTimestamp(plain(rawLine));
        // 切掉译文那一行（同一条消息的第二行）
        int cut = body.indexOf('\n');
        if (cut < 0) {
            cut = body.indexOf(TRANSLATION_MARK);
        }
        if (cut >= 0) {
            body = body.substring(0, cut);
        }
        body = body.trim();
        if (body.isBlank()) {
            return null;
        }
        String who = resolveSpeaker(null, body);
        // 原文语言跟随翻译配置的源语言（不再按字形猜）
        // 原文按内容判语言：英文消息用英文嗓音、中文内容用中文嗓音。
        // 译文那边不靠这里——它由 speakTranslation 直接指定目标语言。
        return new Speech(speakable(who, body), ScriptDetector.profile(body).isCjk() ? "zh" : "en");
    }

    /**
     * <b>朗读的全部决策，一次算完：读不读、读什么、用什么嗓音。</b>
     * 返回 {@code null} 表示不读。
     *
     * <p><b>为什么必须是一个方法</b>：这三件事依赖同一份文本分析。之前它们散在
     * {@code shouldSpeak} / {@code speakable} / {@code pickVoiceFor} 三层各自判断，
     * 结果是每加一条规则就只改一层、另一层漏掉 —— 出过两次真实故障：
     * <ul>
     *   <li>朗读那层没算数字，于是"数字读英文"整条规则不生效</li>
     *   <li>英文嗓音那层和低层判断各写一遍字形检查，写岔了也没人发现</li>
     * </ul>
     * 现在只有这里判断。上层拿到 {@code null} 就不读，拿到 {@code Speech} 就照着读。
     */
    /** 朗读的一次“单元”：原文与译文共用同一套校验（长度/表情/策略），只有出口一个。 */
    private void speakUnit(String who, String raw, String lang, boolean self) {
        if (raw == null || raw.isBlank() || (self && !speechCfgAllowsSelf())) {
            return;
        }
        if (SpeechPolicy.isEmoticon(raw)) {
            return;
        }
        if (!speech.allow(raw)) {
            return;
        }
        sink.speak(who, raw, lang);
    }

    private boolean speechCfgAllowsSelf() {
        ReadAloudConfig a = speechCfg;
        return a != null && a.speakOwnMessages;
    }
    /** 译文朗读：开关 + 语言 + 校验，统一走 speakUnit。 */
    private void speakTranslation(String who, String translation) {
        ReadAloudConfig a = speechCfg;
        if (a == null || !a.enabled || !a.readTranslation) {
            return;
        }
        String lang = translateCfg == null ? "zh" : translateCfg.targetLanguage;
        speakUnit(who, translation, lang, false);
    }
    public Speech planSpeech(String type, String name, String body) {
        ReadAloudConfig a = speechCfg;
        if (a == null || !a.enabled) {
            return null;
        }
        // 三段式：只有"读原文"才在这里计划；读译文由朗读单元在译文到达后追加
        if (!a.readOriginal) {
            return null;
        }
        // ★ 不读自己的话 —— <b>决策在这里，不在 Sink 里事后否决</b>。
        //
        // 服务器会把你发的消息原样广播回来，那条和别人的消息长得一模一样。
        // 判据是【说话人】，不是"文本里含不含我刚发出去的内容"（那是启发式：
        // 要设时间窗口，别人复读同一句还会被误伤）。
        //
        // 以前这个判断写在 {@code McSink.speak()} 里 —— 同一个决定有两个出口，
        // 而 {@link Sink#isSelf} 加了却一直没人调用。现在只有一个出口。
        // 附带好处：说话人从正文兜底抠出来之后（服务器消息常常丢发送者资料），
        // 这条判断对那类消息也生效了。
        if (!a.speakOwnMessages && sink.isSelf(name)) {
            return null;
        }
        String raw = plain(body);
        if (raw.isBlank() || raw.indexOf(TRANSLATION_MARK) >= 0) {
            return null;
        }
        // 只读玩家聊天（系统播报另有白名单）
        if (!LogEntry.TYPE_CHAT.equals(type) && !matchesSystemKeyword(raw, translateCfg)) {
            return null;
        }
        if (raw.length() < a.minLength || raw.length() > a.maxLength) {
            return null;
        }
        ScriptDetector.Profile p = ScriptDetector.profile(raw);
        // 念不出来的东西（纯表情、纯符号）不读 —— 注意数字【算】能念
        if (!p.hasSpeakable()) {
            return null;
        }
        // 内置表情/拟声表（awa / qwq / hhh …）—— 两道保险之一
        if (SpeechPolicy.isEmoticon(raw)) {
            return null;
        }
        // 玩家自己列的"不翻译表"（orz / 0w0 …）也不读，一处维护两边一致
        if (Glossary.lookup(raw).keepOriginal()) {
            return null;
        }
        // 目标语言与「读原文」无关：读不读由 readOriginal / onlyEnglish 决定（2026-10-21）
        // ★★ 只读英语（用户要求）✗ —— 非英语原文一律不念
        //
        //   为什么：嗓音只有 zh/en 两档（LocalTts.voiceLanguageFor）✗，
        //   拿英文嗓音念越南语/俄语就是怪声（用户实测听过 ✓）。
        //   判据见 SpeechPolicy.isEnglishText —— 关键是排除【带变音符号的拉丁字母】✓，
        //   否则 `xin chào`（拉丁字母 ✗）会被当成英语 ✗ 又会用英文嗓音念越南语 ✗。
        //   配置项 readAloud.json 的 onlyEnglish（默认 true ✓）
        // 该读了：把文本处理成能念的样子，并把嗓音一起定下来
        return new Speech(speakable(name, raw), p.isCjk() ? "zh" : "en");
    }

    /** 兼容旧签名（只看文本，不看类型）。 */
    public boolean shouldSpeak(String plainText) {
        return planSpeech(LogEntry.TYPE_CHAT, null, plainText) != null;
    }

    // ---- 出站 ----

    /**
     * 自己要发一条消息时：要不要翻成英文再发。
     *
     * @return 实际应该发出去的文本；不翻就返回原文
     */
    /**
     * <b>出站该不该翻：唯一判据。</b>英文、数字、符号原样发出去；其它"不是英语的文字"都要翻。
     *
     * <p><b>为什么必须只有一个实现</b>：这条规则原本写在两个文件里 ——
     * 发送闸门（{@link obro1961.chatpatches.chatstudy.ChatStudyBridge}）写的是
     * "不是英文就拦"，而这里写的是"只有含汉字才翻"。两者不一致的后果是实打实的：
     * 发一个 {@code 2333} 时闸门把它拦下、这里却原样返回，于是"拦下 → 翻译 → sendChat
     * → 又被拦"，死循环写进 44 万条记录。现在两个地方都调这个方法。
     *
     * <p><b>★ 2026-10-12 泛化</b>：原来只认汉字（{@code profile.han()}）——
     * 结果是<b>越南人打越南语根本不触发翻译</b> ✗（越南语是拉丁字母，不是汉字）。
     * 用户要"越南语-英文"那个版本时发现的。现在按"<b>不是英语常用的字形就翻</b>"来。
     *
     * <p>中文用户的行为<b>完全不变</b>：发英文 → 拉丁字母 → 不翻 ✓；
     * 发 2333 / !!! / awa（没有非拉丁字母）→ 不翻 ✓。
     */
    /**
     * <b>出站闸门（配置驱动版）</b> —— 只有这一条规则。
     *
     * <p>判据（用户要求「严格按照配置文件写，把配置的语言跟对照表锁死」）：
     * <b>文本里有没有 {@code outgoingSourceLanguage} 声明的语言的字符</b> ✓
     * <pre>
     *   outgoingSourceLanguage = "中文"   → 有汉字才翻；英文/数字/表情原样发 ✓
     *   outgoingSourceLanguage = "越南语" → 有越南语字符才翻 ✓
     *   outgoingSourceLanguage = "auto"   → 老的启发式（显式选择才用）
     * </pre>
     *
     * <p>为什么改成这样：原来是<b>隐藏的启发式</b> ✗ ——
     * 「{@code Chào nhé} 算不算外语」这种判断反复出错，
     * 而配置里看不出这个决定在哪做 ✗（用户：「不搞自动检测语言了」）。
     * 现在<b>语言写在配置里，规则只有一条</b>，出 bug 改配置就能改行为 ✓
     *
     * @param sourceLanguage 配置里的出站源语言；{@code null} → 视作 {@code "auto"}
     */
    public static boolean needsOutgoingTranslation(String text, String sourceLanguage) {
        return outgoingSkipReason(text, sourceLanguage) == null;
    }

    /**
     * 兼容旧签名：等价于 {@code outgoingSourceLanguage = "auto"}（老的启发式）。
     *
     * @deprecated 生产代码一律走 {@link #needsOutgoingTranslation(String, String)} ——
     *             语言由配置决定 ✓。这条留着只是为了让老自测还能跑。
     */
    @Deprecated
    public static boolean needsOutgoingTranslation(String text) {
        return needsOutgoingTranslation(text, "auto");
    }

    /**
     * 这段文本里有没有<b>越南语专属</b>字符。
     *
     * <p>实现只有一份 —— 在 {@link ScriptDetector#isVietnamese}。
     * 这里只是转发（免得两处各写一遍，改一处忘一处）。
     */
    public static boolean hasVietnameseMarks(String text) {
        return ScriptDetector.isVietnamese(text);
    }

    public String outgoing(String text) {
        TranslateConfig c = translateCfg;
        if (text == null || text.isBlank() || c == null || !c.enabled || !c.translateOutgoing) {
            return text;
        }
        // ★ 只翻中文，别的原样发。
        //
        // 旧规则是"不是英语就翻"，那 "23" / "666" / "awa" 这种"没有文字"的输入也会被送去翻译，
        // 模型会硬编出一句英语 —— 实测刷屏发 "23" 时，每条都变成一句怪话发出去。
        // 用户的原话就是「如果我发的是英语，就不管；如果我发的是中文，翻译成英语」，
        // 所以判据就该是"有没有那门语言的字"。
        //
        // ★★ 2026-10-13：语言不再写死成"中文"，而是由 config 声明 ✓
        //    （用户：「把配置的语言跟对照表锁死」）—— 越南语构建填"越南语"即可 ✓
        if (!needsOutgoingTranslation(text, c.outgoingSourceLanguage)) {
            return text;
        }
        try {
            String out = translator.outbound(text, c.outgoingTargetLanguage);
            if (out == null || out.isBlank()) {
                return text;   // ★ 失败就发原文，绝不吞消息
            }
            return out;
        } catch (Exception e) {
            sink.notice("§c[chatpatch] 出站翻译失败，已按原文发出: §7" + e);
            return text;
        }
    }

    /**
     * 把一个"带频道的命令"拆成 {@code [频道词, 正文]}；不是这种命令就返回 {@code null}。
     *
     * <p><b>为什么需要它</b>：MIA 的本地频道是"切模式"（切完直接打字 = 普通聊天），
     * 但全球频道是<b>打成命令</b>发出去的（{@code /g 内容}）。
     * 命令走的是 {@code ALLOW_COMMAND}，<b>根本不经过聊天的出站闸门</b> ——
     * 于是打出去的中文既没被翻译、也没被改造回显。
     * 实测证据（日志时间线）：
     * <pre>
     *   15:24:21  切到 local
     *   15:24:32  出站翻译成功 mymemory
     *   15:24:33  已把回显改造成「中文 + ↳ 英文」
     *   —— 而 global 那一段（15:14:20~15:18:44）这两条日志一条都没有
     * </pre>
     *
     * <p>只认<b>配置里列出的频道词</b>，不做通用猜测 ——
     * {@code /msg Bob 你好} 这种绝不能把 {@code Bob 你好} 整段拿去翻。
     *
     * @return {@code [head, rest]}；{@code head} 已小写。不是频道命令返回 {@code null}
     */
    public static String[] splitChannelCommand(String command, java.util.List<String> heads) {
        if (command == null || heads == null || heads.isEmpty()) {
            return null;
        }
        String s = command.startsWith("/") ? command.substring(1) : command;
        int sp = s.indexOf(' ');
        if (sp <= 0) {
            return null;                       // 只有命令词、没有正文
        }
        String head = s.substring(0, sp).toLowerCase(java.util.Locale.ROOT);
        if (!heads.contains(head)) {
            return null;                       // 不在白名单里 —— 不碰
        }
        String rest = s.substring(sp + 1).trim();
        if (rest.isEmpty()) {
            return null;
        }
        return new String[]{ head, rest };
    }

    /**
     * 把一段文本按 <b>[from, to)</b> 切开，并标记"替换内容插在哪"。
     *
     * <p><b>为什么要单独抽出来</b>：这是"保留样式只换内容"那个操作的<b>纯逻辑核心</b> ——
     * 切片越界、插错位置、插两次，都是很容易写错的。而真正用它改聊天栏的代码
     * （{@code ChatStudyBridge.replaceRange}）依赖 Minecraft 类型，
     * <b>快速自测编译不到</b>，只能靠进游戏试。前面已经两次栽在"只有游戏能验证"的代码上
     * （attach 时序、回显匹配），所以把这块数学拿出来，放在能测的地方。
     *
     * @param runs 按顺序排好的"样式段"文本（首尾相接就是整行）
     * @return 按顺序要输出的片段；{@code replacement=true} 的那一项表示此处插入替换内容
     */
    public static List<Piece> splice(List<String> runs, int from, int to) {
        List<Piece> out = new java.util.ArrayList<>();
        int pos = 0;
        boolean inserted = false;
        for (int i = 0; i < runs.size(); i++) {
            String t = runs.get(i);
            int s = pos;
            int e = pos + t.length();
            pos = e;
            if (e <= from || s >= to) {                 // 与替换区间无交集：整段保留
                out.add(new Piece(i, 0, t.length(), false));
                continue;
            }
            if (s < from) {                             // 区间左边那一截
                out.add(new Piece(i, 0, from - s, false));
            }
            if (!inserted) {                            // 替换内容只插一次
                out.add(new Piece(i, 0, 0, true));
                inserted = true;
            }
            if (e > to) {                               // 区间右边那一截
                out.add(new Piece(i, to - s, t.length(), false));
            }
        }
        if (!inserted) {                                // 区间落在末尾之外：补一次
            out.add(new Piece(Math.max(0, runs.size() - 1), 0, 0, true));
        }
        return out;
    }

    /**
     * {@link #splice} 输出的一项。
     *
     * @param run         取第几个"样式段"的样式
     * @param fromInRun   该段内的起始下标（含）
     * @param toInRun     该段内的结束下标（不含）；{@code replacement=true} 时这两个无意义
     * @param replacement 这一项是要插入的替换内容，不是原文片段
     */
    public record Piece(int run, int fromInRun, int toInRun, boolean replacement) {
    }

    /**
     * 翻译回来后的日志条目（供落盘）。
     *
     * @param refSeq 这条译文挂在<b>哪条消息的 {@code seq}</b>（当天消息编号，1 起）；
     *               {@code <= 0} = 不知道，那就退回"下一行"的老配对方式。
     * @param lang   译文的目标语言（写进 <code>translation.lang</code>，多语言/排障要用）。
     *
     * <p><b>为什么是 {@code refSeq} 而不是物理行号</b>（2026-10-20 M2b）：只追加的文件里
     * 行号会被 <code>session</code> 头和 <code>delete</code> 标记顶偏 —— 第 1 条消息的
     * {@code seq} 是 1，但它可能是文件第 2 行。两套编号各算一次，配对就会错到别的消息上。
     * 存储契约 S3：一切引用用 {@code seq}，行号只留作旧数据兼容（{@code ref}）。
     */
    public static LogEntry translationEntry(String time, String translation, int refSeq, String lang) {
        LogEntry e = new LogEntry();
        e.type = "translation";
        e.time = time;
        e.text = translation;
        e.refSeq = refSeq > 0 ? refSeq : null;
        e.lang = lang;
        return e;
    }

    // ---- 工具 ----

    /**
     * 点击后翻译模式 = 不自动翻（R16）。
     *
     * <p>为什么在 pipeline 层判而不是在 bridge 层拦：自动翻译的"决定"本来就只有
     * {@link #onIncoming} 一个出口，多拦一处就又变成"同一个决定两个出口"。
     */
    private boolean autoTranslateDisabled() {
        return translateCfg != null && translateCfg.translateOnClick;
    }

    /**
     * <b>手动翻译一条消息</b>（右键菜单「翻译这条」，R16）。
     *
     * <p>和自动翻译<b>共用同一套</b>缓存、落盘与挂行逻辑 —— 不另写一条路。
     * 这样"手动翻的"和"自动翻的"在文件里长得一模一样（`translation` + `refSeq` + `lang`），
     * 灌回/导出/搜索都不用区分来源。
     *
     * @param handle 那条消息的组件（挂译文行用）
     * @param content 要翻的内容（不含时间戳和名字）
     * @param refSeq 那条消息的 seq；{@code <=0} = 不知道（旧消息），落盘会退回"下一行"配对
     */
    public void translateMessage(M handle, String content, int refSeq) {
        if (content == null || content.isBlank()) {
            return;
        }
        executor.execute(() -> {
            try {
                String tr = translator.inbound(content);
                if (tr != null && !tr.isBlank()) {
                    sink.attachTranslation(handle, tr, refSeq);
                }
            } catch (Exception e) {
                sink.notice("§c[chatpatch] 翻译失败: §7" + e);
            }
        });
    }


    /**
     * 出站译文是不是<b>把简写表原样背出来</b>。
     *
     * <p><b>为什么必须有这条</b>（2026-10-20 用户当场撞上）：打中文两个字，
     * 模型把 prompt 里那份 83 条简写清单当成译文吐了出来，直接被当"我说的话"发到公聊：
     * <pre>
     *   alr ive lmk lemme gimme pls wdym wym sup idk idc ikr ig nvm lf lfm lfp
     *   wts wtb ngl tbh imo btw fr smh bruh omg rn rq asap omw ty thx tysm yw np sry mb wb gn
     * </pre>
     * 提示词已经改成"只给两个例子、禁止罗列"，但<b>不能只靠模型自觉</b>：
     * 免费源（35B）换个版本就可能再犯。这里是最后一道闸，判据不依赖任何模型行为。
     *
     * <p><b>两条判据（任一命中就算）</b>：
     * <ol>
     *   <li><b>内容像表</b>：输出切成词后 ≥6 个词，其中 ≥5 个、且 ≥60% 是简写表里的词
     *       —— 一句话的正常译文不会长这样。</li>
     *   <li><b>长度不成比例</b>：输入（去掉颜色码/图标后）≤8 个字符，输出却有 ≥12 个词
     *       —— 两个字翻出十几个词，要么在背表，要么在编话。</li>
     * </ol>
     *
     * <p>命中后的处置在 {@code TranslationManager.translateOutgoing}：换下一个源；
     * 全都不行就返回 null → <b>发原文</b>（绝不吞消息，也绝不让模型代你刷屏）。
     */
    public static boolean looksLikeAbbrevDump(String input, String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        java.util.Set<String> abbr = obro1961.chatpatches.translate.Glossary.expandTerms().keySet();
        int total = 0;
        int hit = 0;
        for (String t : output.trim().toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9']+")) {
            if (t.isEmpty()) {
                continue;
            }
            total++;
            if (abbr.contains(t)) {
                hit++;
            }
        }
        if (total >= 6 && hit >= 5 && hit * 100 / total >= 60) {
            return true;
        }
        int inLen = input == null ? 0 : plain(input).trim().length();
        return inLen <= 8 && total >= 12;
    }


    /** 剥掉 § 颜色码和私用区图标，只留可判断的文字。 */
    public static String plain(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                i++;
                continue;
            }
            if (c >= '\uE000' && c <= '\uF8FF') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 给测试/调试用。 */
    public List<String> describe() {
        return List.of(
                "translate=" + (translateCfg != null && translateCfg.enabled),
                "target=" + (translateCfg == null ? "-" : translateCfg.targetLanguage),
                "speak=" + (speechCfg != null && speechCfg.enabled),
                "onlyWhenTranslated=" + (speechCfg != null && speechCfg.readOriginal),
                "speakOwnMessages=" + (speechCfg != null && speechCfg.speakOwnMessages));
    }
}
