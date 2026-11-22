package obro1961.chatpatches.chatstudy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import obro1961.chatpatches.ChatLog;
import obro1961.chatpatches.ChatPatches;
import obro1961.chatpatches.chatlog.ChatLogStore;
import obro1961.chatpatches.chatlog.LogEntry;
import obro1961.chatpatches.pipeline.ChatStudyPipeline;
import obro1961.chatpatches.translate.ApiProvider;
import obro1961.chatpatches.translate.ConfigStore;
import obro1961.chatpatches.translate.Glossary;
import obro1961.chatpatches.translate.TranslationManager;
import obro1961.chatpatches.util.ChatUtil;
import obro1961.chatpatches.tts.AudioPlayer;
import obro1961.chatpatches.tts.OpenAiTtsProvider;
import obro1961.chatpatches.tts.ReadAloudConfig;
import obro1961.chatpatches.tts.SpeechPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <b>MC 适配壳</b> —— 把三个已验证的模块（翻译层 / 存储层 / TTS 层）接到 Minecraft 上。
 *
 * <p>这里<b>只做搬运，不做决策</b>：所有"该不该翻、该不该读、怎么落盘"的判断都在
 * {@link ChatStudyPipeline} 里（那部分有 124 项自测覆盖）。这个类负责：
 * <ol>
 *   <li>把 MC 的 {@code Component} 剥成纯文本，喂给流水线</li>
 *   <li>把流水线的决定作用回 MC（改 allMessages、播放音频、贴提示）</li>
 * </ol>
 *
 * <p><b>注意：本文件依赖 Minecraft，无法独立编译验证</b>（Gradle 构建当前受环境限制）。
 * 逻辑本身已在无 MC 环境验证过。
 */
public final class ChatStudyBridge {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * ★ <b>启动游戏那天</b> —— 只在类加载时取一次。
     *
     * <p>熬夜跨过午夜也还是这一天，因为游戏没重启。这就是"以进入时间为准"那条规则的实现：
     * 日期在启动那一刻定死，之后不管几点都写同一本。
     */
    private static final String SESSION_DAY = LocalDate.now().format(DAY);
    private static final String SESSION_START = LocalDateTime.now().format(FULL);

    private static ChatLogStore store;
    private static ChatStudyPipeline<Component> pipeline;
    private static AudioPlayer player;
    /** 翻译/落盘用的线程。 */
    private static ExecutorService worker;
    /** 出站（自己发消息）专用 —— 不能排在别人消息后面。 */
    private static ExecutorService outgoingWorker;
    /**
     * 语音合成专用的线程 —— <b>必须跟翻译分开</b>。
     *
     * <p>语音合成要起外部进程，一句要几百毫秒到一秒多；如果跟翻译共用一条线程，
     * 读一句话就会把翻译堵住一秒。
     */
    private static ExecutorService ttsWorker;

    /**
     * ★ 是否正在"灌回历史"。
     *
     * <p><b>这个标志是必须的</b>：`chat.addMessage(...)` 会被 ChatComponentMixin 拦下，
     * 又走回 {@link #onMessage}，于是灌回的消息会被<b>再记录一遍、再翻译一遍、再朗读一遍</b>
     * ——每进一次服记录就翻一倍。灌回期间必须把入口关掉。
     */
    private static volatile boolean restoring = false;

    /**
     * ★ <b>登记我发出去的那句英文 → 我原本打的中文</b>。服务器把英文回显回来时，
     * 靠它认出"这是我的"，然后把那一行<b>改造成</b>「中文 + ↳ 英文」。
     *
     * <p><b>为什么是"改造"而不是"自己造一行 + 删掉回显"</b>（走过的弯路）：
     * <ul>
     *   <li>自己造一行 → 排版一定和服务器的不一样（MIA 是 {@code   Xiaoliyu_: 内容}，
     *       Chat Patches 的 {@code nameFormat} 是 {@code <Xiaoliyu_>}），一眼就看得出来</li>
     *   <li>删掉回显 → 还得处理"onMessage 比 addMessage 早、此刻消息不在聊天栏"的时序问题</li>
     * </ul>
     * 直接改造回显那一行，就<b>天生是服务器的排版</b> —— 因为它本来就是服务器那一行。
     */
    private static final java.util.Map<String, String> SELF_TRANSLATED_SENT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 上限：万一回显没来（换服/掉线），也不让它无限涨。 */
    private static final int SELF_TRANSLATED_SENT_MAX = 32;

    private static volatile String currentPlace = "未知";
    private static final Set<String> sessionHeaded = new HashSet<>();

    private static boolean initialized = false;

    private ChatStudyBridge() {
    }

    // ---- 初始化 ----

