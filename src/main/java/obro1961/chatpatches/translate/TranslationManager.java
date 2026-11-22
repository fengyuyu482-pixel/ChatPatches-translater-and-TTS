package obro1961.chatpatches.translate;

import static obro1961.chatpatches.ChatPatches.LOGGER;
import obro1961.chatpatches.pipeline.ChatStudyPipeline;
import obro1961.chatpatches.translate.Glossary;
import obro1961.chatpatches.translate.TranslateConfig;
import obro1961.chatpatches.translate.ConfigStore;
import obro1961.chatpatches.translate.ApiProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class TranslationManager {

    /** 按配置列表顺序保存，顺序即优先级。 */
    private static final Map<String, TranslateProvider> PROVIDERS = new LinkedHashMap<>();

    /** 最近一次自检结果，留给将来的文本框/悬浮面板复用。 */
    private static volatile Map<String, ProbeResult> lastCheck = new LinkedHashMap<>();

    private static String active = "deepseek";

    /** 源 id → 冷却到什么时候（毫秒时间戳）。撞 429 才写。 */
    private static final java.util.Map<String, Long> COOLDOWN_UNTIL = new java.util.concurrent.ConcurrentHashMap<>();
    /** 源 id → 连续撞了几次限流（用来翻倍）。 */
    private static final java.util.Map<String, Integer> COOLDOWN_STREAK = new java.util.concurrent.ConcurrentHashMap<>();

    /** 测试用：清空冷却状态。 */
    public static void resetCooldown() {
        COOLDOWN_UNTIL.clear();
        COOLDOWN_STREAK.clear();
    }

    /** 这个源现在是不是还在冷却。 */
    public static boolean isCoolingDown(String id) {
        Long until = id == null ? null : COOLDOWN_UNTIL.get(id);
        return until != null && until > System.currentTimeMillis();
    }

    /**
     * 这个异常是不是"被限流"（R11）。
     *
     * <p>判据故意放宽一点：除了 429，也认报错文本里的 rate limit / too many requests ——
     * 有些免费源（B站/mymemory）限流时返回 200 带一段错误、或 403，光看状态码会漏。
     * <p>public 是为了能单独测：判错的代价是"把正常的失败当成限流、白白冷却 20 秒"。
     */
    public static boolean isRateLimited(Throwable ex) {
        if (ex == null) {
            return false;
        }
        String msg = String.valueOf(ex.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return msg.contains("429") || msg.contains("rate limit") || msg.contains("too many request")
                || msg.contains("quota") || msg.contains("throttl");
    }

    /**
     * 连续第 {@code streak} 次撞限流时该冷却多久：base, 2×base, 4×base… 不超过 cap。
     * <p>public 是为了能单独测。
     */
    public static long backoffMs(int streak, long base, long cap) {
        long b = Math.max(1000L, base);
        long c = Math.max(b, cap);
        int n = Math.max(1, Math.min(6, streak));
        long v = b << (n - 1);
        return Math.min(v, c);
    }

    /** 记一次限流，并返回冷却到什么时候。 */
    public static long markRateLimited(String id, TranslateConfig cfg) {
        int streak = COOLDOWN_STREAK.merge(id, 1, Integer::sum);
        long wait = backoffMs(streak, cfg.rateLimitBackoffMs, cfg.rateLimitBackoffMaxMs);
        long until = System.currentTimeMillis() + wait;
        COOLDOWN_UNTIL.put(id, until);
        LOGGER.warn("[chatpatch] {} 被限流（连续 {} 次），冷却 {}ms 后再试", id, streak, wait);
        return until;
    }

    /** 成功一次就清掉冷却/连击。 */
    public static void clearRateLimit(String id) {
        COOLDOWN_STREAK.remove(id);
        COOLDOWN_UNTIL.remove(id);
    }

    private TranslationManager() {
    }

    /** 按配置重建 provider 实例。改完配置执行 /chatstudy reload 生效，无需重启游戏。 */
    public static synchronized void rebuild() {
        PROVIDERS.clear();
        TranslateConfig cfg = ConfigStore.translate();
        if (cfg.providers != null) {
            for (ApiProvider pc : cfg.providers) {
                // ★★ 2026-11-01 重构：**身份从"名字"改成"槽位号"** —— 行为不变，只换标识 ✓
                //
                //   以前这里判 `pc.name.isBlank() → continue` ✗ ——
                //   于是"用户填了免费槽位但名字留空"会被静默跳过（填了不生效、还不报错）✗
                //   现在只看 slot ✓（加载时由 ConfigStore.assignSlots 补齐 ✓ 不会漏 ✓）
                if (pc == null || pc.slot <= 0) {
                    continue;
                }
                // 关掉的不参与；本地引擎（type=local）不需要 baseUrl；其余必须填地址
                if (!pc.enabled) {
                    continue;
                }
                // 免费 API 总开关：关掉后 bilibili / mymemory / 免费槽 一律不参与
                // ★ 判据仍看"名字/标题" ✓ —— 槽位号是数字，没法从里面看出是不是免费源 ✓（行为不变）
                if (!cfg.freeApiEnabled) {
                    if (looksFree(pc)) {
                        continue;
                    }
                }
                boolean local = "local".equals(pc.type);
                boolean hasBase = pc.baseUrl != null && !pc.baseUrl.isBlank();
                if (!local && !pc.isGetType() && !hasBase) {
                    continue; // 空着就跳过，自动轮到下一个
                }
                // 有 endpoint 就是 GET 型，否则当 POST 型
                // ★ 槽位号当 key（身份 ✓）；provider 的 id 传【显示名】✓ —— 日志里还是"在线/本地"这种人话 ✓
                String key = String.valueOf(pc.slot);
                String display = displayName(pc);
                PROVIDERS.put(key, pc.isGetType()
                        ? new SimpleGetProvider(display, pc)
                        : new OpenAiCompatibleProvider(display, pc));
            }
        }
        active = cfg.activeProvider == null ? "" : cfg.activeProvider;
        lastCheck = new LinkedHashMap<>();
    }

    public static List<String> ids() {
        return new ArrayList<>(PROVIDERS.keySet());
    }

    public static String activeId() {
        return active;
    }

    public static TranslateProvider get(String id) {
        return PROVIDERS.get(id);
    }

    public static Map<String, ProbeResult> lastCheck() {
        return lastCheck;
    }

    public static void setActive(String id) {
        active = id;
        ConfigStore.translate().activeProvider = id;
        ConfigStore.saveTranslate();
    }

    /**
     * <b>入站翻译的总时间预算。</b>
     *
     * <p>为什么要它：出站早就有预算了（见 {@link #OUTGOING_BUDGET_MS}，注释里记着"等过 40 秒"那次教训），
     * 但<b>入站漏了</b> —— 每个源各用配置里的 8 秒，三个源最坏 24 秒。
     * 实测真的出现过：日志里 {@code deepseek 翻译成功 (19671ms)} 和 {@code (25616ms)}。
     *
     * <p>聊天是即时场景，译文晚到 20 秒等于没翻。所以定一个总预算，超了就放弃、静默 ——
     * 宁可这一条不翻，也不要挂一条"人来人往之后"才出现的迟到译文。
     *
     * <p><b>★ 但它必须比"单次上限"大，否则降级链等于不存在</b> ——
     * 见 {@link #totalBudgetMs(long)}。踩过的坑：
     * 用户配的 {@code timeoutMs=8000} 和这里的 {@code 8000} 正好相等，
     * 于是第一次尝试就能吃掉整个总预算，轮到降级时剩余为 0 → 直接放弃 →
     * 表现就是「有些消息死活不翻译」（实测日志：{@code 入站翻译总预算 8000ms 用完，不再降级}，
     * 而那条消息正是 8 秒前进来的那一条）。
     */
    public static final long INBOUND_BUDGET_MS = 8000;

    /**
     * <b>实际用的总预算 = 单次上限 + 4000ms 的降级余量</b>，夹在 {@code [INBOUND_BUDGET_MS, 20000]}。
     *
     * <p>为什么要"加"：总预算如果 ≤ 单次上限，第一次尝试就会把它全部吃掉，
     * 后面的源一个都轮不到 —— 降级链形同虚设。
     * 加出来的这 4000ms 就是保证"至少还有一次降级机会"。
     *
     * <p>为什么要"夹"：用户可能把 {@code timeoutMs} 配得很大（比如 30 秒），
     * 那不该让入站也跟着等 34 秒 —— 聊天是即时场景。
     */
    public static long totalBudgetMs(long perAttemptMs) {
        return budgetFor(perAttemptMs, INBOUND_BUDGET_MS, 20000);
    }

    /** 出站的同款规则（见 {@link #totalBudgetMs}）。 */
    public static long outgoingBudgetMs(long perAttemptMs) {
        return budgetFor(perAttemptMs, OUTGOING_BUDGET_MS, 30000);
    }

    /**
     * 实际总预算 = 单次上限 + 4000ms（降级余量），夹在 {@code [floor, cap]}。
     *
     * <p>入站出站都用这一条 —— 同一个坑不能只在一个方向填。
     */
    private static long budgetFor(long perAttemptMs, long floor, long cap) {
        return Math.min(cap, Math.max(floor, Math.max(1000, perAttemptMs) + 4000));
    }

    /**
     * 当前源先试，失败就按列表顺序往后走（永远降级，不给开关）。
     *
     * @return 译文；全部失败返回 null，调用方决定是否退回原文
     */
    /**
     * <b>术语表的整句命中，能不能直接拿来当译文用</b>。
     *
     * <p>判据：<b>表里的值本身就得是目标语言</b>。
     * 术语表的值全是中文，所以只有"翻成中文"时才能白拿；翻成越南语/英文/日语时必须走 API ✗。
     *
     * <p>抽成 public static 是为了能单独测 —— 这条判错的代价是"整整一类消息永远翻错语言"，
     * 而且看起来一切正常（有译文、不报错、速度还快）。
     */
    public static boolean canUseGlossaryHit(String value, String targetLanguage) {
        return ScriptDetector.isTargetLanguage(value, targetLanguage);
    }

    public static String translate(String text, List<String> context) {
        TranslateConfig cfg = ConfigStore.translate();
        if (!cfg.enabled) {
            return null;
        }
        // ★ ① 先查【目标语言那一份对照表】（glossary.json 的 byLanguage 段）。
        //   有的话整句零成本返回 —— 这才是"越南语对照表"该走的路。
        String langHit = Glossary.wholeLineFor(text, cfg.targetLanguage);
        if (langHit != null) {
            LOGGER.info("[chatpatch] {} 对照表整句命中，跳过 API: {} → {}", cfg.targetLanguage, text, langHit);
            return langHit;
        }

        // ② 术语表先查：整句命中就直接返回，零成本零延迟，不烧 token
        Glossary.Match gm = Glossary.lookup(text, cfg.targetLanguage);   // ★ 带上目标语言：提示词要给对语言的值
        if (gm.keepOriginal()) {
            LOGGER.debug("[chatpatch] 术语表标记为「不翻译」（表情/拟声），跳过: {}", text);
            return null;
        }
        if (gm.full() != null) {
            // ★★ 只有【目标语言和表里的值同语言】时才能直接用整句命中。
            //
            //   为什么：术语表里存的值<b>全是中文</b>（{@code wb → 欢迎回来}）✗。
            //   原来这里不看目标语言，直接返回 —— 于是把目标设成越南语/英文时，
            //   整句 {@code wb} / {@code ty} / {@code gg} 照样吐中文 ✗
            //   （用户实测反馈：「越南语版本好像还是把英语翻译成中文而不是越南语」）。
            //
            //   现在：不是中文目标就<b>忽略整句命中，走 API</b> ——
            //   提示仍然带上（模型知道 wb 是什么意思），只是花一次调用换正确的语言。
            if (canUseGlossaryHit(gm.full(), cfg.targetLanguage)) {
                LOGGER.info("[chatpatch] 术语表整句命中，跳过 API: {}", text);
                return gm.full();
            }
            LOGGER.info("[chatpatch] 术语表整句命中，但目标是「{}」不是中文 → 改走 API: {}",
                    cfg.targetLanguage, text);
        }
        final long base = Math.max(1000, cfg.timeoutMs);
        // ★ R1 ①：入站的 sourceLanguage:"auto" 已废弃（10-19 决定）——
        //   入站不做语言识别，只看"是不是已经是目标语言"（汉字/西里尔那套 100% 判据）。
        //   老配置里还写着 auto 的，提示一次，行为按"未配置"处理（不报错、不阻塞翻译）。
        // 源语言=「自动检测」：按消息判定，判不出就用上一句/英语兜底
        warnDeprecatedInboundAuto(cfg);

        // ★★ 占位符保护：@名字 / 坐标 / 命令 / 链接 / 数字 先换成 ⟦n⟧，
        //    模型根本看不到原词 → 想翻也翻不了。翻完再回填。
        TextProtector.Masked masked = TextProtector.mask(text);
        final String sendText = masked.masked();
        final String originalText = text;   // 校验时要用原始文本比对数字/长度

        // ★ 总预算必须比单次上限大，否则第一次就能吃掉全部、降级链形同虚设（见 totalBudgetMs）
        final long total = totalBudgetMs(base);
        final long deadline = System.currentTimeMillis() + total;
        for (String id : order()) {
            TranslateProvider p = PROVIDERS.get(id);
            // 语言能力不够就跳过（本地模型默认只认中/英/日），不浪费一次请求
            ApiProvider cap = findProvider(cfg, id);
            if (!LanguageSupport.supports(cap, cfg.targetLanguage)) {
                // ★ 表即穷举：能力表里没有这一格 = 真翻不了，直接跳过，让降级链交给下一个
                //   日志写清原因，排查"这句为什么没翻"时不用猜
                LOGGER.info("[chatpatch] 跳过 {}：{}（目标 {}）", id,
                        LanguageSupport.skipReason(cap, cfg.targetLanguage), cfg.targetLanguage);
                continue;
            }
            if (p == null) {
                continue;
            }
            // ★ §6.4 implemented=false：界面上保留、但代码不支持 → 跳过并写日志（别静默）
            ApiProvider ap = ConfigStore.find(id);
            if (ap != null && !ap.implemented) {
                LOGGER.info("[chatpatch] {} 未实现（implemented=false），跳过", id);
                continue;
            }
                if (ServiceWatchdog.isKnownDown(ap)) {
                    LOGGER.info("[chatpatch] {} 已知连不上，跳过", id);
                    continue;
                }
            if (!p.ready()) {
                // ★ 说清为什么不可用（开关关着 / key 没填 / 端点没填）
                //   旧代码在这里静默 continue —— 用户看到"配了却不生效"完全无从查起
                LOGGER.info("[chatpatch] 跳过 {}：{}", id, OpenAiCompatibleProvider.readiness(ap));
                continue;
            }
            if (isCoolingDown(id)) {
                LOGGER.info("[chatpatch] {} 刚被限流，冷却中，跳过（R11）", id);
                continue;
            }
            long budget = attemptBudget(deadline, base);
            if (budget <= 0) {
                LOGGER.warn("[chatpatch] 入站翻译总预算 {}ms 用完（单次上限 {}ms），不再降级",
                        total, base);
                break;
            }
            try {
                long t0 = System.currentTimeMillis();
                String raw = p.translate(sendText, cfg.sourceLanguage, cfg.targetLanguage, context, gm.hints(), null, budget);
                long took = System.currentTimeMillis() - t0;
                if (raw != null && !raw.isBlank()) {
                    // 兜底：provider 那边已经用 orTimeout 限过时长了，但万一将来有人不理会给它的预算，
                    // 迟到的译文也不该挂出去 —— 宁可这条不翻，也不要挂一条人来人往之后才出现的译文。
                    if (took > budget + 250) {
                        LOGGER.warn("[chatpatch] {} 迟到的译文（{}ms > 预算 {}ms），丢弃", id, took, budget);
                        return null;
                    }
                    // ★ ① 回填占位符（缺一个就作废 —— 模型吃掉了内容）
                    String out = TextProtector.restore(raw, masked.tokens());
                    if (out == null) {
                        LOGGER.warn("[chatpatch] {} 占位符回填失败，换下一个源", id);
                        continue;
                    }
                    // ★ ② 六项质量校验（数字/脚本/长度/自我解释/退化重复）
                    String verified = TranslationVerifier.checkAndLog(
                            originalText, out, cfg.targetLanguage, id);
                    if (verified == null) {
                        LOGGER.warn("[chatpatch] {} 译文未通过校验，换下一个源", id);
                        continue;
                    }
                    clearRateLimit(id);
                    LOGGER.info("[chatpatch] {} 翻译成功 ({}ms)", id, took);
                    return verified;
                }
                LOGGER.warn("[chatpatch] {} 返回空译文", id);
            } catch (Exception ex) {
                if (isRateLimited(ex)) {
                    markRateLimited(id, cfg);
                }
                LOGGER.warn("[chatpatch] {} 失败: {}", id, ex.toString());
                String lowerId = id.toLowerCase(java.util.Locale.ROOT);
                if (lowerId.contains("mymemory")) {
                    // 免费源共用一个反馈：最后一个免费源也失败，才提示“免费 API 全部不可用”
                } else if (!lowerId.contains("bili") && !id.contains("免费")) {
                }
            }
        }
        return null;
    }

    /**
     * 本轮请求该拿多少超时。
     *
     * <p><b>关键</b>：光"每轮开头看一眼还没到点"是封不住总时长的 —— 一轮请求自己就能跑满
     * 配置里的超时，于是"总预算 8 秒"实际会变成"最后一次尝试又跑满 8 秒"。
     * 把本轮的超时也压进剩余时间内，总时长才真的封得住。
     *
     * @return 本轮可用毫秒数；{@code <=0} 表示预算已用完，一个请求都不该再发
     *
     * <p>public 是为了能单独测 —— 这个方法判错的代价很直接：
     * 判宽了总时长封不住（就是"25 秒才翻译回来"那个问题），判严了第一条就被挡掉、完全不翻。
     */
    public static long attemptBudget(long deadline, long base) {
        long remaining = deadline - System.currentTimeMillis();
        return remaining <= 0 ? 0 : Math.min(remaining, base);
    }

    /**
     * 出站翻译的风格要求：写成母语玩家那种短、随意、带简写的英文。
     *
     * <p><b>★ 简写清单不再是硬编码的</b> —— 直接从 {@code glossary.json} 的
     * {@code expand} 表（英→英展开表）生成。
     *
     * <p>为什么：原来这里手写了十来个词（{@code ty, thx, wb, lol, brb, idk, rn, ...}），
     * 和术语表各写一份 —— 加词要改两处，漏一处就不生效。
     * 现在同一张表<b>两个方向</b>用：
     * <ul>
     *   <li>入站：{@code lmk = let me know} 当术语提示，让模型看懂缩写再翻</li>
     *   <li>出站：这张表的 key 就是"该用哪些简写"的清单</li>
     * </ul>
     * 用户在配置里加一条 {@code lmk}，两个方向同时生效 —— 不用改代码、不用重编。
     */
    public static String outgoingStyle() {
        return outgoingStyle(ConfigStore.translate().outgoingTargetLanguage);
    }

    /**
     * 出站风格提示词，**按目标语言生成**。
     *
     * <p>★ 这里踩过大坑：原来这段写死了 {@code "on an English Minecraft server"}
     * 和 {@code "Never use formal or textbook English"} ——
     * 用户把目标语言改成越南语，模型照样吐英文 ✗
     * （实测：中文「你好，我要去挖矿了」→ {@code "hey, i'm off to mine."}）。
     *
     * <p>原因：provider 的 system prompt 里<b>确实</b>有
     * {@code "Translate the text into <目标语言>"}，但这段风格说明更具体、更强势，
     * 还明着提 English —— 模型听后者。**两处指令互相矛盾时，模型会跟更具体的那条。**
     *
     * <p>现在两处都提目标语言、不再自相矛盾。
     * 简写清单只在目标语言是英文时给 —— 给越南语塞 {@code (ty, wb, lol...)} 只会添乱。
     */
    public static String outgoingStyle(String targetLanguage) {
        // ★ 用注册表的英文名（{@code en_us → English}），不能把 locale 码拼进提示词 ——
        //   模型看到 "written in en_us" 会当成一门没听过的语言。
        String lang = ScriptDetector.promptLanguageName(targetLanguage);
        boolean english = "English".equalsIgnoreCase(lang) || "eng".equalsIgnoreCase(lang);

        StringBuilder sb = new StringBuilder();
        sb.append("The output will be SENT as your own chat message on a Minecraft server,")
          .append(" and it MUST be written in ").append(lang).append(".")
          .append(" Write it the way a native player types in ").append(lang)
          .append(": SHORT, casual, all lowercase (except names).");

        if (english) {
            // ★★ 2026-10-20 修：这里以前把【整张简写表（用户配置里 83 条）】塞进 system prompt，
            //    还写着 "if one of these abbreviations covers the meaning, output just that"。
            //
            //    实测（用户当场撞上）：他打中文两个字，bili 的 Index-Translate-35B 把
            //    【整张表】当成译文原样吐了出来，直接被当成"我发的话"发到公聊：
            //      alr ive lmk lemme gimme pls wdym wym sup idk idc ikr ig nvm lf lfm lfp
            //      wts wtb ngl tbh imo btw fr smh bruh omg rn rq asap omw ty thx tysm yw
            //      np sry mb wb gn
            //    证据：当天聊天记录 jsonl 里 seq=23 的 outgoing（14:15:13），以及
            //    latest.log 14:15:13「出站翻译成功 bili (2032ms)」。
            //
            //    根因：模型把"可以用简写"读成了"要输出简写清单"。清单越长，越像"要输出的内容"。
            //    现在：①这里只留 2 个例子、明令禁止罗列；②真正跟输入相关的简写
            //    改由 Glossary.reverseHints(输入) 逐句算出（见 translateOutgoing）。
            sb.append(" Use the shortest form that fits: if the meaning is exactly a common")
              .append(" chat abbreviation, one abbreviation is fine (e.g. thank you -> ty,")
              .append(" good night -> gn).")
              .append(" Those are STYLE examples, not content: NEVER list abbreviations,")
              .append(" NEVER output more than one of them, and never output anything")
              .append(" that is not the translation of the input.");
        } else {
            // 非英语：不塞英文简写清单，只说"用这门外语里玩家平时打字的那个短说法"
            sb.append(" Use the SHORTEST casual form a native speaker would actually type in ")
              .append(lang).append(" — the way players type in that language's own chat.")
              .append(" Do not write English, and do not mix languages.");
        }

        // ★ 「不许加词」这一条是实测逼出来的：
        //   用户发「晚安」，模型输出的是 "Good night, honey." ——
        //   自己补了个 honey，还大写，完全不是外服聊天的样子。
        //   原提示词只说了"短、随意、可以用简写"，【没说不能加输入里没有的词】。
        sb.append(" Do NOT add anything that was not in the input:")
          .append(" no endearments (honey / dear / babe / bro), no extra greetings,")
          .append(" no explanations, no politeness the user did not say.")
          .append(" If the input is just a greeting, output just the greeting.")
          .append(" Never use formal or textbook wording.");
        return sb.toString();
    }

    /**
     * 出站翻译：中文 → 目标语言。
     *
     * <p>风格提示词<b>每次现算</b>（{@link #outgoingStyle()}），不再缓存成静态常量 ——
     * 缓存的话在类加载那刻就把目标语言定死了，用户改了配置也不生效 ✗
     *
     * <p>和入站不同：<b>不做术语表整句短路</b>（那是英文→中文方向的），
     * 只吃 {@link Glossary#reverseHints} 给的「中文 → 英文缩写」提示。
     *
     * <p><b>关键：整条降级链共用一个时间预算。</b>
     * 实测踩过大坑：每个 provider 各自 20 秒超时，deepseek 超时后降级到 mymemory 又等 20 秒，
     * 用户发一句"奶龙"等了 <b>40 秒</b>（等不及又发了两遍，加起来更久）。
     * 所以这里记一个总时限，超了就不往下试 —— 宁可发原文，也不能让人干等。
     */
    public static final long OUTGOING_BUDGET_MS = 12000;

    /** 入站 auto 废弃提示只打一次（每进程一次，不刷屏）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean WARNED_INBOUND_AUTO =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 入站 {@code sourceLanguage:"auto"} 的废弃提示（R1 ①）。
     *
     * <p>为什么"提示"而不是"直接改配置里的值"：配置至上（C5），程序不许替用户改值；
     * 但也不能一声不吭地让老配置一直走老语义 —— 所以只提示一次，让人知道该改。
     */
    static void warnDeprecatedInboundAuto(TranslateConfig cfg) {
        if (cfg == null || cfg.sourceLanguage == null) {
            return;
        }
        String s = cfg.sourceLanguage.trim();
        if (!s.isEmpty() && !"auto".equalsIgnoreCase(s)) {
            return;
        }
        if (WARNED_INBOUND_AUTO.compareAndSet(false, true)) {
            LOGGER.warn("[chatpatch] 入站的 sourceLanguage=\"{}\" 已废弃（R1）：入站不再做语言识别，"
                    + "只看「是不是已经是目标语言」。建议把 1-translate.json 的 sourceLanguage 改成具体语言名"
                    + "（如「英语」）；不改也不会报错。", cfg.sourceLanguage);
        }
    }

    /**
     * <b>出站翻译的源语言 —— 取自配置，绝不用 {@code auto}</b> ✗
     *
     * <p>★ 为什么单独抽一个方法（2026-10-13 踩的坑）：
     * 这里原来硬写 {@code "auto"} ✗ —— OpenAI 兼容的源（bili）能把 auto 当"你自己认" ✓，
     * 但 <b>GET 型接口（mymemory）会直接报错</b> ✗：
     * <pre>
     *   langpair=auto|en → 403 'AUTO' IS AN INVALID SOURCE LANGUAGE …
     *   有时是 HTTP 200 + 正文「PLEASE SELECT TWO DISTINCT LANGUAGES」✗
     * </pre>
     * 后面那句会被当成"译文"发到公聊 ✗ —— 用户实测：
     * 「我发出去的越南语叫我选择两个语言」。
     *
     * <p>现在取 {@code translate.json} 的 {@code outgoingSourceLanguage} ✓
     * （越南语构建 = 越南语 ✓，主版 = 中文 ✓）；GET 型接口再用
     * {@code SimpleGetProvider.langCode()} 转成 {@code vi}/{@code zh-CN}/… ✓
     *
     * <p>配置里写 {@code "auto"} 是<b>显式选择</b>，那时才回退到 auto ✓
     * （并且由 {@code SimpleGetProvider} 的 detect 兜底 ✓）。
     */
    public static String outgoingSource() {
        String s = ConfigStore.translate().outgoingSourceLanguage;
        return (s == null || s.isBlank()) ? "auto" : s;
    }

    public static String translateOutgoing(String text, String targetLanguage, List<String> extraHints) {
        long base = Math.max(1000, ConfigStore.translate().timeoutMs);
        // ★ 同入站：总预算必须大于单次上限，否则第一次就吃光、降级链形同虚设（见 budgetFor）
        long total = outgoingBudgetMs(base);
        long deadline = System.currentTimeMillis() + total;

        // ★★ 2026-10-20：简写提示改成【按这句话算】，不再把整张表塞进 prompt。
        //    Glossary.reverseHints 只在输入真的含「谢谢 / 欢迎回来 / 打得好…」时给 ty / wb / gg，
        //    最多 4 条 —— 模型没有"可罗列的清单"可抄，也就吐不出简写表。
        java.util.List<String> hints = new java.util.ArrayList<>();
        if (extraHints != null) {
            hints.addAll(extraHints);
        }
        for (String h : Glossary.reverseHints(text)) {
            if (hints.size() >= 4) {
                break;
            }
            if (!hints.contains(h)) {
                hints.add(h);
            }
        }

        // ★★ 出站也要占位符保护：你打的 @名字 / 坐标 / 链接 / 数字 不该被翻
        TextProtector.Masked masked = TextProtector.mask(text);
        final String sendText = masked.masked();
        final String originalText = text;

        for (String id : outgoingOrder()) {
            TranslateProvider p = PROVIDERS.get(id);
            ApiProvider ap = ConfigStore.find(id);
            if (ap != null && !ap.implemented) {
                LOGGER.info("[chatpatch] {} 未实现（implemented=false），跳过出站", id);
                continue;
            }
            // ★ 出站也要过能力表：目标语言是 English 时，混元能翻；但如果是越南语/日语，
            //   本地小模型翻不了，必须交给云端 —— 和入站同一套判据（同一个坑不能只填一边）
            if (ap != null && !LanguageSupport.supports(ap, targetLanguage)) {
                LOGGER.info("[chatpatch] 跳过出站 {}：{}（目标 {}）", id,
                        LanguageSupport.skipReason(ap, targetLanguage), targetLanguage);
                continue;
            }
            if (p == null || !p.ready()) {
                if (p != null) {
                    LOGGER.info("[chatpatch] 跳过出站 {}：{}", id, OpenAiCompatibleProvider.readiness(ap));
                }
                continue;
            }
            if (isCoolingDown(id)) {
                LOGGER.info("[chatpatch] {} 出站：刚被限流，冷却中，跳过（R11）", id);
                continue;
            }
            long budget = attemptBudget(deadline, base);
            if (budget <= 0) {
                LOGGER.warn("[chatpatch] 出站翻译总时限 {}ms 用完（单次上限 {}ms），不再降级",
                        total, base);
                break;
            }
            try {
                long t0 = System.currentTimeMillis();
                // ★ 每次调用现算 —— 必须这样：类加载时术语表还没读，
                //   用那个静态常量的话简写清单永远是空的（只有兜底那十几个词）。
                //
                // ★★ 2026-10-13 修：源语言【不能用 "auto"】✗
                //   用户实测：「我发出去的越南语叫我选择两个语言」——
                //   查出来是 GET 型兜底源（mymemory）收到 auto 直接报错：
                //     langpair=auto|en → 403 'AUTO' IS AN INVALID SOURCE LANGUAGE …
                //   而它有时是 HTTP 200 + 正文一句「PLEASE SELECT TWO DISTINCT LANGUAGES」✗，
                //   那句话被当成"译文"，直接发到公聊 ✗
                //
                //   现在源语言取自配置 ✓（outgoingSourceLanguage，越南语构建 = 越南语 ✓）——
                //   GET 型接口用 langCode() 转成 vi/en/… ✓，不再有 auto ✓
                //   （这本就是用户要的"严格按照配置文件"✗ 我之前漏了这一处）
                String raw = p.translate(sendText, outgoingSource(), targetLanguage, List.of(), hints,
                        outgoingStyle(), budget);
                if (raw != null && !raw.isBlank()) {
                    // ★ 回填占位符（出站同理：你打的 @名字 / 坐标 不该被翻）
                    String out = TextProtector.restore(raw, masked.tokens());
                    if (out == null) {
                        LOGGER.warn("[chatpatch] {} 出站占位符回填失败，换下一个源", id);
                        continue;
                    }
                    // ★ 六项质量校验
                    // ★★ 2026-10-20：出站最后一道闸 —— 输出是"简写表本身"就当场丢弃。
                    //
                    //   提示词修好了也不代表模型一定听话（35B 免费源尤其）。这条不靠模型自觉：
                    //   只要译文里大半是简写表里的词、而且数量明显不是一句话该有的样子，
                    //   就判为"模型在背表"，换下一个源；全都不行就返回 null → 发原文。
                    //   宁可发中文原文，也不能替用户在公聊刷 41 个缩写。
                    if (ChatStudyPipeline.looksLikeAbbrevDump(originalText, out)) {
                        LOGGER.warn("[chatpatch] 出站译文疑似简写表本身，已丢弃并换源: {}", out);
                        continue;
                    }
                    // ★ 六项质量校验（数字/脚本/长度/自我解释/退化重复）
                    String verified = TranslationVerifier.checkAndLog(
                            originalText, out, targetLanguage, "出站");
                    if (verified == null) {
                        LOGGER.warn("[chatpatch] 出站译文未通过校验，换下一个源");
                        continue;
                    }
                    clearRateLimit(id);
                    LOGGER.info("[chatpatch] 出站翻译成功 {} ({}ms)", id, System.currentTimeMillis() - t0);
                    return verified;
                }
            } catch (Exception ex) {
                if (isRateLimited(ex)) {
                    markRateLimited(id, ConfigStore.translate());
                }
                LOGGER.warn("[chatpatch] 出站翻译 {} 失败: {}", id, ex.toString());
            }
        }
        return null;
    }

    /**
     * 出站的尝试顺序：{@code outgoingProvider} 置顶，其余按原顺序接上。
     *
     * <p>出站快慢直接影响体验（要等译文回来才发得出去），所以单独给一档配置。
     * 实测大模型 3.9~23.9 秒，mymemory 0.43~0.7 秒。
     */
    private static List<String> outgoingOrder() {
        List<String> order = new ArrayList<>();
        String preferred = ConfigStore.translate().outgoingProvider;
        if (preferred != null && !preferred.isBlank() && PROVIDERS.containsKey(preferred)) {
            order.add(preferred);
        }
        for (String id : order()) {
            if (!order.contains(id)) {
                order.add(id);
            }
        }
        return order;
    }

    /**
     * 当前源置顶，其余按原顺序接在后面。
     */
    /**
     * 按引用从配置里找服务商（找不到返回 null）。
     *
     * <p>★ 2026-11-01：{@code id} 现在是<b>槽位号</b>("3") ✗ —— 只按名字找会全返回 null ✗，
     * 于是翻译链里"语言能力过滤"那一关会把<b>所有源</b>都跳过 ✗。
     * 现在<b>先当槽位号</b> ✓，再退回名字 ✓（老调用不出错 ✓）
     */
    private static ApiProvider findProvider(TranslateConfig cfg, String ref) {
        if (cfg == null || cfg.providers == null || ref == null) {
            return null;
        }
        String key = ref.trim();
        if (key.matches("\\d+")) {
            int slot = Integer.parseInt(key);
            for (ApiProvider p : cfg.providers) {
                if (p != null && p.slot == slot) {
                    return p;
                }
            }
        }
        for (ApiProvider p : cfg.providers) {
            if (p != null && key.equals(p.name)) {
                return p;
            }
        }
        return null;
    }
    private static List<String> order() {
        List<String> order = new ArrayList<>();
        if (active != null && !active.isBlank() && PROVIDERS.containsKey(active)) {
            order.add(active);
        }
        // 自动顺序：在线（名字含“在线”）→ 本地 → 免费/内置（bili、mymemory 等）
        List<String> rest = new ArrayList<>(PROVIDERS.keySet());
        rest.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        for (String id : rest) {
            if (!order.contains(id)) {
                order.add(id);
            }
        }
        return order;
    }

    /**
     * 自动降级顺序的权重：在线 0，本地 1，免费 2，其它 3。
     *
     * <p>★ 2026-11-01：参数从"名字"改成"槽位号"了 ✗ —— 所以这里要先<b>查出那一条 provider</b>，
     * 再用它的 {@code title/name} 判权重 ✓。<b>权重规则一个字没改</b> ✓（行为不变 ✓）
     */
    private static int rank(String id) {
        TranslateProvider p = PROVIDERS.get(id);
        String s = "";
        if (p != null) {
            ApiProvider cfg = providerCfg(p);
            if (cfg != null) {
                s = (nz(cfg.title) + " " + nz(cfg.name)).toLowerCase(java.util.Locale.ROOT);
            }
        }
        if (s.isBlank()) {
            s = nz(id).toLowerCase(java.util.Locale.ROOT);   // 兜底：万一是老的名字 key ✓
        }
        if (s.contains("在线") || s.contains("online") || s.contains("deepseek") || s.contains("openai")) {
            return 0;
        }
        if (s.contains("本地") || s.contains("local")) {
            return 1;
        }
        if (s.contains("bili") || s.contains("mymemory") || s.contains("免费")) {
            return 2;
        }
        return 3;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 取一条 provider 背后的配置（两个实现都把 cfg 存在字段里 ✓）。 */
    private static ApiProvider providerCfg(TranslateProvider p) {
        if (p instanceof OpenAiCompatibleProvider) {
            return ((OpenAiCompatibleProvider) p).cfg;
        }
        if (p instanceof SimpleGetProvider) {
            return ((SimpleGetProvider) p).cfg;
        }
        return null;
    }

    /** 这条源是不是"免费源"（bili / mymemory / 免费槽位…）—— 免费 API 总开关用它 ✓。 */
    public static boolean looksFree(ApiProvider pc) {
        if (pc == null) {
            return false;
        }
        String nm = (nz(pc.title) + " " + nz(pc.name)).toLowerCase(java.util.Locale.ROOT);
        return nm.contains("bili") || nm.contains("mymemory") || nm.contains("免费");
    }

    /**
     * 给日志/界面用的显示名：{@code title} 优先 → {@code name} → {@code #槽位号}。
     *
     * <p>★ 身份是槽位号 ✓，但日志里不能只显示 "3" ✗ —— 那样排查时看不出是哪一家 ✓
     */
    public static String displayName(ApiProvider pc) {
        if (pc == null) {
            return "?";
        }
        if (pc.title != null && !pc.title.isBlank()) {
            return pc.title;
        }
        if (pc.name != null && !pc.name.isBlank()) {
            return pc.name;
        }
        return "#" + pc.slot;
    }

    /**
     * 并发自检所有源。必须在后台线程调用（会阻塞到全部结束），结果已按配置顺序排好。
     */
    public static Map<String, ProbeResult> checkAll() {
        Map<String, ProbeResult> raw = new LinkedHashMap<>();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (Map.Entry<String, TranslateProvider> e : PROVIDERS.entrySet()) {
            tasks.add(CompletableFuture.runAsync(() -> {
                ProbeResult r;
                try {
                    r = e.getValue().probe();
                } catch (Throwable t) {
                    r = ProbeResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage(), 0);
                }
                synchronized (raw) {
                    raw.put(e.getKey(), r);
                }
            }));
        }
        try {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } catch (Exception ignored) {
            // 单个任务内部已兜底
        }
        Map<String, ProbeResult> ordered = new LinkedHashMap<>();
        for (String id : PROVIDERS.keySet()) {
            ordered.put(id, raw.getOrDefault(id, ProbeResult.fail("未执行", 0)));
        }
        lastCheck = ordered;
        return ordered;
    }

    /** 自检结果渲染成多行文本，聊天框和将来的文本框共用这一份。 */
    public static String renderCheck(Map<String, ProbeResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("§6[chatpatch] §f翻译源自检 §8(当前: ").append(active).append(")");
        for (Map.Entry<String, ProbeResult> e : results.entrySet()) {
            ApiProvider pc = ConfigStore.find(e.getKey());
            String title = pc == null ? e.getKey() : pc.displayTitle();
            sb.append('\n').append(e.getValue().line(e.getKey(), title));
        }
        return sb.toString();
    }

    public static String statusLine() {
        TranslateConfig cfg = ConfigStore.translate();
        StringBuilder sb = new StringBuilder();
        sb.append("§6[chatpatch] §7翻译: ").append(cfg.enabled ? "§a开" : "§c关");
        sb.append(" §7| 当前源: §f").append(active);
        sb.append(" §7| 语言: §f").append(cfg.sourceLanguage).append(" §7-> §f").append(cfg.targetLanguage);
        sb.append("\n§7API 列表（顺序=优先级）:");
        int i = 0;
        for (Map.Entry<String, TranslateProvider> e : PROVIDERS.entrySet()) {
            i++;
            TranslateProvider p = e.getValue();
            boolean isActive = e.getKey().equals(active);
            ApiProvider pc = ConfigStore.find(e.getKey());
            String title = pc == null ? e.getKey() : pc.displayTitle();
            sb.append("\n  §8").append(i).append(". ").append(isActive ? "§a→ " : "§7  ")
                    .append(p.ready() ? "§a●" : "§c○")
                    .append(" §f").append(title).append(" §8[").append(e.getKey()).append("] §7")
                    .append(p.describe());
        }
        return sb.toString();
    }
}
