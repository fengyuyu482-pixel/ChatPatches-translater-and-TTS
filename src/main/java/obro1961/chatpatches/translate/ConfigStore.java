package obro1961.chatpatches.translate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import obro1961.chatpatches.ChatPatches;
import obro1961.chatpatches.tts.ReadAloudConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置的读写。
 *
 * <p>现在<b>全部放文件</b>：
 * <pre>
 * config/chatpatches/translate.json   翻译
 * config/chatpatches/readAloud.json   朗读
 * </pre>
 *
 * <p>为什么不塞进 Chat Patches 自己的 {@code chatpatches.json}：它的配置系统只支持
 * 布尔/整数/字符串/字符串列表，<b>塞不下"对象列表"</b>（每个服务商有 6-7 个字段）。
 * 以后要进界面，用 YACL 的分组做，再搬过去。
 */
public final class ConfigStore {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 配置目录：config/chatpatches/ */
    private static Path dir;

    private static TranslateConfig translate;
    private static ReadAloudConfig readAloud;
    private static obro1961.chatpatches.chatlog.RecordConfig record;

    private ConfigStore() {
    }

    /** 由 mod 初始化时调用一次。 */
    public static void init(Path configDir) {
        dir = configDir.resolve("chatpatches");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            ChatPatches.LOGGER.warn("[chatpatch] 建配置目录失败: {}", e.toString());
        }
        migrate("1-translate.json", "translate.json");
        translate = load("1-translate.json", TranslateConfig.class, ConfigStore::defaultTranslate);
        migrateTranslate(translate);
        assignSlots(translate);   // ★ 分配稳定槽位号 + 把 activeProvider/outgoingProvider 从"名字"迁到槽位 ✓
        Language.setCustom(translate.languages);   // M6：用户自定义语言别名
        migrate("2-readaloud.json", "readAloud.json");
        readAloud = load("2-readaloud.json", ReadAloudConfig.class, ConfigStore::defaultReadAloud);
        migrateReadAloudDefaults();
        assignSlots(readAloud);   // ★ 朗读侧也发号 ✓（翻译/朗读两边同一套身份规则 ✓）
        // ★ 5-record.json（D5 五份的最后一份）：之前一直缺，灌回条数写死在 ChatPatches 里。
        record = load("5-record.json", obro1961.chatpatches.chatlog.RecordConfig.class,
                obro1961.chatpatches.chatlog.RecordConfig::new);
        ensureReadme();
    }

    /**
     * 写一份 {@code 说明.txt}（只在新装/缺失时写，不覆盖用户改过的）。
     *
     * <p>D5/R10 要的就这一件事：<b>只靠文件名和这份对照表，就知道该改哪个文件</b>。
     * 以前五个文件散着，用户只能猜。
     */
    private static void ensureReadme() {
        Path p = dir.resolve("说明.txt");
        if (Files.isRegularFile(p)) {
            return;
        }
        String text = String.join(System.lineSeparator(),
                "想干什么                              改哪个文件",
                "------------------------------------  ------------------",
                "换翻译语言 / 填 API key              1-translate.json",
                "关掉朗读 / 换音色 / 音量              2-readaloud.json",
                "时间戳格式 / 名字颜色 / 聊天留多少条   3-display.json",
                "加缩写、术语、不翻译的词              4-glossary.json",
                "进服灌回多少条 / 分界线               5-record.json",
                "聊天记录存在哪                        chatlogs\\ 文件夹",
                "",
                "改完要重启游戏才生效（当前版本没有热加载命令；/chatstudy reload 尚未实现）。");
        try {
            Files.writeString(p, text, StandardCharsets.UTF_8);
            ChatPatches.LOGGER.info("[chatpatch] 生成配置说明：{}", p);
        } catch (Exception e) {
            ChatPatches.LOGGER.warn("[chatpatch] 写说明.txt 失败: {}", e.toString());
        }
    }

    /**
     * <b>给每条服务商分配稳定槽位号，并把配置里的"名字引用"迁到"槽位号"</b>。
     *
     * <p>★ 用户要求「换成槽位」的落地处。三条后果一次解决（见 {@link ApiProvider#slot}）：
     * <ul>
     *   <li>名字空着也能用 ✓（身份是 slot ✓，不再看 name）</li>
     *   <li>改中文名随便改 ✓（{@code activeProvider} 存的是号 ✓）</li>
     *   <li>看门狗用 {@code title} 显示 ✓（不用认名字 ✓）</li>
     * </ul>
     *
     * <p><b>规则</b>：
     * <ol>
     *   <li>{@code slot <= 0} 的（新加的、老配置里的）→ 取"现有最大值 + 1" ✓</li>
     *   <li>某个号一旦分配，<b>删掉那条也不会把号给别人</b> ✓（留洞 ✓ 下次取 max+1 自然跳过）</li>
     *   <li>{@code activeProvider} / {@code outgoingProvider} 里如果是<b>名字</b> → 换成对应槽位号 ✓；
     *       已经是数字、或找不到 → 原样不动 ✓（让下游自己兜底）</li>
     * </ol>
     *
     * <p>只在真的改了东西时回写文件 ✓（不无脑写盘）
     */
    public static void assignSlots(TranslateConfig cfg) {
        if (cfg == null || cfg.providers == null) {
            return;
        }
        // ★ 发号基准 = max(现有最大号, 历史最高水位) ✗ —— 只取现有最大号会复用删掉的号 ✗
        //   （自测抓到的：7/8/9 删掉 9 → 现有最大变回 8 → 新加的又拿到 9 → 老配置"用 9 号"指错人 ✗）
        int max = Math.max(0, cfg.nextSlot);
        for (ApiProvider p : cfg.providers) {
            if (p != null && p.slot > max) {
                max = p.slot;
            }
        }
        boolean changed = false;
        for (ApiProvider p : cfg.providers) {
            if (p == null) {
                continue;
            }
            if (p.slot <= 0) {
                p.slot = ++max;
                changed = true;
                ChatPatches.LOGGER.info("[chatpatch] 分配槽位 #{} → {}",
                        p.slot, (p.title == null || p.title.isBlank()) ? p.name : p.title);
            }
        }
        // ★ 水位写回（只增不减 ✓）：删掉某条后，它的号也不会再发出去 ✓
        if (cfg.nextSlot != max) {
            cfg.nextSlot = max;
            changed = true;
        }
        String a = migrateProviderRef(cfg.providers, cfg.activeProvider);
        if (a != null) {
            ChatPatches.LOGGER.info("[chatpatch] activeProvider 迁移：{} → 槽位 {}", cfg.activeProvider, a);
            cfg.activeProvider = a;
            changed = true;
        }
        String o = migrateProviderRef(cfg.providers, cfg.outgoingProvider);
        if (o != null) {
            ChatPatches.LOGGER.info("[chatpatch] outgoingProvider 迁移：{} → 槽位 {}", cfg.outgoingProvider, o);
            cfg.outgoingProvider = o;
            changed = true;
        }
        if (changed) {
            saveTranslate();
        }
    }

    /**
     * 通用发号：给 {@code slot <= 0} 的补齐 ✓，返回新的历史水位 ✓。
     *
     * <p>翻译和朗读共用这一份 ✓（两边规则必须一样 ✗ 否则又是两套）
     */
    private static int assignSlotsTo(java.util.List<ApiProvider> list, int highWater) {
        int max = Math.max(0, highWater);
        if (list == null) {
            return max;
        }
        for (ApiProvider p : list) {
            if (p != null && p.slot > max) {
                max = p.slot;
            }
        }
        for (ApiProvider p : list) {
            if (p == null) {
                continue;
            }
            if (p.slot <= 0) {
                p.slot = ++max;
                ChatPatches.LOGGER.info("[chatpatch] 分配槽位 #{} → {}",
                        p.slot, (p.title == null || p.title.isBlank()) ? p.name : p.title);
            }
        }
        return max;
    }

    /** 朗读侧的槽位分配 + activeProvider 名字→号迁移 ✓ */
    public static void assignSlots(ReadAloudConfig cfg) {
        if (cfg == null || cfg.providers == null) {
            return;
        }
        int max = assignSlotsTo(cfg.providers, cfg.nextSlot);
        boolean changed = cfg.nextSlot != max;
        cfg.nextSlot = max;
        String a = migrateProviderRef(cfg.providers, cfg.activeProvider);
        if (a != null) {
            ChatPatches.LOGGER.info("[chatpatch] 朗读 activeProvider 迁移：{} → 槽位 {}", cfg.activeProvider, a);
            cfg.activeProvider = a;
            changed = true;
        }
        if (changed) {
            save("2-readaloud.json", cfg);
        }
    }

    /**
     * 把"名字引用"换成"槽位号引用"。
     *
     * @return 新的引用（槽位号字符串）；已经是数字 / 找不到 / 空 → {@code null}（表示不需要改）
     */
    public static String migrateProviderRef(java.util.List<ApiProvider> list, String ref) {
        if (ref == null || ref.isBlank() || ref.matches("\\d+")) {
            return null;   // 空 / 已经是槽位号 ✓
        }
        for (ApiProvider p : list) {
            if (p == null || p.slot <= 0) {
                continue;
            }
            if (ref.equals(p.name) || ref.equals(p.title)) {
                return String.valueOf(p.slot);
            }
        }
        return null;   // 认不出来 → 保持原样，让下游自己兜底 ✓
    }

    /**
     * D9 一次性默认值迁移：把老文件里的 {@code onlyWhenTranslated: true} 改成 {@code false}。
     *
     * <p><b>为什么需要</b>：D9 把<b>代码默认值</b>改成了 false，但老用户的
     * {@code readAloud.json} 里早就写着 {@code true} —— 那是<b>旧默认值被写成文件</b>的结果，
     * 不是用户的选择。按 C5「配置说了算」，文件里的值赢 → 默认值改了等于没改。
     * 实测日志：`自动朗读：开（... 只读翻译过的消息 = true ...）`。
     *
     * <p><b>怎么判"是旧文件"</b>：看文件里<b>有没有 {@code schemaVersion} 这个键</b>。
     * 不能用字段值判 —— Gson 缺键时会保留字段初始值（10-13 的坑），
     * 那样老文件会"看起来已经是新版"。
     *
     * <p>补上键之后就不会再跑：用户以后手改 true 也保留（C5）。
     */
    private static void migrateReadAloudDefaults() {
        Path p = dir.resolve("2-readaloud.json");
        if (!Files.isRegularFile(p)) {
            return;
        }
        try {
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            if (raw.contains("\"schemaVersion\"")) {
                return;   // 新文件（已经迁移过）
            }
            // 老字段 onlyWhenTranslated 已删除：按它的值等价搬到新三段式
            boolean oldTrue = raw.matches("(?s).*\"onlyWhenTranslated\"\\s*:\\s*true.*");
            boolean changed = false;
            if (oldTrue) {
                readAloud.readOriginal = false;
                readAloud.readTranslation = true;
                changed = true;
            }
            readAloud.schemaVersion = 2;
            save("2-readaloud.json", readAloud);
            if (changed) {
                ChatPatches.LOGGER.info("[chatpatch] 朗读配置迁移：老 onlyWhenTranslated=true → 只读译文（readOriginal=false, readTranslation=true）"
                        + "；想改回读原文请在朗读页打开「读原文」");
            } else {
                ChatPatches.LOGGER.info("[chatpatch] 朗读配置补上 schemaVersion=2（D9 迁移标记）");
            }
        } catch (Exception e) {
            ChatPatches.LOGGER.warn("[chatpatch] 朗读配置 D9 迁移失败（继续用当前值）: {}", e.toString());
        }
    }

    // 说明：配置【只在启动时读一次】。
    // 用户在文件里改完要生效 —— 重启游戏，或打 `/chatstudy reload`。
    // （曾经做过"存盘 1 秒自动生效"的热加载，用户明确要求改回重启生效：
    //   编辑过程中间态被读进去太意外，重启更可预期。）

    /**
     * 修掉老配置里那个<b>用不了的模型名</b>。
     *
     * <p>默认值曾经写成 {@code deepseek-flash} —— 那是个"思考型"模型：推理会把 token 烧光。
     * 实测同一台机器同一把 key：
     * <pre>
     *   deepseek-flash 不设上限 : 17984 / 22793 / 36494 ms，可见答案 6 个字却烧了 2872 / 3686 / 6416 token
     *   deepseek-flash 加上限   : 返回【空】
     *   deepseek-chat           : 577 ms，答案正确
     * </pre>
     * 于是每次调用都超时 → 吃光总预算 → 降级也没机会 → 表现就是"翻译总是不出来"。
     *
     * <p><b>只改官方 DeepSeek 的 {@code deepseek-flash}</b> —— 别的厂商/中转如果有同名模型，
     * 不动它。改动会写回配置文件并留一行日志。
     */
    /**
     * 目标语言要不要纠正。
     *
     * <p>规则：<b>当前值 == 硬编码默认（{@code TranslateConfig} 里的「简体中文」）
     * 且 本构建默认不是它</b> → 纠正成本构建默认。
     *
     * <p>换成人话：换上一个"默认越南语"的 jar 之后，那些还停留在出厂中文默认值的配置
     * 会被自动纠正 ✓；而用户自己设过语言的（英文/日语/越南语…）一律不碰 ✓；
     * **主版构建里这条永远不触发**（它的构建默认就是简体中文）✓
     *
     * <p>抽成 public static 是为了能单独测 —— 判错会把用户的语言设置改掉，
     * 而且"配置迁移"这类代码错一次可能几个月没人发现。
     *
     * @return 新的目标语言；不需要纠正返回 {@code null}
     */
    public static String migrateTargetLanguage(String current, String buildDefault, String hardcodedDefault) {
        if (current == null || current.isBlank() || buildDefault == null || buildDefault.isBlank()) {
            return null;
        }
        if (current.equals(buildDefault)) {
            return null;   // 已经就是本构建的默认
        }
        if (hardcodedDefault != null && current.equals(hardcodedDefault)) {
            return buildDefault;   // 停在出厂默认 → 按本构建的默认纠正
        }
        return null;       // 用户自己设过 → 不碰
    }

    /**
     * mymemory 的地址曾经<b>写死 {@code langpair=en|zh-CN}</b> ✗ —— 修掉它。
     *
     * <p>为什么必须迁移（而不是改默认值就完事）：默认值只对**新建的配置**生效 ✗，
     * 老配置里那行死值会一直在。而它的危害很隐蔽：
     * <pre>
     *   首选源（B站）正常 → 根本轮不到 mymemory → 看不出问题 ✓
     *   B站 一限流(503)   → 降级到 mymemory → 它永远翻中文 ✗
     * </pre>
     * 用户看到的是"越南语版把英语翻成了中文"，而根因在一个兜底源的 URL 里 ✗。
     *
     * <p>改成 {@code {langpair}} 之后它跟着 translate.json 的目标语言走 ✓。
     *
     * @return 修好的 endpoint；不需要修返回 {@code null}
     */
    public static String migrateMymemoryEndpoint(String endpoint) {
        if (endpoint == null || !endpoint.contains("mymemory")) {
            return null;
        }
        if (endpoint.contains("langpair={langpair}")) {
            return null;   // 已经是新写法
        }
        String fixed = endpoint
                .replace("langpair=en%7Czh-CN", "langpair={langpair}")
                .replace("langpair=en|zh-CN", "langpair={langpair}");
        return fixed.equals(endpoint) ? null : fixed;
    }

    public static void migrateTranslate(TranslateConfig cfg) {
        if (cfg == null || cfg.providers == null) {
            return;
        }

        // ❻ 语言字段：★★ 2026-10-14 **全部撤掉自动纠正** ✗✗
        //
        //    【为什么撤】用户实测：「还是越南语」——
        //    他在越南语 jar 的实例里把 targetLanguage 改成「简体中文」✗，
        //    下次启动被【自动迁移改回越南语】✗，日志里还写着
        //    「不想要就自己改 translate.json 的 targetLanguage」——**但改了没用** ✗✓
        //
        //    根因：自动纠正**分不出**「这是出厂默认值」还是「用户明确要中文」✗。
        //    它跟用户的两条明确要求直接冲突：
        //      「不搞自动检测语言了，改成严格按照配置文件吧，这样不容易出bug」
        //      「把配置的语言跟对照表锁死，以免出bug」
        //    ⇒ **配置至上** ✓：翻译成什么语言，由 translate.json 说了算，程序不许替用户改 ✗
        //
        //    代价（如实说）：换上越南语 jar 之后，一份【旧的出厂中文配置】不会再自动变越南语 ✗。
        //    替代方案更可预测 ✓：越南语分发包里**带一份写好「越南语」的 translate.json** ✓
        //    （zip 里就是这么做的 ✓），用户放进去即可，不存在"猜"的环节 ✓。
        //
        //    ★ 下面这三个字段【只在缺失时补默认】✓（新字段/字段被删的情况 ✓），
        //      绝不根据"它等于出厂值"去改用户已写下的内容 ✗。
        if (cfg.sourceLanguage == null || cfg.sourceLanguage.isBlank()) {
            cfg.sourceLanguage = defaultTranslate().sourceLanguage;
            ChatPatches.LOGGER.info("[chatpatch] 配置迁移：入站源语言缺失 → {}", cfg.sourceLanguage);
            saveTranslate();
        }
        if (cfg.targetLanguage == null || cfg.targetLanguage.isBlank()) {
            cfg.targetLanguage = defaultTranslate().targetLanguage;
            ChatPatches.LOGGER.info("[chatpatch] 配置迁移：目标语言缺失 → {}", cfg.targetLanguage);
            saveTranslate();
        }
        if (cfg.outgoingSourceLanguage == null || cfg.outgoingSourceLanguage.isBlank()) {
            cfg.outgoingSourceLanguage = defaultTranslate().outgoingSourceLanguage;
            ChatPatches.LOGGER.info("[chatpatch] 配置迁移：出站源语言缺失 → {}", cfg.outgoingSourceLanguage);
            saveTranslate();
        }

        // ❺ 单次超时：老默认 8000 → 新默认 4000
        //
        //    ★ 只在【正好等于旧默认 8000】时改 —— 用户自己调过的（网络差设了 15000 之类）一律不碰 ✓
        //    为什么改：B站免费源开始限流（大量 request timed out），而它健康时只要 300~1000ms ✗ ——
        //    给它 8 秒纯属浪费：每条消息先干等 8 秒才降级，用户看到的是"半天不出译文"，像坏了 ✗
        if (cfg.timeoutMs == 8000) {
            cfg.timeoutMs = new TranslateConfig().timeoutMs;
            ChatPatches.LOGGER.info("[chatpatch] 配置迁移：单次超时 8000 → {}ms"
                            + "（8000 是旧默认；免费源限流时等 8 秒才降级太久。自己调过的值不会被动）",
                    cfg.timeoutMs);
            saveTranslate();
        }

        // ❹ mymemory 的地址曾经写死中文 → 改成跟着目标语言走（越南语用户的关键修复）
        for (ApiProvider p : cfg.providers) {
            String fixed = p == null ? null : migrateMymemoryEndpoint(p.endpoint);
            if (fixed != null) {
                ChatPatches.LOGGER.info("[chatpatch] 配置迁移：{} 的地址里写死了 langpair=en|zh-CN ✗ —— "
                        + "它会让【非中文目标】的降级翻译永远出中文。已改成 {{langpair}} 占位符，"
                        + "现在跟着 targetLanguage 走", p.name);
                p.endpoint = fixed;
                saveTranslate();
            }
        }

        // ❸ 目标语言：把"从别的构建带过来的旧默认值"纠正成本构建的默认值。
        //
        //    ★ 为什么必须做：{@code defaultTranslate()} 只在【配置文件不存在】时才生效 ✗。
        //      用户以前启动过、配置里写着「简体中文」，那么换上越南语 jar 之后
        //      配置里的 简体中文 照样赢 → **界面还是中文**，看起来像 jar 没生效 ✗
        //      （用户实测：「我用的是工作区的那个，但是还是中文」）。
        //
        //    ★ 为什么这样写是安全的：
        //      - 只有【当前值正好等于硬编码默认（简体中文）】且【本构建默认不是它】时才动；
        //        用户自己设成英文/日语/越南语的，一律不碰 ✓
        // ❸ ★★ 2026-10-14 **撤掉**目标语言的自动纠正 ✗✗（原来在这儿调用 migrateTargetLanguage）
        //
        //    原来是这么想的：{@code defaultTranslate()} 只在【配置文件不存在】时生效 ✗，
        //    所以用户以前启动过、配置里写着「简体中文」，换上越南语 jar 之后照样是中文 ✗，
        //    看起来像 jar 没生效（用户实测：「我用的是工作区的那个，但是还是中文」）——
        //    于是做了"出厂默认值 → 本构建默认"的自动纠正。
        //
        //    【为什么现在必须撤】它**分不出**「出厂默认值」和「用户明确要中文」✗。
        //    实测现场（越南语实例）：
        //      · 用户把 targetLanguage 改成「简体中文」✗
        //      · 下次启动被自动改回「越南语」✗
        //      · 日志还写着「不想要就自己改 translate.json」——**改了没用** ✗✓
        //    这跟用户两条明确要求直接冲突：
        //      「不搞自动检测语言，严格按照配置文件」「把配置的语言跟对照表锁死」
        //    ⇒ **配置至上** ✓：翻成什么语言由 translate.json 说了算，程序不许替用户改 ✗
        //
        //    代价（如实说）：换上越南语 jar 后，一份【旧的出厂中文配置】不会再自动变越南语 ✗。
        //    替代方案更可预测 ✓：越南语分发包里**带一份写好「越南语」的 translate.json** ✓
        //    （zip 里就是这么做的 ✓）—— 加上面 ❻ 那段"缺失才补"的兜底 ✓，不存在猜的环节 ✓。
        //
        //    ★ migrateTargetLanguage() 函数本身保留 ✓（逻辑仍正确、有自测 ✓），
        //      只是**不再被调用** —— 它是"如果将来要做一次性迁移"的现成材料 ✓。
        //        

        // ① 补上"免费：B站 Index-Translate"这个源（老配置里没有）
        //
        //    为什么要自动补：这个源**免费、免注册、免 Key**，实测质量还比通用模型好（见 defaultTranslate）。
        //    老配置里没有它，用户就只能继续付费。
        //    只补一次：已经有一个指向 index-translate.bilibili.com 的源就不动。
        //
        //    ⚠️ 它会把聊天内容发到 B站服务器 —— 和用 deepseek 是同一类行为，
        //       不是新增的隐私面；不想要就把这个源 enabled 改成 false。
        boolean hasBili = false;
        for (ApiProvider p : cfg.providers) {
            if (p != null && p.baseUrl != null && p.baseUrl.contains("index-translate.bilibili.com")) {
                hasBili = true;
                break;
            }
        }
        if (!hasBili) {
            ApiProvider bili = new ApiProvider();
            bili.name = "bili";
            bili.title = "免费：B站 Index-Translate";
            bili.baseUrl = "https://index-translate.bilibili.com/v1";
            bili.model = "Index-Translate-35B-A3B";
            cfg.providers.add(0, bili);
            cfg.activeProvider = "bili";
            // ★ 出站是【另一个字段】—— 上次只改了 activeProvider，出站还挂在 mymemory 上，
            //   于是译文质量突然变得很烂（实测输出 `Hello ando` / `I speak English or Chinese n`
            //   这种被截断的东西）。两个字段都要设，这才是"换个源"的完整动作。
            if (cfg.outgoingProvider == null || cfg.outgoingProvider.isBlank()
                    || "mymemory".equalsIgnoreCase(cfg.outgoingProvider)) {
                cfg.outgoingProvider = "bili";
            }
            ChatPatches.LOGGER.info("[chatpatch] 配置迁移：加入免费源「B站 Index-Translate」并设为首选"
                    + "（入站 activeProvider + 出站 outgoingProvider 都改；免注册免 Key；"
                    + "不想要就把 translate.json 里 bili 的 enabled 改成 false）");
            saveTranslate();
        }
        // 已经加过 bili、但出站还指着 mymemory 的（上一版迁移漏掉的那半边）—— 补一次
        if (hasBili && "mymemory".equalsIgnoreCase(cfg.outgoingProvider)) {
            cfg.outgoingProvider = "bili";
            ChatPatches.LOGGER.info("[chatpatch] 配置迁移：出站翻译源 mymemory → bili"
                    + "（出站是独立字段，上一版迁移只改了入站）");
            saveTranslate();
        }
        // 老配置里没有的字段补默认值 —— 缺字段 Gson 会留 null，
        // 代码若把 null 当成"用户显式留空"，功能就会静默失效。
        if (cfg.channelCommands == null) {
            cfg.channelCommands = new java.util.ArrayList<>(TranslateConfig.defaultChannelCommands());
        }
        if (cfg.systemKeywords == null) {
            cfg.systemKeywords = new java.util.ArrayList<>(TranslateConfig.defaultSystemKeywords());
        }
        boolean changed = false;
        for (ApiProvider p : cfg.providers) {
            if (p == null || !"deepseek-flash".equalsIgnoreCase(p.model)) {
                continue;
            }
            if (p.baseUrl == null || !p.baseUrl.contains("api.deepseek.com")) {
                continue;   // 不是官方端点，可能是用户特意选的，不碰
            }
            p.model = "deepseek-chat";
            changed = true;
            ChatPatches.LOGGER.info("[chatpatch] 配置迁移：{} 的模型 deepseek-flash → deepseek-chat"
                    + "（deepseek-flash 是思考型模型，推理会烧光 token、做不了聊天翻译）", p.name);
        }
        if (changed) {
            saveTranslate();
        }
    }

    /** 给翻译层用。 */
    /**
     * 一次性迁移：把代码内置的旧服务商搬进界面管理的条目（2026-10-27）。
     *
     * <p>背景：早期内置 {@code deepseek} / {@code local} 等条目，后加的界面只编辑
     * {@code 在线} / {@code 本地} / {@code 免费槽位1}。结果"能用的条目界面上看不见"。
     * 这里把旧条目就地改名并转移字段，之后界面就是唯一入口。
     */
    private static void migrateLegacyProviders(TranslateConfig cfg) {
        if (cfg == null || cfg.providers == null) {
            return;
        }
        boolean changed = false;
        changed |= renameIfMissing(cfg, "deepseek", "在线");
        changed |= renameIfMissing(cfg, "local", "本地");
        if (changed) {
            saveTranslate();
            ChatPatches.LOGGER.info("[chatpatch] 服务商已迁移到界面条目：{}",
                    cfg.providers.stream().map(p -> p.name).toList());
        }
    }

    /** 旧名字存在、新名字不存在 → 直接改名保留字段。 */
    private static boolean renameIfMissing(TranslateConfig cfg, String oldName, String newName) {
        ApiProvider oldP = null;
        boolean hasNew = false;
        for (ApiProvider p : cfg.providers) {
            if (p == null) {
                continue;
            }
            if (oldName.equals(p.name)) {
                oldP = p;
            }
            if (newName.equals(p.name)) {
                hasNew = true;
            }
        }
        if (oldP == null || hasNew) {
            return false;
        }
        oldP.name = newName;
        oldP.title = newName;
        // ★ 2026-11-01：身份改成槽位号后，**activeProvider 里存的是数字** ✓
        //   ⇒ 改名时**不能再**去动 activeProvider ✗（那会把数字写成名字，直接失配 ✗）
        //   只有老配置里还是"名字"的情况才跟着改 ✓（一次性兼容 ✓）
        if (!oldName.matches("\\d+") && oldName.equals(cfg.activeProvider)) {
            cfg.activeProvider = newName;
        }
        return true;
    }
    public static TranslateConfig translate() {
        if (translate == null) {
            translate = defaultTranslate();
        }
        migrateLegacyProviders(translate);
        return translate;
    }

    /** 给朗读层用。 */
    public static ReadAloudConfig readAloud() {
        if (readAloud == null) {
            readAloud = defaultReadAloud();
        }
        return readAloud;
    }

    /**
     * 给记录层用（{@code 5-record.json}：灌回条数 / 历史分界 / 删除策略 / debug）。
     *
     * <p>没加载过就给默认值 —— 不写盘（写盘发生在 {@code init} 或界面保存时），
     * 免得自测里凭空造出一个用户的配置文件。
     */
    public static obro1961.chatpatches.chatlog.RecordConfig record() {
        if (record == null) {
            record = new obro1961.chatpatches.chatlog.RecordConfig();
        }
        return record;
    }

    /** 保存记录层配置。 */
    public static void saveRecord() {
        save("5-record.json", record());
    }
    /** 保存翻译配置。 */
    public static void saveTranslate() {
        save("1-translate.json", translate());
    }

    /** 保存朗读配置。 */
    public static void saveReadAloud() {
        save("2-readaloud.json", readAloud());
    }

    /** 配置目录（术语表等也放这里）。 */
    public static Path dir() {
        return dir;
    }

    /** 按 name 找服务商（翻译用）；找不到返回 null。 */
    /**
     * 按引用找服务商：<b>先当槽位号找</b> ✓，再退回当名字找（兼容老调用）✓。
     *
     * <p>★ 2026-11-01：身份改槽位号后，调用方传进来的 {@code id} 是 "3" 这种数字 ✗，
     * 只按名字找会全部返回 null ✗ → 翻译链里"语言能力过滤"那一关会把所有源都跳过 ✗。
     * 所以这里两样都认 ✓（行为对老调用不变 ✓）
     */
    public static ApiProvider find(String ref) {
        if (ref == null) {
            return null;
        }
        String key = ref.trim();
        // ① 槽位号
        if (key.matches("\\d+")) {
            int slot = Integer.parseInt(key);
            for (ApiProvider p : translate().providers) {
                if (p != null && p.slot == slot) {
                    return p;
                }
            }
        }
        // ② 老路子：按名字（兼容）
        for (ApiProvider p : translate().providers) {
            if (p != null && key.equals(p.name)) {
                return p;
            }
        }
        return null;
    }

    /** 按 name 找<b>能朗读</b>的服务商；找不到返回 null。 */
    public static ApiProvider findSpeak(String ref) {
        if (ref == null) {
            return null;
        }
        String key = ref.trim();
        // ★ 2026-11-01：朗读侧也改成槽位号了 ✓ —— 两种都认（老配置里还是名字 ✓）
        for (ApiProvider p : readAloud().providers) {
            if (p == null || !p.canSpeak()) {
                continue;
            }
            if (key.matches("\\d+") ? p.slot == Integer.parseInt(key) : key.equals(p.name)) {
                return p;
            }
        }
        return null;
    }

    /** 朗读引擎的优先级顺序：当前选中的排第一，其余按列表顺序跟在后面（自动降级）。 */
    public static List<ApiProvider> speakOrder() {
        List<ApiProvider> list = order(readAloud().providers, readAloud().activeProvider);
        // 本地 HTTP 引擎（有地址）优先于系统语音：用户配了 GPT-SoVITS 就是要用它
        list.sort((a, b) -> Integer.compare(speakRank(a), speakRank(b)));
        return list;
    }

    /** 0 = 在线（公网地址）；1 = 本地 HTTP；2 = 系统语音；3 = 其它。 */
    private static int speakRank(ApiProvider p) {
        if (p == null) {
            return 3;
        }
        if ("local".equalsIgnoreCase(p.type)) {
            return 2; // 系统语音
        }
        String u = p.baseUrl == null ? "" : p.baseUrl.toLowerCase(java.util.Locale.ROOT);
        if (u.isBlank()) {
            return 3;
        }
        if (u.contains("127.0.0.1") || u.contains("localhost")) {
            return 1; // 本地 HTTP（GPT-SoVITS 等）
        }
        return 0; // 在线
    }

    /** 翻译源的优先级顺序。 */
    public static List<ApiProvider> translateOrder() {
        return order(translate().providers, translate().activeProvider);
    }

    private static List<ApiProvider> order(List<ApiProvider> all, String active) {
        List<ApiProvider> out = new ArrayList<>();
        if (all == null) {
            return out;
        }
        ApiProvider first = null;
        for (ApiProvider p : all) {
            if (p == null || !p.enabled) {
                continue;
            }
            // ★ 2026-11-01：active 现在是【槽位号】("3") ✗ —— 只比名字永远匹配不上 ✗
            //   两种都认 ✓：数字比 slot ✓，老配置里的名字比 name ✓
            boolean isActive;
            if (active == null || active.isBlank()) {
                isActive = false;
            } else if (active.matches("\\d+")) {
                isActive = p.slot == Integer.parseInt(active);
            } else {
                isActive = active.equals(p.name);
            }
            if (isActive) {
                first = p;
            } else {
                out.add(p);
            }
        }
        if (first != null) {
            out.add(0, first);
        }
        return out;
    }

    // ---- 内部 ----

    /**
     * 配置改名接管：新名不存在、旧名存在时，把旧文件复制成新名（内容一个字不动）。
     * 旧文件保留不删——用户回退旧版本也还能用。
     */
    private static void migrate(String file, String legacy) {
        Path p = dir.resolve(file);
        Path old = dir.resolve(legacy);
        if (Files.isRegularFile(p) || !Files.isRegularFile(old)) {
            return;
        }
        try {
            Files.copy(old, p);
            ChatPatches.LOGGER.info("[chatpatch] 配置改名：{} → {}（旧文件保留）", legacy, file);
        } catch (Exception e) {
            ChatPatches.LOGGER.warn("[chatpatch] 生成 {} 失败（继续读旧文件）: {}", file, e.toString());
        }
    }
    private static <T> T load(String file, Class<T> type, java.util.function.Supplier<T> defaults) {
        Path p = dir.resolve(file);
        if (Files.isRegularFile(p)) {
            try {
                T v = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), type);
                if (v != null) {
                    return v;
                }
            } catch (Exception e) {
                ChatPatches.LOGGER.warn("[chatpatch] 读配置 {} 失败，改用默认值: {}", file, e.toString());
            }
        }
        T v = defaults.get();
        save(file, v);
        return v;
    }

    private static void save(String file, Object value) {
        if (dir == null || value == null) {
            return;
        }
        try {
            Files.writeString(dir.resolve(file), GSON.toJson(value), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ChatPatches.LOGGER.warn("[chatpatch] 写配置 {} 失败: {}", file, e.toString());
        }
    }

    /** 全新安装时的默认翻译配置。public 是为了让自测能断言"默认模型名"。 */
    public static TranslateConfig defaultTranslate() {
        TranslateConfig cfg = new TranslateConfig();

        // ① B站 Index-Translate —— 免费、免注册、免 Key、OpenAI 兼容
        //
        //    实测（同机同网，2026-10-12）：
        //      /v1/models  HTTP 200 · 229ms
        //      入站 5 条真实 MIA 聊天：320~800ms，译文准确
        //      出站 4 条中文：晚安→gn · 我要去睡觉了→gn ·
        //                     有人在第一层吗→any1 on first floor? · 谢谢我马上回来→ty, brb
        //    ★ 出站质量明显强过通用模型：它是**翻译专用模型**，
        //      "外服口语"那股味道通用模型再提示也写不出来。
        //    ⚠️ 免费公共端点，随时可能限流/改协议 → 后面必须留兜底（见 ②）。
        ApiProvider bili = new ApiProvider();
        bili.name = "bili";
        bili.title = "免费：B站 Index-Translate";
        bili.baseUrl = "https://index-translate.bilibili.com/v1";
        bili.model = "Index-Translate-35B-A3B";
        // apiKey 留空 = 不发 Authorization 头（见 OpenAiCompatibleProvider.hasKey）
        cfg.providers.add(bili);

        ApiProvider mymemory = new ApiProvider();
        mymemory.name = "mymemory";
        mymemory.title = "免费：MyMemory";
        mymemory.endpoint = "https://api.mymemory.translated.net/get?q={text}&langpair={langpair}";
        cfg.providers.add(mymemory);

        // ③ 付费兜底：默认【关着】。
        //
        //   ★ 这条是给"要发给别人测试"定的：全新安装的人没有 key，
        //     而 ready() 只看 baseUrl + model（不看 key 有没有填），
        //     所以一个 enabled 但没 key 的源会被当成"可用"，每次调用都换回一个 401 ——
        //     白白占降级链里的一份时间。默认关掉，填了 key 自己打开即可。
        ApiProvider deepseek = new ApiProvider();
        deepseek.name = "deepseek";
        deepseek.title = "付费：DeepSeek（要自己填 key）";
        deepseek.baseUrl = "https://api.deepseek.com/v1";
        // ★ 必须是 deepseek-chat，不能是 deepseek-flash。
        //
        //   实测（同一台机器、同一把 key、同样的问题）：
        //     deepseek-flash 不设 max_tokens : 17984 / 22793 / 36494 ms
        //                                     可见答案只有 6 个字，却烧了 2872 / 3686 / 6416 个 token
        //     deepseek-flash 设 max_tokens  : 返回【空】内容（token 全用在推理上，finish=length）
        //     deepseek-chat                 : 577 ms，1 个 token，答案正确
        //
        //   deepseek-flash 是"思考型"模型，推理会把预算烧光 —— 用来做聊天翻译必然超时，
        //   表现就是"翻译总是失败/有些消息永远没译文"。
        deepseek.model = "deepseek-chat";
        // ★★ 关掉「思考模式」——翻译不需要推理，开着纯属浪费。
        //
        //   实测（DeepSeek 思考型端点）：
        //     不设上限 : 17984 / 22793 / 36494 ms，可见答案 6 个字，却烧 2872~6416 token
        //     关闭思考 : ~600ms，几个 token 搞定
        //   差 30 倍。聊天翻译的时间预算是 4 秒，开着思考必然超时 → 整条降级链作废。
        //
        //   参数名各厂商不同：DeepSeek 是 {"thinking":{"type":"disabled"}}；
        //   有些中转站不认这个字段（会当未知字段忽略，无害）—— 所以放心默认加上。
        //   用户想改就在 translate.json 的 extraBody 里覆盖。
        deepseek.extraBody = new java.util.LinkedHashMap<>();
        deepseek.extraBody.put("thinking", java.util.Map.of("type", "disabled"));
        deepseek.enabled = false;
        cfg.providers.add(deepseek);

        // ★ 首选是免费那家（B站）。首选决定了降级链里谁先试（见 TranslationManager.order()）。
        // ★ 2026-11-01：默认配置直接发号 + 写号 ✓（不再写名字 ✗ 免得依赖启动时迁移）
        bili.slot = 1;
        mymemory.slot = 2;
        deepseek.slot = 3;
        cfg.nextSlot = 3;
        cfg.activeProvider = String.valueOf(bili.slot);
        cfg.outgoingProvider = String.valueOf(bili.slot);   // 出站是独立字段，别只设一个（踩过这个坑）
        // 注意：这里【不】设 targetLanguage —— 默认就是 TranslateConfig 里的「简体中文」。
        // 为了导出"越南语默认"的 jar，曾临时加过 cfg.targetLanguage = "越南语"，导出后必须删 ✗。
        // （换语言版 jar 之后，旧配置的纠正由 migrateTargetLanguage 负责，见那边注释）
        return cfg;
    }

    /** 首次运行时写出的默认朗读配置：一个本地引擎（不用网络不用 key）+ 一个 OpenAI 的位置。 */
    private static ReadAloudConfig defaultReadAloud() {
        ReadAloudConfig cfg = new ReadAloudConfig();

        // 本地引擎：不需要 baseUrl/apiKey。具体调哪个由 MC 那边的适配层决定
        // （比如 Windows 系统语音）。放第一个，因为它断网也能用、不花钱。
        ApiProvider local = new ApiProvider();
        local.name = "系统语音";
        local.title = "本地：系统语音";
        local.type = "local";
        local.voice = "";
        cfg.providers.add(local);

        // 云端引擎：留个位子，填上 baseUrl/key/model 就能用
        ApiProvider openai = new ApiProvider();
        openai.name = "openai-tts";
        openai.title = "付费：OpenAI TTS";
        openai.type = "api";
        openai.baseUrl = "https://api.openai.com/v1";
        openai.ttsModel = "tts-1";
        openai.voice = "alloy";
        openai.format = "wav";
        openai.enabled = false; // 默认关着，免得没配好就报错
        cfg.providers.add(openai);
        // ★ 朗读侧也发号 ✓（跟翻译同一套规则 ✓）
        local.slot = 1;
        openai.slot = 2;
        cfg.nextSlot = 2;

        cfg.activeProvider = local.name;
        return cfg;
    }

    /** 便于测试：直接塞一份配置进来。 */
    public static void setTranslateForTest(TranslateConfig cfg) {
        translate = cfg;
    }

    /** 便于测试：直接塞一份朗读配置进来。 */
    public static void setReadAloudForTest(ReadAloudConfig cfg) {
        readAloud = cfg;
    }

    public static List<ApiProvider> providers() {
        return translate().providers;
    }
}