    /** 在 {@code ChatPatches.onInitializeClient()} 里调用一次。 */
    /**
     * 兜底记录未捕获异常。
     *
     * <p>为什么需要：2026-10-21 那次"卡退"，MC 自己的崩溃报告器抛了
     * {@code Negative index in crash report handler}，结果 crash-reports 目录里什么都没有。
     * 这里把线程名 + 完整栈（含 cause 链）追加到 {@code config/chatpatches/last-crash.txt}，
     * 同时打到游戏日志 —— 下次不用再猜。
     */
    private static void installCrashLogger(Path configDir) {
        try {
            final Path crashFile = configDir.resolve("chatpatches").resolve("last-crash.txt");
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                try {
                    ChatPatches.LOGGER.error("[chatpatch] 未捕获异常（线程 {}）", thread.getName(), error);
                } catch (Throwable ignored) {
                }
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                    pw.println("==== " + java.time.LocalDateTime.now() + "  thread=" + thread.getName() + " ====");
                    error.printStackTrace(pw);
                    pw.flush();
                    java.nio.file.Files.createDirectories(crashFile.getParent());
                    java.nio.file.Files.writeString(crashFile, sw.toString(),
                            java.nio.charset.StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }
    /**
     * <b>一次性存储去冗</b>：把旧记录里的 {@code "ref":<行号>} 换成 {@code "refSeq":<seq>}。
     *
     * <p>为什么必须做：以前译文靠"挂第几行"引用原文（`ref`），后来改成"挂 seq"（`refSeq`）。
     * 两套字段长期并存 = 用户说的"双格式兼容的冗杂"，也是读代码时最容易踩的坑
     * （行号会因 session 头、删除、跨天而错位；seq 不会）。
     *
     * <p>做法：
     * <ol>
     *   <li>先整体复制 {@code chatlogs/} 到 {@code _backup-chatlogs-<时间>/}（失败就不迁移）；</li>
     *   <li>每个文件建 行号→seq 映射；</li>
     *   <li>译文行：能映射 → 改写成 refSeq；映射不上 → 删掉 ref 字段（该译文会退化为"不配对"）；</li>
     *   <li>删除标记：旧 {@code "line"} 同理换成 targetSeq。</li>
     * </ol>
     * 幂等：文件里没有 {@code "ref"} 就直接返回，不会重复迁移。
     */
    private static void migrateLegacyRecordRefs(Path logsRoot) {
        try {
            java.util.List<Path> files;
            try (var walk = java.nio.file.Files.walk(logsRoot)) {
                files = walk.filter(p -> p.toString().endsWith(".jsonl")).toList();
            }
            boolean anyRef = false;
            for (Path f : files) {
                try {
                    if (java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8).contains("\"ref\"")) {
                        anyRef = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!anyRef) {
                return;
            }

            Path backup = logsRoot.getParent().resolve("_backup-chatlogs-"
                    + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now()));
            try (var walk = java.nio.file.Files.walk(logsRoot)) {
                for (Path src : walk.toList()) {
                    Path dst = backup.resolve(logsRoot.relativize(src).toString());
                    if (java.nio.file.Files.isDirectory(src)) {
                        java.nio.file.Files.createDirectories(dst);
                    } else {
                        java.nio.file.Files.createDirectories(dst.getParent());
                        java.nio.file.Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            int converted = 0;
            int dropped = 0;
            int injected = 0;
            java.util.regex.Pattern refPat = java.util.regex.Pattern.compile("\"ref\"\\s*:\\s*(\\d+)");
            java.util.regex.Pattern linePat = java.util.regex.Pattern.compile("\"line\"\\s*:\\s*(\\d+)");
            com.google.gson.JsonParser parser = new com.google.gson.JsonParser();
            for (Path f : files) {
                java.util.List<String> raw = java.nio.file.Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8);
                java.util.Map<Integer, Integer> seqByLine = new java.util.HashMap<>();
                java.util.Map<Integer, Integer> injectSeq = new java.util.HashMap<>();
                for (int i = 0; i < raw.size(); i++) {
                    com.google.gson.JsonObject o = asObject(parser, raw.get(i));
                    if (o == null) {
                        continue;
                    }
                    String type = o.has("type") ? o.get("type").getAsString() : "";
                    if (o.has("seq") && !"translation".equals(type) && !"delete".equals(type)) {
                        seqByLine.put(i + 1, o.get("seq").getAsInt());
                    }
                }
                // 老文件（M2b 之前）消息没有 seq → 用"行号"当 seq 写进去，否则 ref 无从换算
                for (int i = 0; i < raw.size(); i++) {
                    com.google.gson.JsonObject o = asObject(parser, raw.get(i));
                    if (o == null || o.has("seq")) {
                        continue;
                    }
                    String type = o.has("type") ? o.get("type").getAsString() : "";
                    if ("translation".equals(type) || "delete".equals(type) || "session".equals(type)) {
                        continue;
                    }
                    seqByLine.put(i + 1, i + 1);
                    injectSeq.put(i + 1, i + 1);
                    injected++;
                }
                java.util.List<String> out = new java.util.ArrayList<>(raw.size());
                for (int li = 0; li < raw.size(); li++) {
                    String s = raw.get(li);
                    Integer inject = injectSeq.get(li + 1);
                    if (inject != null && s.startsWith("{")) {
                        s = "{\"seq\":" + inject + "," + s.substring(1);
                    }
                    if (s.contains("\"ref\"")) {
                        var m = refPat.matcher(s);
                        if (m.find()) {
                            int refLine = Integer.parseInt(m.group(1));
                            Integer seq = seqByLine.get(refLine);
                            if (seq != null) {
                                s = refPat.matcher(s).replaceFirst("\"refSeq\":" + seq);
                                converted++;
                            } else {
                                s = s.replaceFirst(",\\s*\"ref\"\\s*:\\s*\\d+", "").replaceFirst("\"ref\"\\s*:\\s*\\d+,?\\s*", "");
                                dropped++;
                            }
                        }
                    }
                    if (s.contains("\"line\"")) {
                        var m = linePat.matcher(s);
                        if (m.find()) {
                            int ln = Integer.parseInt(m.group(1));
                            Integer seq = seqByLine.get(ln);
                            if (seq != null) {
                                s = linePat.matcher(s).replaceFirst("\"targetSeq\":" + seq);
                                converted++;
                            }
                        }
                    }
                    out.add(s);
                }
                java.nio.file.Files.write(f, out, java.nio.charset.StandardCharsets.UTF_8);
            }
            ChatPatches.LOGGER.info("[chatpatch] 记录去冗迁移完成：补 seq {} 条，ref→refSeq {} 条，删掉无法映射的 ref {} 条；旧文件已备份到 {}",
                    injected, converted, dropped, backup.getFileName());
        } catch (Exception e) {
            ChatPatches.LOGGER.warn("[chatpatch] 记录去冗迁移失败（保留旧字段，不影响读取）: {}", e.toString());
        }
    }

    private static com.google.gson.JsonObject asObject(com.google.gson.JsonParser parser, String line) {
        try {
            var el = parser.parse(line);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }
    public static synchronized void init(Path configDir) {
        if (initialized) {
            return;
        }
        initialized = true;
        // ★ 2026-10-21 加：把未捕获异常（含 OOM/栈溢出）完整落一份到 chatpatches/last-crash.txt。
        //   原因：这次"卡退"时 MC 自带的崩溃报告器自己挂了（Negative index in crash report handler），
        //   日志里拿不到真正的栈。先兜住，下次能直接看。
        installCrashLogger(configDir);
        try {
            ConfigStore.init(configDir);
            Glossary.load();

            Path logsRoot = configDir.resolve("chatpatches").resolve("chatlogs");
            Files.createDirectories(logsRoot);
            store = new ChatLogStore(logsRoot);
            store.setMinSeq(ConfigStore.record().lastSeq);   // 删光记录也不从 1 重来
            // ★ 一次性存储去冗：把老的 "ref":行号 换成 "refSeq":seq（先整体备份）
            migrateLegacyRecordRefs(logsRoot);

            player = new AudioPlayer();
            player.setVolume(ConfigStore.readAloud().volume);

            // 注册 Minecraft 官方语言表（语言选择/翻译提示词/ISO 代码都靠它）
            try {
                obro1961.chatpatches.gui.MinecraftLanguageCatalog.refresh();
            } catch (Throwable ignored) {
            }

            // ★ 出站必须能并发。用单线程的话消息一密集就排队 ——
            // 实测过：26 次翻译平均 2 秒，时间戳每次只隔 1 秒（完全串行），
            // 积压到最后一次竟然 13.7 秒。3 条并发足够，也不会把 API 打爆。
            worker = Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "chatpatch-worker");
                t.setDaemon(true);
                return t;
            });
            // ★ 出站单独一条线程池：你自己发消息时不能排在别人消息的翻译后面，
            // 不然"我等了半分钟才发出去"就是这么来的（实测 40 秒）。
            outgoingWorker = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "chatpatch-outgoing");
                t.setDaemon(true);
                return t;
            });
            ttsWorker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "chatpatch-tts");
                t.setDaemon(true);
                return t;
            });

            pipeline = new ChatStudyPipeline<>(new McSink(), new McTranslator(), worker);
            pipeline.setConfig(ConfigStore.translate(), ConfigStore.readAloud());

            // ★ 必须调这一步：TranslationManager 的 PROVIDERS 是静态表，
            // 只有 rebuild() 会填充它。不调的话 translate() 的循环一次都不执行，
            // 直接静默 return null —— 表现就是"翻译完全不动，日志里还没有任何报错"。
            obro1961.chatpatches.translate.ServiceFeedback.setSink(msg -> {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null) {
                    mc.execute(() -> {
                        if (mc.player != null) {
                            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), false);
                        }
                    });
                }
            });            obro1961.chatpatches.util.I18n.setResolver((key, args) ->
                    net.minecraft.network.chat.Component.translatable(key, args).getString());            TranslationManager.rebuild();
            obro1961.chatpatches.translate.ServiceWatchdog.start();

            // ★ 2026-10-21：把 mod 版本打出来 —— 否则"装的是哪一版"只能靠文件名猜。
            try {
                String modVer = net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getModContainer("chatpatches")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                        .orElse("?");
                ChatPatches.LOGGER.info("[chatpatch] mod 版本 = {}（ChatPatches-ChatStudy）", modVer);
            } catch (Throwable ignored) {
            }
            ChatPatches.LOGGER.info("[chatpatch] 就绪 —— 记录目录 {}，今天是 {}，翻译源 {} 个：{}",
                    logsRoot, SESSION_DAY, TranslationManager.ids().size(), TranslationManager.ids());

            // ★ 把自动朗读的开关状态也打出来。
            //
            //   为什么必须打：这个开关【默认关着过一次】—— 而右键菜单的手动朗读
            //   不受它管。于是现象是"右键能读、自动不读"，用户报的像是 bug，
            //   实际只是 readAloud.json 里一个字段，日志里却一个字都没有，
            //   只能靠猜（真实反馈：「它那边可以右键主动朗读，不能自动朗读」）。
            //   一句话的成本，换掉一整类"查不出原因"。
            ReadAloudConfig ra = ConfigStore.readAloud();
            ChatPatches.LOGGER.info("[chatpatch] 自动朗读：{}（2-readaloud.json 的 enabled；"
                            + "只读翻译过的消息 = {}；字符数 {}-{}；右键手动朗读不受这个开关管）",
                    ra.enabled ? "开" : "关",
                    ra.readOriginal, ra.minLength, ra.maxLength);
            // ★ 把【本构建的默认目标语言】打出来。
            //
            //   为什么：我们有两个构建 —— 主版（默认中文）和越南语版（默认越南语）。
            //   两个 jar 的**文件名只差「-越南语」三个字、大小只差几十字节** ✗，
            //   发给别人之后根本分不清谁是谁 —— 用户和朋友就因此折腾了一晚上：
            //   对方拿的是主版，译文当然还是中文 ✗，但看起来像"mod 没生效"。
            //
            //   打这一行之后，任何一台机器启动就知道自己装的是哪个版本 ✓，
            //   "对方说还是中文"这类反馈可以直接靠这行定位。
            ChatPatches.LOGGER.info("[chatpatch] 本构建的默认目标语言：{}"
                            + "（换语言版 jar 之后，旧配置里滞留的出厂默认值会自动纠正；"
                            + "要别的语言就改 1-translate.json 的 targetLanguage）",
                    ConfigStore.defaultTranslate().targetLanguage);
        } catch (Exception e) {
            ChatPatches.LOGGER.error("[chatpatch] 初始化失败", e);
        }
    }

    /**
     * 局内配置保存后立即生效：重建翻译源、把新配置塞回流水线。
     * <p>只由设置界面保存时调用；手动改配置文件不触发（重启才生效，省掉文件轮询）。
     */
    private static Thread detectWatcher;

    /** 自动检测：开=定期探端口并启用；关=停止。 */
    public static synchronized void refreshDetectWatcher() {
        obro1961.chatpatches.translate.TranslateConfig cfg = ConfigStore.translate();
        if (!cfg.autoDetectService) {
            if (detectWatcher != null) {
                detectWatcher.interrupt();
                detectWatcher = null;
                ChatPatches.LOGGER.info("[chatpatch] 自动检测已关闭");
            }
            return;
        }
        if (detectWatcher != null && detectWatcher.isAlive()) {
            return;
        }
        int sec = Math.max(5, cfg.autoDetectIntervalSec);
        detectWatcher = new Thread(() -> {
            while (true) {
                try {
                    obro1961.chatpatches.gui.ChatStudyYaclScreen.autoDetectNow();
                    Thread.sleep(sec * 1000L);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                }
            }
        }, "chatpatch-detect");
        detectWatcher.setDaemon(true);
        detectWatcher.start();
        ChatPatches.LOGGER.info("[chatpatch] 自动检测已开启，每 {} 秒一次", sec);
    }
    public static void applyConfigNow() {
        try {
            obro1961.chatpatches.translate.ServiceFeedback.setSink(msg -> {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null) {
                    mc.execute(() -> {
                        if (mc.player != null) {
                            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), false);
                        }
                    });
                }
            });            obro1961.chatpatches.util.I18n.setResolver((key, args) ->
                    net.minecraft.network.chat.Component.translatable(key, args).getString());            TranslationManager.rebuild();
            obro1961.chatpatches.translate.ServiceWatchdog.start();
            if (pipeline != null) {
                pipeline.setConfig(ConfigStore.translate(), ConfigStore.readAloud());
            }
            ChatPatches.LOGGER.info("[chatpatch] 配置已即时生效（翻译源 {} 个）", TranslationManager.ids().size());
            refreshDetectWatcher();
            obro1961.chatpatches.translate.ServiceWatchdog.resetState();
            obro1961.chatpatches.translate.ServiceWatchdog.kick();
        } catch (Throwable t) {
            ChatPatches.LOGGER.warn("[chatpatch] 即时生效失败: {}", t.toString());
        }
    }

    public static boolean ready() {
        return initialized && pipeline != null;
    }

    /**
     * 我们是不是正在把历史灌回聊天栏。
     *
     * <p>给 {@code ChatComponentMixin} 用：灌回时它<b>不能再跑一遍 modifyMessage</b> ——
     * 我们存的已经是"渲染好的整行"（含时间戳），再跑一次会<b>多补一个时间戳</b>。
     */
    public static boolean isRestoring() {
        return restoring;
    }

    public static ChatLogStore store() {
        return store;
    }

    public static String sessionDay() {
        return SESSION_DAY;
    }

    public static String sessionStart() {
        return SESSION_START;
    }

    public static String currentPlace() {
        return currentPlace;
    }

    // ---- 地点（决定二级文件夹）----

    /** 现在在哪个"地点"：多人 = 服务器名；单人 = 单人_世界名。 */
    public static String resolvePlace() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getCurrentServer() != null) {
                String n = mc.getCurrentServer().name;
                if (n != null && !n.isBlank()) {
                    return ChatLogStore.sanitize(n);
                }
            }
            if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
                String level = mc.getSingleplayerServer().getWorldData().getLevelName();
                return ChatLogStore.singleplayerPlace(level);
            }
        } catch (Exception e) {
            ChatPatches.LOGGER.warn("[chatpatch] 取地点失败: {}", e.toString());
        }
        return "未知";
    }

    /**
     * 换地点时切文件夹。
     *
     * <p>注意：<b>公屏不受影响</b>（Chat Patches 的 vanillaClearing=false 会保留聊天），
     * 这里只决定"接下来的记录写到哪个文件夹"。
     */
    public static void onJoin() {
        String place = resolvePlace();
        currentPlace = place;
        ensureSessionHeader(place);
        ChatPatches.LOGGER.info("[chatpatch] 进入 {} —— 记录写到 {}/{}", place, SESSION_DAY, place);
            // 每次进世界：重置记忆并立刻检测一轮
            obro1961.chatpatches.translate.ServiceWatchdog.resetState();
            obro1961.chatpatches.translate.ServiceWatchdog.start();    }

    /** 同一次启动里，一个地点只写一次 session 头。 */
    private static void ensureSessionHeader(String place) {
        if (!ready() || place == null || sessionHeaded.contains(place)) {
            return;
        }
        sessionHeaded.add(place);
        try {
            store.append(SESSION_DAY, LogEntry.session(SESSION_START));
        } catch (IOException e) {
            ChatPatches.LOGGER.warn("[chatpatch] 写会话头失败: {}", e.toString());
        }
    }

    // ---- 消息进来（由 ChatUtil.modifyMessage 调用）----

    /**
     * 一条消息处理完了，交给流水线。
     *
     * <p><b>★ 这里传的是「结构」，不是「渲染好的字符串」。</b>
     * {@code ChatUtil.modifyMessage} 是唯一一个"万事俱备"的时刻：发送者资料和正文参数表
     * 都还在手上。再往下走一步就只剩一个字符串，那两件事就<b>永远拿不回来了</b>。
     *
     * <p>这不是洁癖。本服资源包把 {@code chat.type.text} 改写成了 {@code "%s%s"}，
     * 渲染结果就是 {@code Xiaoliyu_23333} —— 名字和内容之间<b>没有任何分隔符</b>，
     * 事后想从串里把两者分开是不可能的。
     *
     * @param modified 已经拼装好的那条 Component（要落盘、也是译文的挂载点）
     * @param time     这条消息的时间
     * @param type     chat / system / death / boundary
     * @param speaker  结构化说话人：{@code messageData.sender().name()}，拿不到才是 null
     * @param content  <b>结构化正文</b>：纯内容、绝不含名字；拿不到才是 null（才落到兜底）
     */
    public static void onMessage(Component modified, java.util.Date time, String type,
                                 String speaker, String content) {
        onMessage(modified, time, type, speaker, content, null);
    }

    /**
     * @param uuid 发送者 UUID（拿不到就 {@code null}）。M2b 起落盘，供以后按玩家回溯。
     */
    public static void onMessage(Component modified, java.util.Date time, String type,
                                 String speaker, String content, String uuid) {
        if (!ready() || modified == null) {
            return;
        }
        // ★ 灌回历史时会重新走到这里，必须挡住，否则记录会翻倍、还会重复朗读
        if (restoring) {
            return;
        }
        // 恢复历史时不要重复记录，也不要翻译 —— 那些本来就是我们自己的文件读回来的
        if (ChatLog.isRestoring()) {
            return;
        }
        // ★ 类型还是在这里判一次。
        //
        // 为什么不能省：MIA 的服务器消息是以 system 进来的（拿不到 messageData），
        // 但正文里其实写着「谁说的」。不把它提升成 chat，翻译和朗读会一起失效
        // （实测一轮 system:97 / chat:13，就是这条造成的）。
        //
        // ★ 这里必须用 plain() 洗过的串：流水线里 resolveSpeaker 拿到的也是 plain 过的文本。
        //   以前这里喂原始串（带 § 颜色码和资源包私用区图标）、那边喂洗过的，
        //   【同一份事实两种输入】—— 结果分类判漏、名字抠不出，表现就是"朋友发的 wb 不翻译"。
        String body = ChatStudyPipeline.plain(
                ChatStudyPipeline.stripTimestamp(modified.getString()));

        // ★ 这条是不是"我刚翻译发出的那句"的回显？是就把它改造成「中文 + ↳ 英文」，
        //   并从这里返回（不落盘、不翻译、不朗读 —— 它是我自己说的话，不是新消息）。
        if (rewriteOwnEcho(modified, speaker, content, body)) {
            return;
        }

        // ★ 多传一个【结构信号】：MIA 的玩家聊天模板里，名字上挂着
        //   click_event = run_command: /mia profile <玩家>。
        //   名字里有没有空格 / emoji / 全角字母 / 数学粗斜体，都影响不了这个信号。
        String compJson = componentJson(modified);
        String kind = ChatStudyPipeline.classifyMessage(type, speaker, body,
                ChatStudyPipeline.looksLikePlayerChat(compJson));

        // 抠名字：判成玩家聊天、但按常规规则抠不出名字时，用「第一个冒号之前」的宽松规则
        // （MIA 允许名字带空格：🍁𝓐𝔂𝓪 𝓦𝓮𝓷𝓦𝓮𝓷🍁、aya wenwen）
        String who = speaker;
        if ((who == null || who.isBlank()) && ChatStudyPipeline.isChat(kind)) {
            who = ChatStudyPipeline.speakerAllowSpaces(body);
        }

        // 来源标记：本模组自己插入的消息不进流水线（不记录、不翻译、不朗读）
        String source = obro1961.chatpatches.pipeline.SelfMessages.isSelf(modified.getString())
                ? obro1961.chatpatches.chatlog.LogEntry.SOURCE_MOD
                : obro1961.chatpatches.chatlog.LogEntry.SOURCE_SERVER;
        pipeline.onIncoming(modified, new ChatStudyPipeline.Incoming(
                kind, timeStr(time), who, content, modified.getString(), compJson, uuid, source));
    }

    // ---- 把 Component 存成 JSON / 从 JSON 还原（灌回历史要保真）----

    /**
     * 把那条消息序列化成 JSON —— <b>存下来才能在灌回时还原样式</b>。
     *
     * <p>{@code modified.getString()} 是纯文本，样式全丢（时间戳的粉色、名字的白色、
     * 悬浮提示、右键建议命令、玩家实体 hover 都不在里面）。原版 Chat Patches 的
     * {@code logs/chatlog.json} 存的就是这种结构，所以它灌回来是保真的 ——
     * 这就是"原版可以、我们不行"的全部原因。
     *
     * <p>失败返回 {@code null}：不影响主流程，灌回时回落成纯文本（内容还在）。
     */
    private static String componentJson(Component c) {
        try {
            //? if >=1.21.5 {
            net.minecraft.resources.RegistryOps<com.google.gson.JsonElement> ops =
                    net.minecraft.resources.RegistryOps.create(
                            com.mojang.serialization.JsonOps.INSTANCE, registries());
            return net.minecraft.network.chat.ComponentSerialization.CODEC
                    .encodeStart(ops, c)
                    .result()
                    .map(com.google.gson.JsonElement::toString)
                    .orElse(null);
            //?} else {
            /*return net.minecraft.network.chat.Component.Serializer.toJson(c);
            *///?}
        } catch (Exception e) {
            ChatPatches.LOGGER.debug("[chatpatch] 组件序列化失败（灌回会退化成纯文本）: {}", e.toString());
            return null;
        }
    }

    /** 从 JSON 还原。任何一步不对就返回 {@code null}，由调用方回落。 */
    private static Component componentFromJson(String json) {
        try {
            //? if >=1.21.5 {
            net.minecraft.resources.RegistryOps<com.google.gson.JsonElement> ops =
                    net.minecraft.resources.RegistryOps.create(
                            com.mojang.serialization.JsonOps.INSTANCE, registries());
            return net.minecraft.network.chat.ComponentSerialization.CODEC
                    .parse(ops, com.google.gson.JsonParser.parseString(json))
                    .result()
                    .orElse(null);
            //?} else {
            /*return net.minecraft.network.chat.Component.Serializer.fromJson(json);
            *///?}
        } catch (Exception e) {
            ChatPatches.LOGGER.debug("[chatpatch] 组件反序列化失败（这条按纯文本显示）: {}", e.toString());
            return null;
        }
    }

    /**
     * 解析组件时需要的注册表。
     *
     * <p>为什么需要：{@code hover_event} 里可能引用注册表条目
     * （例如原版存档里的 {@code {action:"show_entity", id:"minecraft:player"}}）。
     * 连接还没建立时退到 {@link net.minecraft.core.RegistryAccess#EMPTY} ——
     * 那种情况解析会失败，然后回落到纯文本，不会炸。
     */
    private static net.minecraft.core.HolderLookup.Provider registries() {
        try {
            var conn = Minecraft.getInstance().getConnection();
            if (conn != null) {
                return conn.registryAccess();
            }
        } catch (Exception ignored) {
            // 拿不到就用空注册表，让调用方回落
        }
        return net.minecraft.core.RegistryAccess.EMPTY;
    }

    private static String timeStr(java.util.Date time) {
        return TIME.format(LocalDateTime.ofInstant(time.toInstant(), ZoneId.systemDefault()));
    }

    /**
     * 把某条消息的译文落盘（挂载成功后调用）。
     *
     * @param refSeq 这条译文挂在<b>哪条消息的 {@code seq}</b>；{@code <= 0} = 不知道。
     *               写进 {@code refSeq} 之后，配对就不靠"译文是不是恰好落在下一行"了 ——
     *               译文是异步回来的，会插队（实测那份记录里就串过）。
     *
     * <p><b>★ 2026-10-20（M2b）：参数从"物理行号"改成 {@code seq}。</b>
     * 存储层 {@code append()} 现在返回的是 {@code seq}，而 {@code seq} 和行号并不是同一个数
     * （前面有 session 头、后面插 delete 标记都会顶偏）。旧写法把 seq 当行号存进 {@code ref}，
     * 灌回时按行号查 → 译文挂到别人头上。现在两侧统一认 {@code seq}。
     */
    private static void recordTranslation(String timeStr, String translation, int refSeq) {
        try {
            String lang = ConfigStore.translate() == null ? null : ConfigStore.translate().targetLanguage;
            store.append(SESSION_DAY, currentPlace,
                    ChatStudyPipeline.translationEntry(timeStr, translation, refSeq, lang));
        } catch (IOException e) {
            ChatPatches.LOGGER.warn("[chatpatch] 写译文记录失败: {}", e.toString());
        }
    }

    // ---- 出站（由发送钩子调用）----

    /**
     * 这条要发出去的话需要翻译吗？
     *
     * <p>由 {@code ClientSendMessageEvents.ALLOW_CHAT} 调用：
     * 返回 true 时我们会拦下原文，翻译好再真正发出去。
     *
     * <p><b>为什么要"拦下发"而不是原地改</b>：翻译要联网（几百毫秒），
     * 直接在发送钩子里等会<b>卡住游戏</b>。拦下来异步翻，翻完再发。
     */
    /**
     * 我们自己正准备发出的内容。
     *
     * <p><b>为什么必须有这道防线</b>：{@code ClientPacketListener.sendChat()} <b>同样会走</b>
     * {@code ClientSendMessageEvents.ALLOW_CHAT}。我们拦下原文、翻好、再用 sendChat 发出去 ——
     * 那一次发送会被<b>自己的闸门再拦一次</b>：
     * <pre>
     *   拦下 → 翻译 → sendChat → 又被拦 → 又翻译 → 又 sendChat → …
     * </pre>
     * 实测发一个 {@code 2333} 写出 <b>441567 条记录 / 21 MB</b>，游戏直接卡死。
     *
     * <p>登记是<b>精确匹配</b>（"我马上要发这句"），不是文本启发式 —— 闸门看到就放行并消费掉，
     * 用完即删，不会堆积。
     */
    private static final java.util.Set<String> SELF_SENDING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 上限：万一日志/事件路径没按预期消费，也不让它无限涨。 */
    private static final int SELF_SENDING_MAX = 64;

    /**
     * 「这句译文是我翻出来的、原文是 X」——供写<b>出站双记录</b>（S6）用。
     *
     * <p>为什么不在 {@code sendTranslated} 里直接写：{@code ClientSendMessageEvents.CHAT}
     * 是<b>真的发出去之后</b>才触发的，那里才是"事实发生"的唯一时刻；
     * 但 CHAT 事件只拿得到发出去的串，拿不到我原本打的中文。
     *
     * <p>所以发送<b>之前</b>先登记 {@code 译文 → 原文}，CHAT 事件来领；
     * 领到就写两条（outgoing=原文、translation=实际发出的译文），领不到就按普通出站写一条。
     * 同一线程内先登记后发送，不存在竞态。
     */
    private static final java.util.Map<String, String> PENDING_OUTGOING =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final int PENDING_OUTGOING_MAX = 32;

    /** 手动翻译的"在途请求"（防连点重复打 API）。用 IdentityHashMap：同句话的两条消息不能算同一条。 */
    private static final java.util.Map<Component, Long> PENDING_TRANSLATE =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    /** 在途请求最多认多久（超时就不挡了，免得失败后永远点不动）。 */
    private static final long PENDING_TRANSLATE_TTL_MS = 15000L;

    /** 这个名字是不是我自己。给 {@code isSelf} 和"认领自己的回显"共用。 */
    static boolean isOwnName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc.player != null && name.equalsIgnoreCase(mc.player.getName().getString());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 这条进来的是不是"我刚翻译发出那句的回显"？是就把它<b>改造成</b>「中文 + ↳ 英文」。
     *
     * <p><b>为什么是改造而不是删掉</b>：那一行本来就是服务器按它的排版渲染的
     * （MIA 是 {@code   Xiaoliyu_: 内容}）。删掉它、自己再造一行，排版一定不一样。
     * 改造它 —— 只把"内容"那段换成中文 —— 排版天生就对。
     *
     * @return true = 已经认领（调用方直接返回：不落盘、不翻译、不朗读）
     */
    private static boolean rewriteOwnEcho(Component modified, String speaker,
                                          String content, String body) {
        if (SELF_TRANSLATED_SENT.isEmpty()) {
            return false;
        }
        // 必须是自己说的 —— 不然别人复读同一句会被误伤
        if (!isOwnName(obro1961.chatpatches.pipeline.ChatStudyPipeline.resolveSpeaker(speaker, body))) {
            return false;
        }
        // 先找出是哪一句（两种形状都认）
        //   ① 结构化正文（`ChatUtil` 从 chat.type.* 参数表里取的）—— 最准
        //   ② 整行以那句结尾 —— 兜底。服务器可能用 `名字: 内容`、`<名字> 内容` 等各种排版
        //
        //   【踩过的坑】以前只用 splitNameAndContent 按名字切，遇到 `<Xiaoliyu_> im gonna build a house`
        //   会切出 `> im gonna build a house`（名字后面是 '>' 不是冒号，不吃掉那个 '>'），
        //   于是跟登记的 `im gonna build a house` 对不上 → 认不出。
        String sent = null;
        for (String s : SELF_TRANSLATED_SENT.keySet()) {
            if (s.equals(content) || (body != null && body.endsWith(s))) {
                sent = s;
                break;
            }
        }
        if (sent == null) {
            return false;
        }
        final String sentText = sent;
        final String original = SELF_TRANSLATED_SENT.remove(sentText);

        // ★★ 必须【推迟到这一帧之后】才能改聊天栏 ——
        //    onMessage 是在 modifyMessage 里被调的，比 addMessage 还早，
        //    此刻这一行根本不在 allMessages 里，直接找必然找不到。
        //    （上一版就是因为这个，摘除一次都没生效；attachTranslation 也是同一个坑。）
        //    跳一次后台线程再 mc.execute，跨线程时它才是真排队。
        final Component handle = modified;
        // ★ 不在这里动手 —— 只入队。原因见 applyOp/tick 的说明。
        enqueue(new Op(true, handle, sentText, original, -1));
        return true;
    }

    // ==================== 聊天栏改动队列 ====================
    //
    // ★★ 全项目【唯一】允许修改聊天栏（allMessages / rescaleChat）的地方，就在 tick() 里。
    //
    // 为什么要有这一层：翻译回来、回显到达，这些时刻都**可能比消息真正进聊天栏早**。
    //   - `onMessage` 是在 `ChatUtil.modifyMessage` 里被调的，比 `addMessage` 还早
    //   - 1.21.11 的聊天是**先入队、之后才处理**的
    // 以前每个调用点各自想办法"推迟一下"（跳后台线程再 mc.execute），
    // 结果就是同一个坑踩了两次（attach 挂不上、回显摘不掉），还各写一套。
    //
    // 现在统一成一句话：**改动只入队，落地只在 tick**。
    // 于是"什么时候调用"不再重要 —— 早到晚到，下一 tick 都会正确处理。

    /** 一次待落地的聊天栏改动。 */
    private static final class Op {
        /** true = 改造回显那一行；false = 挂译文。 */
        final boolean rewrite;
        /** 靠【身份】找到那一行，绝不错改到别人身上。 */
        final Component target;
        /** rewrite：我发出去的英文；attach：译文文本。 */
        final String a;
        /** rewrite：我原本打的中文；attach：没用。 */
        final String b;
        /** 译文挂在第几行（记录用）；<=0 = 不知道。 */
        final int ref;
        /** 连续几 tick 没找到目标。 */
        int misses;

        Op(boolean rewrite, Component target, String a, String b, int ref) {
            this.rewrite = rewrite;
            this.target = target;
            this.a = a;
            this.b = b;
            this.ref = ref;
        }
    }

    private static final java.util.Queue<Op> OPS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 最多等几 tick 找不到目标就放弃（消息可能被去重合并/删掉了）。 */
    private static final int OP_MAX_MISSES = 4;

    /** 队列上限：出问题时不至于无限涨。 */
    private static final int OP_QUEUE_MAX = 256;

    /** 每 tick 最多落地几条，避免一帧里改太多。 */
    private static final int OP_PER_TICK = 64;

    private static void enqueue(Op op) {
        if (OPS.size() < OP_QUEUE_MAX) {
            OPS.add(op);
        }
    }

    /**
     * <b>客户端每 tick 调一次</b>（{@code ChatPatches} 里注册）。
     *
     * <p>把队列里的改动落到聊天栏。这一 tick 里消息一定已经在聊天栏了 ——
     * 所以"调用时机"这个变量被彻底消掉，不再需要各处自己推迟。
     */
    /** lastSeq 回写节流（每 ~5 秒最多一次）。 */
    private static long lastSeqSavedAt = 0L;

    public static void tick() {
        persistLastSeq();
        if (OPS.isEmpty()) {
            flushRescale();
            return;
        }
        for (int n = 0; n < OP_PER_TICK; n++) {
            Op op = OPS.poll();
            if (op == null) {
                // ★ 2026-10-21 修：这里原来直接 return，把 tick 末尾的 flushRescale() 跳过了 →
                //   译文记录写了、聊天栏却永远不重排 → 看起来"不翻译了"。
                flushRescale();
                return;
            }
            if (!applyOp(op)) {
                if (++op.misses >= OP_MAX_MISSES) {
                    // ★ INFO 级：静默失败查起来太痛苦（这个项目已经吃过一次亏）
                    ChatPatches.LOGGER.info("[chatpatch] 聊天栏改动等不到目标，放弃（{}）: {}",
                            op.rewrite ? "改造回显" : "挂译文", op.a);
                } else {
                    OPS.add(op);   // 放回队尾，下一 tick 再试
                }
            }
        }
        flushRescale();
    }

    /** 一 tick 内攒下的"需要重排聊天栏"，统一做一次。 */
    private static boolean rescalePending = false;
    private static int rescaleEoe = -1;

    /** 登记一次重排（每 tick 只真做一次 —— rescaleChat 是全表重排，1300 条时很贵）。 */
    private static void markRescale(int eoeBefore) {
        if (!rescalePending) {
            rescalePending = true;
            rescaleEoe = eoeBefore;
        } else if (eoeBefore >= 0 && (rescaleEoe < 0 || eoeBefore < rescaleEoe)) {
            rescaleEoe = eoeBefore;
        }
    }

    /** 把当前最大 seq 回写到 5-record.json（节流 5 秒，避免每条消息写配置）。 */
    private static void persistLastSeq() {
        if (store == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSeqSavedAt < 5000L) {
            return;
        }
        try {
            obro1961.chatpatches.chatlog.RecordConfig rc = ConfigStore.record();
            int cur = store.currentMaxSeq();
            if (cur > rc.lastSeq) {
                rc.lastSeq = cur;
                ConfigStore.saveRecord();
                lastSeqSavedAt = now;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void flushRescale() {
        if (!rescalePending) {
            return;
        }
        rescalePending = false;
        int e = rescaleEoe;
        rescaleEoe = -1;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gui != null) {
                rescaleKeepingScroll(mc.gui.getChat(), e);
            }
        } catch (Exception ex) {
            ChatPatches.LOGGER.warn("[chatpatch] 重排聊天栏失败: {}", ex.toString());
        }
    }

    /** 把一条改动落到聊天栏。<b>只有 tick() 会调它。</b> */
    private static boolean applyOp(Op op) {
        long applyT0 = System.currentTimeMillis();
        try {
            Minecraft mc = Minecraft.getInstance();
            ChatComponent chat = mc.gui.getChat();
            List<GuiMessage> all = chat.allMessages;
            for (int i = 0; i < all.size(); i++) {
                GuiMessage gm = all.get(i);
                if (gm.content() != op.target) {
                    continue;
                }
                if (op.rewrite) {
                    if (op.b == null || op.b.isBlank()) {
                        return true;            // 没有原文可换，按"不用做"处理
                    }
                    String flat = op.target.getString();
                    int at = flat.lastIndexOf(op.a);
                    if (at < 0) {
                        ChatPatches.LOGGER.info("[chatpatch] 回显里找不到发出那句，保持原样: {}", op.a);
                        return true;
                    }
                    MutableComponent line = replaceRange(op.target, at, at + op.a.length(), op.b);
                    line.append(obro1961.chatpatches.util.TextUtil.translationLine(op.a));
                    // ★ 必须在 all.set 之前取：rescale 之后 trimmedMessages 就重建了
                    int eoeBefore = ChatUtil.message2Visible(i);
                    GuiMessage replaced = new GuiMessage(gm.addedTime(), line, gm.signature(), gm.tag());
                    pipeline.registerHandle(line, op.ref);
                    if(!replaceLinesInPlace(chat, i, replaced)) {
                        all.set(i, replaced);
                        markRescale(eoeBefore);   // 兜底：老路，全表重排
                    }
                    ChatPatches.LOGGER.info("[chatpatch] 已把回显改造成「中文 + ↳ 英文」: {}", op.a);
                } else {
                    // ★ 已经有译文就【不再挂第二行】（用户实测：对已翻译的句子再点"翻译这条"会出两次）
                    //   放在这一个出口，自动/手动/缓存命中三条路都被挡住，不用各处记得判。
                    if (obro1961.chatpatches.util.TextUtil.hasTranslation(op.target)) {
                        ChatPatches.LOGGER.info("[chatpatch] 这条已有译文，跳过重复挂载: {}", op.a);
                        return true;
                    }
                    MutableComponent withTr = op.target.copy();
                    withTr.append(obro1961.chatpatches.util.TextUtil.translationLine(op.a));
                    int eoeBefore = ChatUtil.message2Visible(i);
                    GuiMessage replaced = new GuiMessage(gm.addedTime(), withTr, gm.signature(), gm.tag());
                    pipeline.registerHandle(withTr, op.ref);
                    if(!replaceLinesInPlace(chat, i, replaced)) {
                        all.set(i, replaced);
                        markRescale(eoeBefore);   // 兜底：老路，全表重排
                    }
                    recordTranslation(TIME.format(LocalDateTime.now()), op.a, op.ref);
                    PENDING_TRANSLATE.remove(op.target);
                    ChatPatches.LOGGER.debug("[chatpatch] 译文已挂上: {}", op.a);
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            ChatPatches.LOGGER.warn("[chatpatch] 聊天栏改动失败（放弃这条，不影响别的）: {}", e.toString());
            return true;   // 出错就别再重试
        } finally {
            long dt = System.currentTimeMillis() - applyT0;
            if (dt >= 50) {
                ChatPatches.LOGGER.warn("[chatpatch] 卡顿: 挂译文/改造回显 {}ms（{}=[{}]）", dt, op.rewrite ? "回显" : "译文", op.a);
            }
        }
    }

    /**
     * <b>只重排被改的那一条消息</b>（根治"全表重排"）。
     *
     * <p>原版 {@code refreshTrimmedMessages()} 会把 <b>所有</b>消息重新折行一遍；
     * 而 {@code addMessageToDisplayQueue()} 的字节码告诉我们真正需要的只有三件事：
     * <pre>
     *   width  = floor(chat.getWidth() / chat.getScale())
     *   lines  = msg.splitLines(font, width)
     *   每行 Line(addedTime, seq, tag, endOfEntry = (最后一行))
     *   插入方式 List.addFirst（所以列表里【底行在前、往上是续行】）
     * </pre>
     * 于是我们只需要：定位这条消息在 {@code trimmedMessages} 里的行区间 → 删掉 →
     * 把新内容按同样规则折出来的行插回同一位置。
     *
     * <p><b>顺序必须记住</b>：{@code trimmedMessages} 里 index 0 是最新那行的底行；
     * 一条消息的 EoE 行在低位，续行在它<b>后面</b>（更高 index = 屏幕上更高）。
     * 所以插回时要从最后一行往第一行插（见下面的循环）。
     *
     * <p><b>失败就返回 false</b>，调用方退回 {@link #markRescale(int)} 走全表重排 ——
     * 宁可慢一次，也不能让聊天栏错位。
     */
    private static boolean replaceLinesInPlace(ChatComponent chat, int messageIndex, GuiMessage replacement) {
        try {
            java.util.List<GuiMessage> all = chat.allMessages;
            if (messageIndex < 0 || messageIndex >= all.size()) {
                return false;
            }
            int eoe = ChatUtil.message2Visible(messageIndex);
            if (eoe < 0) {
                return false;
            }
            java.util.List<GuiMessage.Line> vis = chat.trimmedMessages;
            if (eoe >= vis.size()) {
                return false;
            }
            int oldCount = 1;
            while (eoe + oldCount < vis.size() && !vis.get(eoe + oldCount).endOfEntry()) {
                oldCount++;
            }
            Minecraft mc = Minecraft.getInstance();
            int width = net.minecraft.util.Mth.floor(chat.getWidth() / chat.getScale());
            java.util.List<net.minecraft.util.FormattedCharSequence> seqs = replacement.splitLines(mc.font, width);
            if (seqs.isEmpty()) {
                return false;
            }
            java.util.List<GuiMessage.Line> fresh = new java.util.ArrayList<>(seqs.size());
            int last = seqs.size() - 1;
            for (int k = last; k >= 0; k--) {
                fresh.add(new GuiMessage.Line(replacement.addedTime(), seqs.get(k), replacement.tag(), k == last));
            }
            boolean focused = chat.isChatFocused();
            int oldPos = chat.chatScrollbarPos;

            all.set(messageIndex, replacement);
            for (int k = 0; k < oldCount; k++) {
                vis.remove(eoe);
            }
            vis.addAll(eoe, fresh);

            // 视口补偿：这条消息整条都在视口下方（比正在看的内容更新）时，行数变化会把视口顶动。
            int delta = fresh.size() - oldCount;
            if (focused && oldPos > 0 && eoe < oldPos && delta != 0) {
                int max = Math.max(0, vis.size() - chat.getLinesPerPage());
                chat.chatScrollbarPos = net.minecraft.util.Mth.clamp(oldPos + delta, 0, max);
            }
            return true;
        } catch (Throwable t) {
            ChatPatches.LOGGER.warn("[chatpatch] 就地重排失败，退回全表重排: {}", t.toString());
            return false;
        }
    }
    /**
     * 重新排聊天栏，但 <b>保住用户正在看的位置</b>。
     *
     * <p>为什么不能直接 {@code chat.rescaleChat()}：原版 1.21.11 的
     * {@code rescaleChat()} = {@code resetChatScroll()} + {@code refreshTrimmedMessages()}，
     * 而 {@code resetChatScroll()} 会把 {@code chatScrollbarPos} 直接清零 ——
     * 也就是 <b>把聊天栏一脚踹回最底部</b>。
     *
     * <p>原版正常收消息走的是另一条路（{@code addMessageToDisplayQueue}）：
     * 每插一行都 {@code scrollChat(1)} 补偿，所以视图不动。我们重排时必须自己补。
     *
     * <p><b>不补会怎样</b>（已用纯逻辑复刻验证，见 {@code build-check/MenuIndexTest}）：
     * 你滚上去看历史、右键某条 —— 菜单是点击那一瞬按坐标算好的；
     * 紧接着译文挂上、位置归零、屏幕跳到底 —— 菜单还悬在原地，
     * 看起来就是「右键出来的消息和实际那条不同步」。
     *
     * @param eoeBefore 这条消息的 EoE 行号，<b>必须在插入/替换之前取</b>。
     *                  传 {@code -1} 表示它当时不在可见列表里，不用补。
     */
    private static void rescaleKeepingScroll(ChatComponent chat, int eoeBefore) {
        int oldPos = chat.chatScrollbarPos;
        boolean focused = chat.isChatFocused();

        chat.rescaleChat();   // ← 这行会把 oldPos 清零（原版行为）

        // 插入点在视口下方（更靠新消息那一侧）时，视口整体被顶下去一行 → 位置 +1 才看得见同一段内容。
        // 插入点在视口内或上方时，视口下方的行没被挪，位置不用动。
        if (focused && oldPos > 0 && eoeBefore >= 0 && eoeBefore < oldPos) {
            int max = Math.max(0, chat.trimmedMessages.size() - chat.getLinesPerPage());
            chat.chatScrollbarPos = Math.min(oldPos + 1, max);
        }
    }

    /**
     * 把 {@code src} 里 {@code [from, to)} 这段字符换成 {@code replacement}，
     * <b>其余部分的样式一个不动</b>。
     *
     * <p>做法：{@code visit} 逐个"样式段"拿到文本，按字符区间切开重拼。
     * 这样时间戳的粉色、名字的白色、hover 全都留着 —— 只换掉内容那几个字。
     */
    private static MutableComponent replaceRange(Component src, int from, int to, String replacement) {
        final List<net.minecraft.network.chat.Style> styles = new java.util.ArrayList<>();
        final List<String> texts = new java.util.ArrayList<>();
        src.visit((st, tx) -> {
            styles.add(st);
            texts.add(tx);
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);

        // ★ 切片数学交给 ChatStudyPipeline.splice —— 那是纯逻辑，快速自测能覆盖到。
        //   这里只负责"按它给的片段拼 Component"，不含任何下标计算。
        MutableComponent out = Component.empty();
        for (ChatStudyPipeline.Piece p : ChatStudyPipeline.splice(texts, from, to)) {
            if (p.replacement()) {
                out.append(Component.literal(replacement).withStyle(styles.get(p.run())));
            } else {
                String t = texts.get(p.run());
                out.append(Component.literal(t.substring(p.fromInRun(), p.toInRun()))
                        .withStyle(styles.get(p.run())));
            }
        }
        return out;
    }

    public static boolean needsOutgoingTranslation(String text) {
        if (!ready() || text == null || text.isBlank()) {
            return false;
        }
        // ★ 这是我自己正要发出去的，放行（并消费掉这次登记）——
        //   否则 sendChat 会再次触发本闸门，形成无限循环。
        if (SELF_SENDING.remove(text)) {
            return false;
        }
        var c = ConfigStore.translate();
        if (!c.enabled || !c.translateOutgoing) {
            return false;
        }
        // ★ 判据转发给 ChatStudyPipeline.needsOutgoingTranslation —— 全项目只有那一份实现。
        //
        // 这里曾经自己写了一遍"不是英文就拦"，而 pipeline 那边写的是"只有含汉字才翻"。
        // 两者不一致的后果是实打实的：发一个 "2333" 时闸门拦下、那边却原样返回，
        // 于是"拦下 → 翻译 → sendChat → 又被拦"，死循环写进 44 万条记录 / 21 MB。
        //
        // ★★ 2026-10-13：改成<b>配置驱动</b> —— 语言由 translate.json 的
        //    outgoingSourceLanguage 声明（用户：「严格按照配置文件写，把配置的语言跟对照表锁死」）。
        //    原来是隐藏的启发式，à/é 那类判断反复出错且配置里看不见 ✗。
        return obro1961.chatpatches.pipeline.ChatStudyPipeline
                .needsOutgoingTranslation(text, c.outgoingSourceLanguage);
    }

    /**
     * <b>这条命令是不是"带频道的发言"，要不要拦下来翻译。</b>
     *
     * <p>有些服务器的全球频道是打成命令发的（{@code /g 内容}），命令走
     * {@code ALLOW_COMMAND}，<b>根本不经过聊天的出站闸门</b> ——
     * 于是中文原样发出去，既不翻译也不改造回显。
     * 而本地频道往往是切模式后直接打字（普通聊天），所以能正常工作。
     * <b>用户实测就是这个现象：{@code /l} 可以、{@code /g} 不行。</b>
     *
     * <p>只认配置里的 {@code channelCommands} 白名单 ——
     * {@code /msg Bob 你好} 这种绝不能整段拿去翻。
     */
    public static boolean needsCommandTranslation(String command) {
        if (!ready() || command == null) {
            return false;
        }
        // 我们自己重发的那条，放行（不做这一步就是无限循环）
        if (SELF_SENDING.remove(command)) {
            return false;
        }
        var c = ConfigStore.translate();
        if (!c.enabled || !c.translateOutgoing) {
            return false;
        }
        String[] parts = ChatStudyPipeline.splitChannelCommand(command, c.channelCommands);
        if (parts == null) {
            return false;
        }
        return ChatStudyPipeline.needsOutgoingTranslation(parts[1]);
    }

    /** 拦下频道命令后：翻译命令词之后的正文，再把整条命令重发出去。 */
    public static void sendTranslatedCommand(String command) {
        String[] parts = ChatStudyPipeline.splitChannelCommand(
                command, ConfigStore.translate().channelCommands);
        if (parts == null) {
            return;
        }
        // ★ 这条日志必须打：命令是【被我们拦下重发】的，服务器的记录里看不到用户原本打的字。
        //   实测踩过这个坑：用户报"别人看不到我说的话"，而日志里既没有 outgoing 记录、
        //   也没有 sendChat 的异常 —— 因为整条走的都是命令路，而当时这条路一条日志都没有，
        //   等于把自己变成了盲区。
        ChatPatches.LOGGER.info("[chatpatch] 拦下频道命令 /{} —— 只翻译正文，重发为 /{} <译文>: [{}]",
                parts[0], parts[0], parts[1]);
        sendTranslated(parts[1], parts[0]);
    }

    /**
     * 拦下中文原文后：翻译成英文发出去，并登记「英文 → 中文」。
     *
     * <p><b>本地不补显示任何东西</b> —— 显示交给服务器回显那一行，
     * 由 {@link #rewriteOwnEcho} 把它的内容换成中文。
     *
     * <p><b>为什么这样最好</b>：
     * <ul>
     *   <li>那一行本来就是服务器按它的排版渲染的 ⇒ <b>排版天生正确</b>，
     *       不会出现"我补的行和别人的长得不一样"</li>
     *   <li>只有<b>一行</b>，不存在"回显重复"要删</li>
     *   <li>顺序天然正确：服务器回显之后才显示，不存在本地先蹦出来再改的情况</li>
     * </ul>
     *
     * <p>最终效果：
     * <pre>
     * 你这边：  [时间]   Xiaoliyu_: 我准备去挖矿      ← 服务器那一行，内容换成了中文
     *                     ↳ I'm going mining          ← 底下补的译文
     * 别人那边：I'm going mining                       ← 实际发出去的就是英文
     * </pre>
     *
     * <p>失败一律退化：翻不出来就原样发中文（绝不吞消息）。
     */
    public static void sendTranslated(String original) {
        sendTranslated(original, null);
    }

    /**
     * @param commandHead {@code null} = 走普通聊天（{@code sendChat}）；
     *                    否则是频道命令词，发成 {@code head + " " + 英文}
     */
    private static void sendTranslated(String original, String commandHead) {
        if (!ready()) {
            return;
        }
        final ExecutorService ex = outgoingWorker != null ? outgoingWorker : worker;
        ex.execute(() -> {
            String out = pipeline.outgoing(original);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                try {
                    if (mc.player != null) {
                        // 要发出去的完整文本：普通聊天就是英文本身；频道命令是 "head 英文"
                        final String toSend = commandHead == null ? out : commandHead + " " + out;
                        // ★ 先登记"我自己要发这句"，再发 —— 发送会触发 ALLOW_CHAT / ALLOW_COMMAND，
                        //   没有这一步就是无限循环（见 SELF_SENDING 的注释）。
                        if (SELF_SENDING.size() < SELF_SENDING_MAX) {
                            SELF_SENDING.add(toSend);
                        }
                        if (commandHead == null) {
                            // ★ 先登记「这句译文对应我打的哪句」，CHAT 事件会来领（见 PENDING_OUTGOING）
                            if (out != null && !out.isBlank() && !out.equals(original)
                                    && PENDING_OUTGOING.size() < PENDING_OUTGOING_MAX) {
                                PENDING_OUTGOING.put(out, original);
                            }
                            mc.player.connection.sendChat(out);
                        } else {
                            mc.player.connection.sendCommand(toSend);
                            // 命令走 ALLOW_COMMAND，不经过 CHAT 事件 → 这里直接写双记录
                            recordOutgoingPair(original, toSend);
                        }
                    }
                    // ★ 登记「发出去的英文 → 我原本打的中文」。
                    //   服务器把它回显回来时，靠这条把那一行改造成「中文 + ↳ 英文」。
                    //   out == original（没翻 / 翻失败）时不登记 —— 那本来就是我打的英文，不用改。
                    if (out != null && !out.isBlank() && !out.equals(original)) {
                        if (SELF_TRANSLATED_SENT.size() < SELF_TRANSLATED_SENT_MAX) {
                            SELF_TRANSLATED_SENT.put(out, original);
                        }
                    }
                } catch (Exception e) {
                    ChatPatches.LOGGER.warn("[chatpatch] 发送翻译后消息失败: {}", e.toString());
                }
            });
            // ★ 这里【不】记 outgoing —— 出站记录统一由 ChatPatches 里
            //   ClientSendMessageEvents.CHAT 那一处负责（消息真的发出去才触发）。
            //   以前这里记一次、CHAT_CANCELED 又记一次，同一次发送写两条。
        });
    }

    /** 要发出去的消息：需要就翻成英文。 */
    public static String outgoing(String text) {        if (!ready()) {
            return text;
        }
        return pipeline.outgoing(text);
    }

    /**
     * 自己发出去的消息也记录一条 —— <b>由 CHAT 事件调用，代表"真的发出去了"</b>。
     *
     * <p>★ 2026-10-20（S6）：如果这句是我们翻译后发出的，就写<b>两条</b>：
     * {@code outgoing}（我原本打的原文）+ {@code translation}（实际发出去的译文，带 {@code refSeq}/{@code lang}）。
     * 以前只写一条"实际发出去的那句"，于是文件里<b>查不到我到底打了什么</b> ——
     * 今天排查缩写事故时只能用时间戳去日志里对，正是缺这一半。
     */
    public static void onOutgoing(String sent) {
        if (!ready()) {
            return;
        }
        try {
            long t0 = System.currentTimeMillis();
            String original = PENDING_OUTGOING.remove(sent);
            if (original != null && !original.equals(sent)) {
                recordOutgoingPair(original, sent);
                return;
            }
            store.append(SESSION_DAY, currentPlace,
                    LogEntry.of("outgoing", TIME.format(LocalDateTime.now()), sent));
            long dt = System.currentTimeMillis() - t0;
            if (dt >= 50) {
                ChatPatches.LOGGER.warn("[chatpatch] 卡顿: 出站记账 {}ms（渲染线程）", dt);
            }
        } catch (IOException e) {
            ChatPatches.LOGGER.warn("[chatpatch] 写出站记录失败: {}", e.toString());
        }
    }

    /**
     * 出站双记录（S6）：原文一条、实际发出的译文一条，用 {@code refSeq} 挂起来。
     *
     * <p>这正是 {@link #onOutgoing(String)} 领到"这是我翻的"时走的路；
     * 命令（{@code /g 内容}）那条路不触发 CHAT 事件，所以发送成功后直接调这里。
     */
    private static void recordOutgoingPair(String original, String sent) {
        if (!ready() || original == null || sent == null || original.equals(sent)) {
            return;
        }
        try {
            String now = TIME.format(LocalDateTime.now());
            LogEntry out = LogEntry.outgoing(now, currentPlace, original);
            int seq = store.append(SESSION_DAY, currentPlace, out);
            String lang = ConfigStore.translate() == null
                    ? null : ConfigStore.translate().outgoingTargetLanguage;
            store.append(SESSION_DAY, currentPlace,
                    ChatStudyPipeline.translationEntry(now, sent, seq, lang));
            ChatPatches.LOGGER.info("[chatpatch] 出站双记录：原文=[{}] 实际发出=[{}]", original, sent);
        } catch (IOException e) {
            ChatPatches.LOGGER.warn("[chatpatch] 写出站双记录失败: {}", e.toString());
        }
    }

    // ---- 灌回聊天栏（替代它的 ChatLog.restore）----

    /**
     * 进游戏时把记录灌回聊天栏 —— <b>这就是"重启后还能往上翻"的原理</b>。
     *
     * <p>Chat Patches 原来是读 {@code logs/chatlog.json} 整份灌回（实测 16384 条，
     * 光反序列化就要 2.4 秒，进服直接卡住）。我们改读自己的文件夹，而且：
     * <ul>
     *   <li><b>只读今天 + 当前服务器这一个文件</b>（{@code SESSION_DAY} / {@code currentPlace}），
     *       昨天的文件夹根本不碰 —— 不是"跨所有日期取最近 N 条"</li>
     *   <li>当天文件里<b>最多取最近 N 条</b>（默认 2000）：全灌进去会让进服变慢、内存吃不消，
     *       更早的靠搜索功能找</li>
     * </ul>
     *
     * <p>译文处理：{@code translation} 类型的记录<b>归属于紧挨着它的上一条</b>聊天记录，
     * 灌回时拼成同一消息的第二行。
     *
     * @param limit 当天文件里最多灌多少条
     */
    public static void restoreRecent(int limit) {
        restoreRecent(limit, true);
    }

    /** 用配置里的 {@code restoreLimit}（0 = 全灌）。启动时走这个。 */
    public static void restoreRecent() {
        restoreRecent(-1, false);
    }

    /** 本次启动是否已经灌过（D6：一次启动只灌一次）。 */
    private static volatile boolean restoredThisLaunch = false;

    /**
     * @param limit  &lt;0 = 读 {@code 5-record.json} 的 {@code restoreLimit}；
     *               {@code 0} = 全灌；&gt;0 = 只要最近这么多条
     * @param manual true = 手动重载（忽略"一次启动只灌一次"）；false = 启动时自动灌
     */
    public static void restoreRecent(int limit, boolean manual) {
        if (!ready() || restoring) {
            return;
        }
        obro1961.chatpatches.chatlog.RecordConfig rc = ConfigStore.record();
        // ★ D6：一次启动只灌一次。世界只有一条时间线，换服再灌就是把同一条线又贴一遍。
        if (!manual && rc.restoreOncePerLaunch && restoredThisLaunch) {
            ChatPatches.LOGGER.info("[chatpatch] 本次启动已经灌过历史，不再重复灌（D6）");
            return;
        }
        int effectiveLimit = limit >= 0 ? limit : rc.restoreLimit;
        // ★ 关门：灌回期间 addMessage 会走进 onMessage，不挡住记录就翻倍
        restoring = true;
        try {
            // ★★ 一条时间线（S10/D6）：
            //   ① 新布局 + 旧布局都读；
            //   ② 新旧之间去重（同一句被两份文件各写一次的情况）；
            //   ③ 按 (time, seq) 排序 —— 以前直接拼，旧文件 00:35 会排到新文件 15:37 后面，历史是倒的；
            //   ④ 截取最近 N 条。
            // ★★ 跨天也要读（R9：从今天往前按天读，凑满 restoreLimit 或读满 7 天）。
            //   2026-10-21 实测事故：0 点后启动，今天的文件还不存在，历史全在昨天的文件里 ——
            //   只读 SESSION_DAY 就等于"这次聊天记录没灌入"。
            java.util.List<ChatLogStore.Line> collected = new java.util.ArrayList<>();
            java.util.List<String> usedDays = new java.util.ArrayList<>();
            java.time.LocalDate base;
            try {
                base = java.time.LocalDate.parse(SESSION_DAY);
            } catch (Exception e) {
                base = java.time.LocalDate.now();
            }
            // 用户 2026-10-21 澄清：**今天 + 前一天**，不是我之前写的 7 天。
            int restoreDays = Math.max(1, Math.min(7, rc.restoreDays));
            for (int back = 0; back < restoreDays; back++) {
                String day = base.minusDays(back).toString();
                java.util.List<ChatLogStore.Line> fresh = store.readDay(day);
                java.util.List<ChatLogStore.Line> legacy = store.readLegacyDay(day);
                java.util.List<ChatLogStore.Line> part = obro1961.chatpatches.chatlog.RecordConfig
                        .mergeDistinct(fresh, legacy);
                if (part.isEmpty()) {
                    continue;
                }
                collected.addAll(part);
                usedDays.add(day);
                if (effectiveLimit > 0 && collected.size() >= effectiveLimit) {
                    break;
                }
            }
            List<ChatLogStore.Line> lines = obro1961.chatpatches.chatlog.RecordConfig.sortByTime(collected);
            if (lines.isEmpty()) {
                ChatPatches.LOGGER.info("[chatpatch] 今天+前一天都没有记录（{}）", currentPlace);
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            // ★ restoreLimit 数的是【消息】，不是原始记录（2026-10-21 用户：「今天不止上线这点时间」）。
            //   以前的算法 lines.size()-limit：译文/会话头/删除标记也占配额，
            //   2000 条上限实际只灌回 ~1267 条消息。这里改成从末尾往前数"消息"直到凑满。
            int from = 0;
            if (effectiveLimit > 0) {
                int counted = 0;
                from = lines.size();
                for (int k = lines.size() - 1; k >= 0; k--) {
                    LogEntry e = lines.get(k).entry();
                    from = k;
                    boolean isMessage = !e.isSession() && !e.isDeletion()
                            && !"translation".equals(e.type)
                            && e.text != null && !e.text.isBlank();
                    if (isMessage && ++counted >= effectiveLimit) {
                        break;
                    }
                }
            }
            int added = 0;

            // 顶部分界（只显示、不落盘；D14 方案 B：灰色横线，和 stock 维度分界一眼分开）
            if (rc.historyBoundary != null && rc.historyBoundary.enabled) {
                String label = usedDays.size() <= 1
                        ? (usedDays.isEmpty() ? SESSION_DAY : usedDays.getFirst())
                        : usedDays.get(usedDays.size() - 1) + " ~ " + usedDays.getFirst();
                mc.gui.getChat().addMessage(Component.literal(rc.startText(label)));
            }

            // ★ 先把译文归位：新记录认 {@code refSeq}（哪条消息的 seq），旧记录认 {@code ref}（第几行）。
            //
            //   以前靠"紧接着的下一行"，可译文是**异步**回来的、会插队 ——
            //   实测那份记录里就串过：
            //     14:32:33 chat InkQuill: gg
            //     14:32:35 chat TikleMyPic: gg      ← 插进来了
            //     14:32:29 chat Second_Second: wb
            //     14:32:39 translation 欢迎回来      ← 其实是 Second_Second 的
            //   按"下一行"配，这条译文会被安到 TikleMyPic 头上。
            //
            //   ★ M2b 之后：新写入的译文带 refSeq，必须按 seq 查；只有旧文件才用行号。
            //     两套编号不能混用 —— seq=1 的第一条消息可能是文件第 2 行（前面有 session 头）。
            java.util.Map<String, String> trBySeq = new java.util.HashMap<>();   // 键 = 日期#seq（seq 按天重置，不能只用 seq）
            for (ChatLogStore.Line l : lines) {
                LogEntry t = l.entry();
                if (!"translation".equals(t.type) || t.text == null) {
                    continue;
                }
                if (t.refSeq != null) {
                    trBySeq.putIfAbsent(obro1961.chatpatches.chatlog.RecordConfig.pairKey(l.day(), t.refSeq), t.text);
                }
            }

            for (int i = from; i < lines.size(); i++) {
                LogEntry e = lines.get(i).entry();

                if (e.isSession() || e.isDeletion()) {
                    continue;   // 会话头和删除标记不进聊天栏
                }
                if ("translation".equals(e.type)) {
                    continue;   // 译文不单独成条，下面遇到聊天记录时会顺带找它
                }
                String text = e.text;
                if (text == null || text.isBlank()) {
                    continue;
                }

                // ★ 优先用存下来的 Component JSON 还原 —— 颜色 / 悬浮 / 点击 / 插入才保得住。
                //
                // 只用 text（纯字符串）的话，时间戳的粉色、名字的白色、hover、
                // 右键建议命令全没了 —— 灌回的历史看起来就是"素的"，和实时消息不是一个样子。
                // 原版 Chat Patches 存的就是这种结构，所以它灌回来是保真的。
                // 旧记录没有 comp → 回落到 literal(text)，内容还在，只是没样式。
                MutableComponent c = null;
                if (e.comp != null && !e.comp.isBlank()) {
                    Component parsed = componentFromJson(e.comp);
                    if (parsed != null) {
                        c = parsed.copy();
                    }
                }
                if (c == null) {
                    c = Component.literal(text);
                }

                // ★ 译文只按【seq】配 —— 启动时的 v1 迁移已经把所有旧的"行号引用"换成了 refSeq。
                String tr = e.seq == null ? null : trBySeq.get(
                        obro1961.chatpatches.chatlog.RecordConfig.pairKey(lines.get(i).day(), e.seq));
                if (tr != null) {
                    c.append(obro1961.chatpatches.util.TextUtil.translationLine(tr));
                }

                mc.gui.getChat().addMessage(c, null, ChatLog.RESTORED_INDICATOR);
                added++;
            }
            // 底部分界（只显示、不落盘）
            if (rc.historyBoundary != null && rc.historyBoundary.enabled) {
                mc.gui.getChat().addMessage(Component.literal(rc.endText()));
            }
            restoredThisLaunch = true;
            ChatPatches.LOGGER.info("[chatpatch] 灌回 {} 条 —— 今天 {} / {}（新+旧布局共 {} 条，上限 {}）",
                    added, usedDays.isEmpty() ? SESSION_DAY : usedDays.getFirst(), currentPlace, lines.size(),
                    effectiveLimit <= 0 ? "全部" : String.valueOf(effectiveLimit));
        } catch (Exception e) {
            ChatPatches.LOGGER.warn("[chatpatch] 灌回历史失败: {}", e.toString());
        } finally {
            restoring = false;   // ★ 开门
        }
    }

    // ---- 手动朗读 / 停止（右键菜单用）----

    /**
     * 右键菜单「🔊 朗读这条」。收的是那条消息的<b>整串渲染文本</b>。
     *
     * <p><b>清洗交给 {@link ChatStudyPipeline#menuSpeech}</b> —— 里面会切掉时间戳和译文那一行、
     * 剥掉颜色码/图标/下划线/表情。以前这里直接把原串丢给合成，
     * 结果是<b>时间戳被念出来、带译文的还接着念译文</b>（实测日志实证）。
     */
    public static void speakNow(String rawLine) {
        if (!ready() || rawLine == null || rawLine.isBlank()) {
            return;
        }
        ChatStudyPipeline.Speech s = pipeline.menuSpeech(rawLine);
        if (s == null || s.text() == null || s.text().isBlank()) {
            return;
        }
        // 读了「读译文」就把这一行的译文一起念（历史消息没有翻译回调，只能在这里补）
        ReadAloudConfig ra = ConfigStore.readAloud();
        String translated = null;
        if (ra != null && ra.readTranslation) {
            int mark = rawLine.indexOf(obro1961.chatpatches.pipeline.ChatStudyPipeline.TRANSLATION_MARK);
            if (mark >= 0) {
                translated = rawLine.substring(mark + obro1961.chatpatches.pipeline.ChatStudyPipeline.TRANSLATION_MARK.length()).trim();
            }
        }
        final String trText = translated;
        final String trLang = ConfigStore.translate().targetLanguage;
        ttsWorker.execute(() -> {
            doSpeak(s.text(), s.voiceLang());
            if (trText != null && !trText.isBlank()) {
                doSpeak(trText, trLang);
            }
        });
    }

    /**
     * 右键菜单「翻译这条」（R16 点击后翻译）。
     *
     * <p>收的是那条消息的整串渲染文本（含时间戳/名字/可能已挂的译文行）。
     * 清洗顺序和自动翻译一致：{@code plain → stripTimestamp → 切掉 '\n' 之后}，
     * 再 {@code parse} 出"说的那句话" —— <b>不把名字和时间戳送进翻译</b>。
     *
     * <p>{@code seq} 从 pipeline 的 handle→seq 映射里取：取不到（重启后灌回的旧消息）就传 -1，
     * 落盘时退回"下一行"配对；其余照常。
     */
    public static void translateNow(Component line) {
        if (!ready() || line == null) {
            return;
        }
        try {
            String body = ChatStudyPipeline.stripTimestamp(ChatStudyPipeline.plain(line.getString()));
            int cut = body.indexOf('\n');
            if (cut >= 0) {
                body = body.substring(0, cut);
            }
            ChatStudyPipeline.Parsed parsed = ChatStudyPipeline.parse(null, body);
            String content = parsed.content() == null ? "" : parsed.content().trim();
            if (content.isEmpty()) {
                ChatPatches.LOGGER.info("[chatpatch] 翻译这条：没解析出内容，忽略");
                return;
            }
            // ★ 两条守卫（用户实测：「一句话已经翻译了，会出两次」）：
            //   ① 已经有译文行 → 不再翻（翻了也是重复挂）；
            //   ② 上一次请求还没回来 → 别重复发（连点两下会打两次 API，回来挂两行）。
            if (obro1961.chatpatches.util.TextUtil.hasTranslation(line)) {
                ChatPatches.LOGGER.info("[chatpatch] 翻译这条：这条已经有译文了，跳过");
                return;
            }
            long now = System.currentTimeMillis();
            PENDING_TRANSLATE.entrySet().removeIf(e -> now - e.getValue() > PENDING_TRANSLATE_TTL_MS);
            if (PENDING_TRANSLATE.containsKey(line)) {
                ChatPatches.LOGGER.info("[chatpatch] 翻译这条：上一次请求还没回来，跳过重复请求");
                return;
            }
            PENDING_TRANSLATE.put(line, now);
            int seq = pipeline.seqOf(line);
            ChatPatches.LOGGER.info("[chatpatch] 手动翻译: seq={} 内容=[{}]", seq, content);
            pipeline.translateMessage(line, content, seq);
        } catch (Exception e) {
            ChatPatches.LOGGER.warn("[chatpatch] 手动翻译失败: {}", e.toString());
        }
    }

    /**
     * 删除一条消息时落一条 {@code delete{targetSeq}}（R7 / D16）。
     *
     * <p>以前聊天栏删了、文件里什么都没记 —— 灌回时它又回来了，而且"谁删的、何时删的"查不到。
     * 现在先按 handle→seq 拿到目标消息，再 {@code markDeletedSeq} 追加一条记号（不改原行，S7）。
     *
     * <p>拿不到 seq（重启后灌回的历史消息没有映射）时<b>只记日志不瞎写</b> ——
     * 写错 targetSeq 会删掉别人。
     */
    public static void noteDeletion(Component line) {
        if (!ready() || line == null) {
            return;
        }
        int refSeq = pipeline.seqOf(line);
        if (refSeq <= 0) {
            ChatPatches.LOGGER.info("[chatpatch] 删除的这条没有 seq（多为灌回的历史消息），只删屏上、不写 delete 记号");
            return;
        }
        String by = "?";
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                by = mc.player.getName().getString();
            }
        } catch (Exception ignored) {
        }
        try {
            store.markDeletedSeq(SESSION_DAY, refSeq, by, TIME.format(LocalDateTime.now()));
            ChatPatches.LOGGER.info("[chatpatch] 已记删除记号: targetSeq={} by={}", refSeq, by);
        } catch (IOException e) {
            ChatPatches.LOGGER.warn("[chatpatch] 写删除记号失败: {}", e.toString());
        }
    }
    public static void stopSpeaking() {
        if (player != null) {
            player.stopAll();
        }
    }

    public static int pendingAudio() {
        return player == null ? 0 : player.pending();
    }

    public static void shutdown() {
        if (player != null) {
            player.shutdown();
        }
        for (ExecutorService ex : new ExecutorService[]{worker, ttsWorker, outgoingWorker}) {
            if (ex != null) {
                ex.shutdownNow();
                try {
                    ex.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ================= 内部实现 =================

    /** 流水线的出口：把决定作用到 MC 上。 */
    private static final class McSink implements ChatStudyPipeline.Sink<Component> {

        @Override
        public boolean isSelf(String speaker) {
            // 说话人是不是本地玩家 —— 服务器会把你发的消息原样广播回来。
            // 判据转发给 isOwnName：".isSelf" 和"摘掉自己的回显"必须用同一份实现。
            return ChatStudyBridge.isOwnName(speaker);
        }

        @Override
        public int persist(LogEntry entry) {
            try {
                // ★ 把行号带回去：译文落盘时要写进 ref，配对就不靠"下一行"了
                return store.append(SESSION_DAY, currentPlace, entry);
            } catch (IOException e) {
                ChatPatches.LOGGER.warn("[chatpatch] 落盘失败: {}", e.toString());
                return -1;
            }
        }

        @Override
        public void speak(String speaker, String text, String voiceLang) {
            // 自己发出去的话要不要念 —— <b>这个决定不在这里</b>。
            //
            // 判断已经搬到 {@code ChatStudyPipeline.planSpeech()}（用 {@code Sink.isSelf}）。
            // 放在这里就是"同一个决定有两个出口"：pipeline 说读、这里又否掉，
            // 加规则时很容易只改一处 —— 这条规则以前就是这么被写岔的。
            //
            // ★ 朗读没有日志 = 瞎着调试。之前排查"为什么读了表情""为什么念下划线"
            // 全靠猜，就是因为这一步是黑盒。打出来，下次一眼就能看到到底在读什么。
            ChatPatches.LOGGER.info("[chatpatch] 朗读: {}", text);
            // ★ 这一段是在【渲染线程】上被调的（消息从 modifyMessage 下来）。
            // 语音合成要起外部进程、几百毫秒起步，绝不能在这儿同步做 —— 那会卡住游戏。
            // 丢给专门的 TTS 线程。
            final ExecutorService ex = ttsWorker;
            if (ex != null && !ex.isShutdown()) {
                ex.execute(() -> doSpeak(text, voiceLang));
            }
        }

        @Override
        public void attachTranslation(Component handle, String translation, int refLine) {
            // ★ 只入队，由 tick() 落地 —— 这一个出口同时覆盖了两种调用时机：
            //   · 缓存命中是【同步】调用的，那时消息还没进聊天栏（我们比 addMessage 早）
            //   · 调 API 是异步的，译文回来时消息早就进去了
            // 以前这两种时机各自想办法推迟（跳后台线程再 mc.execute），
            // 结果同步那条路漏了 → 只有"重复的句子"没译文。现在时机不再重要。
            obro1961.chatpatches.pipeline.SelfMessages.mark(translation);
            enqueue(new Op(false, handle, translation, null, refLine));
        }

        @Override
        public void notice(String text) {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                try {
                    mc.gui.getChat().addMessage(Component.literal(text));
                } catch (Exception ignored) {
                    // 聊天栏还没准备好就算了
                }
            });
        }
    }

    /** 流水线的翻译能力：接到现有的 TranslationManager。 */
    private static final class McTranslator implements ChatStudyPipeline.Translator {
        @Override
        public String inbound(String text) {
            return TranslationManager.translate(text, List.of());
        }

        @Override
        public String outbound(String text, String targetLanguage) {
            return TranslationManager.translateOutgoing(text, targetLanguage, List.of());
        }
    }

    /**
     * 把译文挂成<b>同一条消息的第二行</b>。
     *
     * <p>做法：在末尾追加一个 sibling，内容以 {@code \n} 开头 —— 这样<b>一个聊天条目渲染成两行</b>，
     * 不会多出一条消息。好处：
     * <ul>
     *   <li>不影响它的重复计数（那个只读 siblings[1]）</li>
     *   <li>不影响它的聊天记录（我们不用它存）</li>
     *   <li>右键菜单的"复制原始/格式化/无时间戳"<b>自动带上译文</b></li>
     *   <li>搜索也能搜到译文</li>
     * </ul>
     *
     * <p><b>注意</b>：落地逻辑已经统一并进 {@link #applyOp} —— 那里是全项目唯一
     * 允许改聊天栏的地方，且只在 {@link #tick()} 里跑。这里不再单独实现一份。
     */

    /** 真正去合成 + 播放。按配置顺序降级。 */
    private static void doSpeak(String text, String voiceLang) {
        if (player == null || player.isMuted()) {
            return;
        }
        java.util.List<ApiProvider> speakList = ConfigStore.speakOrder();
        ChatPatches.LOGGER.info("[chatpatch] 朗读候选: {}", speakList.stream().filter(java.util.Objects::nonNull).map(x -> x.name + "(" + x.type + "," + (x.baseUrl == null ? "" : x.baseUrl) + ",enabled=" + x.enabled + ")").toList());
        for (ApiProvider p : speakList) {
            if (p == null || !p.canSpeak()) {
                continue;
            }
            if (obro1961.chatpatches.translate.ServiceWatchdog.isKnownDown(p)) {
                ChatPatches.LOGGER.info("[chatpatch] {} 已知连不上，跳过", p.name);
                continue;
            }
            try {
                // ★ 统一入口：查能力表 → 决定“整句一次还是分段并行”、并发度、语言支持
                obro1961.chatpatches.tts.EngineCaps.Caps caps = obro1961.chatpatches.tts.EngineCaps.of(p);
                obro1961.chatpatches.tts.SpeechPipeline.Result res =
                        obro1961.chatpatches.tts.SpeechPipeline.speak(text, caps,
                                (segText, segLang) -> synthOne(p, segText, segLang, voiceLang), langHintOf(voiceLang));
                if (res.unsupported()) {
                    ChatPatches.LOGGER.info("[chatpatch] {} 不支持这句话的语言，跳过", p.name);
                    continue;
                }
                if (res.pcm() != null && res.pcm().length > 0) {
                    ChatPatches.LOGGER.info("[chatpatch] 朗读: {} 分段={} 并发={} 音频={} 字节 PCM",
                            p.name,
                            obro1961.chatpatches.tts.SpeechSegmenter.split(text).size(),
                            caps.concurrency(), res.pcm().length);
                    player.enqueue(res.pcm(), true);
                    return;
                }
                // 非 WAV（如 mp3）或整句也失败：原样播一次，交给播放层
                player.enqueue(synthOne(p, text, voiceLang, voiceLang), false);
                return;
            } catch (Exception e) {
                ChatPatches.LOGGER.warn("[chatpatch] 朗读引擎 {} 失败，试下一个: {}", p.name, e.toString());
            }
        }
    }

    /** 语音语言名 → 语言码（原文=源语言、译文=目标语言）。 */
    private static String langHintOf(String lang) {
        return obro1961.chatpatches.translate.DialogLanguages.codeOf(lang);
    }

    /** 单个引擎的一次合成：分段与整句共用，所有类型判断集中在这里。 */
    private static byte[] synthOne(ApiProvider p, String text, String lang, String langHint) throws Exception {
        if ("local".equalsIgnoreCase(p.type) || "system".equalsIgnoreCase(p.type)) {
            return LocalTts.synthesize(text, p.voice, lang);
        }
        if ("piper".equalsIgnoreCase(p.type) && !obro1961.chatpatches.tts.PiperTtsProvider.hasHttp(p)) {
            return new obro1961.chatpatches.tts.PiperTtsProvider(p).synthesize(text, lang);
        }
        if ("sovits".equalsIgnoreCase(p.type) || (p.baseUrl != null && p.baseUrl.contains("9880"))) {
            return new obro1961.chatpatches.tts.SovitsTtsProvider(p).synthesize(text, lang);
        }
        // OpenAI 兼容（含 piper_server / Kokoro）
        String url = obro1961.chatpatches.tts.PiperTtsProvider.effectiveUrl(p).isBlank() ? p.baseUrl : obro1961.chatpatches.tts.PiperTtsProvider.effectiveUrl(p);
        String voiceName = (p.extraBody != null && p.extraBody.get("voice_name") != null)
                ? String.valueOf(p.extraBody.get("voice_name")).trim() : "";
        String configuredVoice = !voiceName.isBlank() ? voiceName : (p.voice == null ? "" : p.voice);
        String voice = obro1961.chatpatches.tts.ServiceVoices.pick(url, configuredVoice, lang);
        String origUrl = p.baseUrl;
        if ("piper".equalsIgnoreCase(p.type)) {
            p.baseUrl = obro1961.chatpatches.tts.PiperTtsProvider.effectiveUrl(p);
        }
        try {
            return new OpenAiTtsProvider(p).synthesize(text, voice);
        } finally {
            p.baseUrl = origUrl;
        }
    }

    // ---- 工具 ----

    public static void logEntry(Component c, String type) {
        // 供将来扩展
    }

    /** 便于测试/自检：当前状态一行字。 */
    public static String statusLine() {
        if (!ready()) {
            return "§c[chatpatch] 未初始化";
        }
        return "§6[chatpatch] §7今天 §f" + SESSION_DAY
                + " §7地点 §f" + currentPlace
                + " §7| 翻译 " + (ConfigStore.translate().enabled ? "§a开" : "§c关")
                + " §7朗读 " + (ConfigStore.readAloud().enabled ? "§a开" : "§c关")
                + " §7| 待播 " + pendingAudio();
    }

    /** 供右键菜单用：这条消息的纯文本（剥掉颜色码与图标）。 */
    public static String plainOf(Component c) {
        return ChatStudyPipeline.plain(c == null ? null : c.getString());
    }

    static SpeechPolicy policyForMenu() {
        return null;
    }
}

