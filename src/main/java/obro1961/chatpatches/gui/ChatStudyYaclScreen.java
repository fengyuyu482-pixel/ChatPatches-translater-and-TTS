package obro1961.chatpatches.gui;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.LabelOption;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.gui.YACLScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import obro1961.chatpatches.ChatPatches;
import obro1961.chatpatches.chatlog.RecordConfig;
import obro1961.chatpatches.config.Config;
import obro1961.chatpatches.translate.ApiProvider;
import obro1961.chatpatches.translate.ConfigStore;
import obro1961.chatpatches.translate.TranslateConfig;
import obro1961.chatpatches.tts.ReadAloudConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 局内配置（YACL 版）。
 *
 * <p>分类顺序：翻译 → 朗读 → 右键菜单 → 开发者。
 * 条目顺序 = 代码添加顺序；主开关关掉时，子项通过 {@code available(false)} 变灰。
 */
public final class ChatStudyYaclScreen {
    /** 上一次打开配置界面时的父界面，选择语言后用来重建界面。 */
    private static net.minecraft.client.gui.screens.Screen LAST_PARENT;

    /** 四项语言上次已提示的值；只对真正变化的那一项发提示。 */
    private static String LAST_SOURCE_LANG;
    private static String LAST_TARGET_LANG;
    private static String LAST_OUT_SOURCE_LANG;
    private static String LAST_OUT_TARGET_LANG;

    private static final List<String> SRC_AUTO = List.of("自动检测", "中文", "英语", "日语", "韩语", "俄语", "西班牙语", "法语", "越南语");
    private static final List<String> LANGS = List.of("中文", "英语", "日语", "韩语", "俄语", "西班牙语", "法语", "越南语");
    private static final List<String> TARGETS = List.of("简体中文", "English", "日本語", "한국어", "Русский", "Español", "Français", "Tiếng Việt");
    private static final List<String> TARGETS_AUTO = List.of("自动检测", "简体中文", "English", "日本語", "한국어", "Русский", "Español", "Français", "Tiếng Việt");

    private ChatStudyYaclScreen() {
    }

    private static String currentLanguage() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        return mc == null || mc.getLanguageManager() == null ? "" : mc.getLanguageManager().getSelected();
    }

    /** YACL 会把分组标题缓存成 MultiLineLabel；语言变化时重建整个配置屏。 */
    private static final class LanguageAwareScreen extends YACLScreen {
        private final Screen parent;
        private String language;

        LanguageAwareScreen(YetAnotherConfigLib config, Screen parent) {
            super(config, parent);
            this.parent = parent;
            this.language = currentLanguage();
        }

        @Override
        public void tick() {
            super.tick();
            String now = currentLanguage();
            if (!now.equals(language)) {
                language = now;
                if (this.minecraft != null) {
                    this.minecraft.setScreen(build(parent));
                }
            }
        }
    }
    public static Screen build(Screen parent) {
        LAST_PARENT = parent;
        TranslateConfig t = ConfigStore.translate();
        captureLanguageSnapshot(t);
        ReadAloudConfig r = ConfigStore.readAloud();
        RecordConfig rec = ConfigStore.record();
        Config c = ChatPatches.config;

        // 打开界面时探一次本机常见端口：服务在跑就直接启用对应条目（用户只点开关就能用）
        autoDetectLocal(t, r);        Runnable save = () -> {
            ConfigStore.saveTranslate();
            ConfigStore.saveReadAloud();
            ConfigStore.saveRecord();
            Config.serialize();
            obro1961.chatpatches.chatstudy.ChatStudyBridge.applyConfigNow();

            // ★ 保存后给一次“语言识别”反馈：认得出就说认成了什么，认不出就说清哪一项、支持哪些
            reportLanguages(t);
        };

        YetAnotherConfigLib yacl = YetAnotherConfigLib.createBuilder()
                .title(tr("聊天补丁 · 详细配置"))
                .category(translateCategory(t))
                .category(readAloudCategory(r))
                .category(contextCategory(c))
                .category(developerCategory(c, rec))
                .save(save)
                .build();
        return new LanguageAwareScreen(yacl, parent);
    }

    // ------------------------------------------------------------ 翻译

    private static ConfigCategory translateCategory(TranslateConfig t) {
        ApiProvider online = ensureProvider(t, "在线", "api");
        ApiProvider local = ensureProvider(t, "本地", "local");
        ApiProvider hunyuan = ensureProvider(t, "混元", "local");
        ApiProvider free1 = ensureProvider(t, "免费槽位1", "api");
        ApiProvider bili = ensureProvider(t, "bilibili", "api");
        ApiProvider mymemory = ensureProvider(t, "mymemory", "api");

        return ConfigCategory.createBuilder()
                .name(tr("翻译"))
                .group(OptionGroup.createBuilder()
                        .name(tr("基础"))
                        .option(bool("启用翻译", "总开关；关掉后自动翻译与出站翻译都不生效", true,
                                () -> t.enabled, v -> { t.enabled = v; kickAllDetect(v); }))
                        .option(bool("自动翻译", "收到消息自动翻译", true,
                                () -> t.autoEnabled, v -> t.autoEnabled = v))
                        .option(lang("入站源语言", "别人说的话本来是什么语言。支持语言：英语/English、简体中文/中文、日语/日本語、韩语/한국어、俄语、西班牙语、法语、德语、葡萄牙语、意大利语、越南语、泰语、阿拉伯语、土耳其语、印尼语；也可填「自动检测」", t.sourceLanguage, true,
                                () -> t.sourceLanguage, v -> t.sourceLanguage = v))
                        .option(lang("入站目标语言", "翻译成什么语言。支持语言：英语/English、简体中文/中文、日语/日本語、韩语/한국어、俄语、西班牙语、法语、德语、葡萄牙语、意大利语、越南语、泰语、阿拉伯语、土耳其语、印尼语（不能填自动检测）", t.targetLanguage, false,
                                () -> t.targetLanguage, v -> t.targetLanguage = v))
                        .option(bool("翻译系统消息", "服务器公告等系统消息也翻译", false,
                                () -> t.translateSystemMessages, v -> t.translateSystemMessages = v))
                        .option(bool("翻译自己说的话", "默认关：自己打的字不必再翻。打开后连服务器回显给你的那条也翻 —— 注意出站已经翻过一次，可能出现往返失真", false,
                                () -> t.translateOwnMessages, v -> t.translateOwnMessages = v))
                        .option(bool("出站翻译", "自己发的话自动翻成对方语言", true,
                                () -> t.translateOutgoing, v -> t.translateOutgoing = v))
                        .option(lang("出站源语言", "我打的话是什么语言。支持语言：英语/English、简体中文/中文、日语/日本語、韩语/한국어、俄语、西班牙语、法语、德语、葡萄牙语、意大利语、越南语、泰语、阿拉伯语、土耳其语、印尼语", t.outgoingSourceLanguage, false,
                                () -> t.outgoingSourceLanguage, v -> t.outgoingSourceLanguage = v))
                        .option(lang("出站目标语言", "翻成对方语言。支持语言：英语/English、简体中文/中文、日语/日本語、韩语/한국어、俄语、西班牙语、法语、德语、葡萄牙语、意大利语、越南语、泰语、阿拉伯语、土耳其语、印尼语", t.outgoingTargetLanguage, false,
                                () -> t.outgoingTargetLanguage, v -> t.outgoingTargetLanguage = v))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(tr("在线翻译"))
                        .option(bool("启用在线翻译", "任意 OpenAI 兼容的云服务：DeepSeek（https://platform.deepseek.com ，模型 deepseek-chat）、硅基流动、各类中转站；地址通常以 /v1 结尾", online.enabled,
                                () -> online.enabled, v -> { online.enabled = v; kickDetect(online, v); }))
                        .option(str("模型名", "例如 deepseek-chat", "deepseek-chat",
                                () -> online.model, v -> online.model = v))
                        .option(str("URL", "OpenAI 兼容地址", "https://api.deepseek.com/v1",
                                () -> online.baseUrl, v -> online.baseUrl = v))
                        .option(str("API Key", "明文保存", "",
                                () -> online.apiKey, v -> online.apiKey = v))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(tr("本地翻译"))
                        .option(bool("启用本地翻译", "任意 OpenAI 兼容的本地模型：Ollama（https://ollama.com ，默认 11434）、LM Studio（1234）、vLLM（8000）。能翻哪些语言取决于你装的模型", local.enabled,
                                () -> local.enabled, v -> { local.enabled = v; kickDetect(local, v); }))
                        .option(str("模型名", "本地模型名，例如 qwen2.5:7b", "qwen2.5:7b",
                                () -> local.model, v -> local.model = v))
                        .option(str("地址", "例如 http://127.0.0.1:11434/v1", "http://127.0.0.1:11434/v1",
                                () -> local.baseUrl, v -> local.baseUrl = v))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(tr("混元专属（Hy-MT2）"))
                        .option(bool("启用混元", "混元 Hy-MT2 是腾讯的翻译专用小模型（1.8B，Q4 约 1GB），只做翻译、不做朗读。模型：https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF （国内可用 https://hf-mirror.com）", hunyuan.enabled,
                                () -> hunyuan.enabled, v -> { hunyuan.enabled = v; kickDetect(hunyuan, v); }))
                        .option(str("模型名", "默认 Hy-MT2-1.8B", "Hy-MT2-1.8B",
                                () -> hunyuan.model, v -> hunyuan.model = v))
                        .option(str("地址", "默认 http://127.0.0.1:8090/v1", "http://127.0.0.1:8090/v1",
                                () -> hunyuan.baseUrl, v -> hunyuan.baseUrl = v))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(tr("免费 API"))
                        .option(bool("启用免费 API", "关掉后 bilibili / mymemory / 免费槽 全部不参与", true,
                                () -> t.freeApiEnabled, v -> t.freeApiEnabled = v))
                        .option(str("免费API 1 名字", "留空 = 不使用", "",
                                () -> free1.name, v -> free1.name = v))
                        .option(str("免费API 1 地址", "自填", "",
                                () -> free1.baseUrl, v -> free1.baseUrl = v))
                        .option(str("免费API 2 名字", "默认 bilibili", "bilibili",
                                () -> bili.name, v -> bili.name = v))
                        .option(str("免费API 2 地址", "bilibili 接口地址", "https://index-translate.bilibili.com/v1",
                                () -> bili.baseUrl, v -> bili.baseUrl = v))
                        .option(str("免费API 3 名字", "默认 mymemory", "mymemory",
                                () -> mymemory.name, v -> mymemory.name = v))
                        .option(str("免费API 3 地址", "mymemory GET 地址（含 {text} 占位）", "https://api.mymemory.translated.net/get?q={text}&langpair={langpair}",
                                () -> mymemory.isGetType() ? mymemory.endpoint : mymemory.baseUrl, v -> { if (mymemory.isGetType()) mymemory.endpoint = v; else mymemory.baseUrl = v; }))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(tr("高级"))
                        .option(integer("429 退避起点 (ms)", "被限流后的第一次等待", t.rateLimitBackoffMs, 1000, 60000, 1000,
                                () -> t.rateLimitBackoffMs, v -> t.rateLimitBackoffMs = v))
                        .option(integer("429 退避上限 (ms)", "退避最长等待", t.rateLimitBackoffMaxMs, 30000, 300000, 5000,
                                () -> t.rateLimitBackoffMaxMs, v -> t.rateLimitBackoffMaxMs = v))
                        .collapsed(true)
                        .build())
                .build();
    }
    /** 下拉候选：第一个是空（自动），其余是槽位名。 */
    private static List<String> providerNames(TranslateConfig t, boolean forTts) {
        List<String> names = new ArrayList<>();
        names.add("");
        for (ApiProvider p : t.providers) {
            if (p == null || p.name == null || p.name.isEmpty()) {
                continue;
            }
            if (forTts && p.canSpeak()) {
                names.add(p.name);
            } else if (!forTts) {
                names.add(p.name);
            }
        }
        return names;
    }
    // ------------------------------------------------------------ 朗读

    private static ConfigCategory readAloudCategory(ReadAloudConfig r) {
        ApiProvider onlineTts = ensureProvider(r, "在线朗读", "openai");
        ApiProvider sovits = ensureProvider(r, "GPT-SoVITS", "sovits");
        ApiProvider kokoro = ensureProvider(r, "Kokoro", "kokoro");
        ApiProvider piper = ensureProvider(r, "Piper", "piper");
        ApiProvider sysVoice = ensureProvider(r, "系统语音", "system");

        return ConfigCategory.createBuilder()
                .name(tr("朗读"))
                .group(OptionGroup.createBuilder()
                        .name(tr("基础"))
                        .option(bool("启用朗读", "TTS 总开关；关掉后右键「朗读这条」仍可用", true,
                                () -> r.enabled, v -> { r.enabled = v; kickAllDetect(v); }))
                        .option(bool("读原文", "朗读原消息（名字 + 原文）", true,
                                () -> r.readOriginal, v -> r.readOriginal = v))
                        .option(bool("读译文", "在原文之后朗读译文", false,
                                () -> r.readTranslation, v -> r.readTranslation = v))
                        .option(bool("读自己的消息", "自己发的也朗读", true,
                                () -> r.speakOwnMessages, v -> r.speakOwnMessages = v))
                        .option(bool("读系统消息", "服务器公告等系统消息也朗读", false,
                                () -> r.speakSystemMessages, v -> r.speakSystemMessages = v))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(tr("参数"))
                        .option(integer("最短长度", "太短的消息不朗读", r.minLength, 1, 50, 1,
                                () -> r.minLength, v -> r.minLength = v))
                        .option(integer("最长长度", "超长消息截断", r.maxLength, 50, 1000, 10,
                                () -> r.maxLength, v -> r.maxLength = v))
                        .option(flt("音量", "朗读音量", r.volume, 0f, 1f, 0.05f,
                                () -> r.volume, v -> r.volume = v))
                        .option(integer("超时 (ms)", "单次合成超时", r.timeoutMs, 5000, 60000, 1000,
                                () -> r.timeoutMs, v -> r.timeoutMs = v))
                        .option(integer("缓存条数", "0 = 不缓存", r.cacheSize, 0, 256, 8,
                                () -> r.cacheSize, v -> r.cacheSize = v))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(tr("在线朗读（OpenAI 兼容）"))
                        .option(bool("启用在线朗读", "关闭 = 不用这一栏", onlineTts.enabled,
                                () -> onlineTts.enabled, v -> { onlineTts.enabled = v; kickDetect(onlineTts, v); }))
                        .option(str("模型名", "例如 tts-1 / kokoro；留空 = kokoro", "tts-1",
                                () -> onlineTts.ttsModel, v -> onlineTts.ttsModel = v))
                        .option(str("地址", "https://api.openai.com/v1 或本地兼容服务", "https://api.openai.com/v1",
                                () -> onlineTts.baseUrl, v -> onlineTts.baseUrl = v))
                        .option(str("API Key", "明文保存，本地服务可空", "",
                                () -> onlineTts.apiKey, v -> onlineTts.apiKey = v))
                        .option(str("音色", "留空 = 自动（服务端 /health）", "alloy",
                                () -> extraOf(onlineTts, "voice_name"), v -> setExtraOf(onlineTts, "voice_name", v)))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(tr("GPT-SoVITS"))
                        .option(bool("启用 GPT-SoVITS", "GPT-SoVITS 声音克隆 TTS：支持中文/日语/英语/韩语/粤语。项目：https://github.com/RVC-Boss/GPT-SoVITS （api.py 默认端口 9880）", sovits.enabled,
                                () -> sovits.enabled, v -> { sovits.enabled = v; kickDetect(sovits, v); }))
                        .option(str("地址", "例如 http://127.0.0.1:9880", "http://127.0.0.1:9880",
                                () -> sovits.baseUrl, v -> sovits.baseUrl = v))
                        .option(str("参考音频", "服务器本地 wav 绝对路径（必填）", "",
                                () -> sovits.voice, v -> sovits.voice = v))
                        .option(str("参考文本", "参考音频里那句话，可留空", "",
                                () -> promptOf(sovits), v -> setPromptOf(sovits, v)))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(tr("Kokoro"))
                        .option(bool("启用 Kokoro", "Kokoro 是轻量开源 TTS：支持英语/中文/日语/西班牙语/法语等。服务端：https://github.com/remsky/Kokoro-FastAPI （默认端口 8880，若用其它端口请改地址）", kokoro.enabled,
                                () -> kokoro.enabled, v -> { kokoro.enabled = v; kickDetect(kokoro, v); }))
                        .option(str("地址", "默认 http://127.0.0.1:8101", "http://127.0.0.1:8101",
                                () -> kokoro.baseUrl, v -> kokoro.baseUrl = v))
                        .option(str("音色", "留空 = 按语言自动取（/health）", "af_sarah",
                                () -> extraOf(kokoro, "voice_name"), v -> setExtraOf(kokoro, "voice_name", v)))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(tr("Piper"))
                        .option(bool("启用 Piper", "Piper 是离线神经网络 TTS，支持中/英/俄/西/法/德等 30+ 语言。走 piper_server.py 的 HTTP 接口", piper.enabled,
                                () -> piper.enabled, v -> { piper.enabled = v; kickDetect(piper, v); }))
                        .option(str("地址", "piper_server.py 的服务地址。示例：http://127.0.0.1:8100", "http://127.0.0.1:8100",
                                () -> piper.baseUrl, v -> piper.baseUrl = v))
                        .option(str("音色", "服务端已加载的模型名，可访问 地址/health 查看。示例：zh_CN-huayan-medium", "zh_CN-huayan-medium",
                                () -> extraOf(piper, "voice_name"), v -> setExtraOf(piper, "voice_name", v)))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(tr("系统语音"))
                        .option(bool("启用系统语音", "Windows 自带 TTS", sysVoice.enabled,
                                () -> sysVoice.enabled, v -> { sysVoice.enabled = v; kickDetect(sysVoice, v); }))
                        .option(str("音色", "留空 = 自动", "",
                                () -> sysVoice.voice, v -> sysVoice.voice = v))
                        .build())
                .build();
    }

    // ------------------------------------------------------------ 右键菜单

    private static ConfigCategory contextCategory(Config c) {
        return ConfigCategory.createBuilder()
                .name(tr("右键菜单"))
                .group(OptionGroup.createBuilder()
                        .name(tr("基础"))
                        .option(bool("启用右键菜单", "左键点消息弹出菜单", true,
                                () -> c.contextMenu, v -> c.contextMenu = v))
                        .option(bool("删除前警告", "删除消息前弹确认框", true,
                                () -> c.contextDeletionWarning, v -> c.contextDeletionWarning = v))
                        .option(bool("删除音效", "删除时的呲啦声", true,
                                () -> c.contextDeletionSizzle, v -> c.contextDeletionSizzle = v))
                        .option(str("回复格式", "回复按钮生成的命令，$ 是占位符", "/msg $ ",
                                () -> c.contextReplyFormat, v -> c.contextReplyFormat = v))
                        .build())
                .build();
    }

    // ------------------------------------------------------------ 开发者

    private static ConfigCategory developerCategory(Config c, RecordConfig rec) {
        Option<Integer> gearX = integerKey("text.chatpatches.gearOffsetX", "设置按钮 X 偏移", "text.chatpatches.desc.gearOffsetX", "相对准星（屏幕中心）：正=右，负=左", 72, -240, 240, 1,
                () -> c.gearOffsetX, v -> c.gearOffsetX = v);
        Option<Integer> gearY = integerKey("text.chatpatches.gearOffsetY", "设置按钮 Y 偏移", "text.chatpatches.desc.gearOffsetY", "相对准星（屏幕中心）：正=上，负=下", -64, -240, 240, 1,
                () -> c.gearOffsetY, v -> c.gearOffsetY = v);

        return ConfigCategory.createBuilder()
                .name(tr("开发者"))
                .group(OptionGroup.createBuilder()
                        .name(trKey("text.chatpatches.group.buttonPosition", "按钮位置"))
                        .option(gearX).option(gearY)
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(tr("记录与恢复"))
                        .option(integer("恢复上限", "最多恢复多少条", rec.restoreLimit, 100, 10000, 100,
                                () -> rec.restoreLimit, v -> rec.restoreLimit = v))
                        .option(integer("恢复天数", "往前恢复几天", rec.restoreDays, 1, 30, 1,
                                () -> rec.restoreDays, v -> rec.restoreDays = v))
                        .option(bool("每次启动只恢复一次", "避免重复灌回", rec.restoreOncePerLaunch,
                                () -> rec.restoreOncePerLaunch, v -> rec.restoreOncePerLaunch = v))
                        .option(bool("历史分界", "显示/隐藏分界行", rec.historyBoundary.enabled,
                                () -> rec.historyBoundary.enabled, v -> rec.historyBoundary.enabled = v))
                        .option(str("分界起始文本", "{date} 会替换成日期", rec.historyBoundary.startFormat,
                                () -> rec.historyBoundary.startFormat, v -> rec.historyBoundary.startFormat = v))
                        .option(str("分界结束文本", "历史记录结束行", rec.historyBoundary.endText,
                                () -> rec.historyBoundary.endText, v -> rec.historyBoundary.endText = v))
                        .collapsed(true)
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(tr("显示"))
                        .option(bool("时间戳", "消息前显示时间", c.time, () -> c.time, v -> c.time = v))
                        .option(str("时间格式", "HH:mm:ss", c.timeDate, () -> c.timeDate, v -> c.timeDate = v))
                        .option(bool("消息计数", "显示第几条消息", c.counter, () -> c.counter, v -> c.counter = v))
                        .option(bool("显示名字", "消息前显示玩家名", c.name, () -> c.name, v -> c.name = v))
                        .option(integer("聊天宽度", "0 = 默认", c.chatWidth, 0, 320, 10, () -> c.chatWidth, v -> c.chatWidth = v))
                        .option(integer("聊天高度", "0 = 默认", c.chatHeight, 0, 180, 10, () -> c.chatHeight, v -> c.chatHeight = v))
                        .option(integer("最大消息数", "0 = 默认", c.chatMaxMessages, 0, 1000, 50,
                                () -> c.chatMaxMessages, v -> c.chatMaxMessages = v))
                        .collapsed(true)
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(tr("搜索"))
                        .option(bool("启用搜索", "聊天搜索框", c.search, () -> c.search, v -> c.search = v))
                        .option(bool("区分大小写", "搜索匹配区分大小写", c.caseSensitive,
                                () -> c.caseSensitive, v -> c.caseSensitive = v))
                        .option(bool("正则搜索", "把输入当作正则表达式", c.regex,
                                () -> c.regex, v -> c.regex = v))
                        .option(bool("空搜索显示全部", "关键字为空时显示全部消息", c.searchEmptyShowsFullChat,
                                () -> c.searchEmptyShowsFullChat, v -> c.searchEmptyShowsFullChat = v))
                        .collapsed(true)
                        .build())
                .build();
    }

    /** 按名字取服务商，没有就建一条（enabled=false，避免污染自动选择）。 */
    private static ApiProvider ensureProvider(TranslateConfig t, String name, String type) {
        for (ApiProvider p : t.providers) {
            if (p != null && name.equals(p.name)) {
                if (p.slot <= 0) {
                    p.slot = nextSlot(t);   // 老条目没号 → 补一个 ✓
                }
                return p;
            }
        }
        ApiProvider p = new ApiProvider();
        p.name = name;
        p.title = name;
        p.type = type;
        p.enabled = false;
        p.slot = nextSlot(t);   // ★ 新建就给槽位号 ✓（否则要等重启由 assignSlots 补）
        t.providers.add(p);
        return p;
    }

    /**
     * 分配一个新槽位号：<b>看历史水位</b>（`max(现有最大, nextSlot) + 1`）✓。
     *
     * <p>★ 只取"现有最大值 + 1"会**复用删掉的号** ✗ —— 7/8/9 删掉 9 → 最大变 8 → 又发 9 ✗
     * → 老配置里"用 9 号"会指到新加的那条 ✗（自测抓到过 ✓）
     */
    private static int nextSlot(ReadAloudConfig r) {
        int max = Math.max(0, r.nextSlot);
        for (ApiProvider p : r.providers) {
            if (p != null && p.slot > max) {
                max = p.slot;
            }
        }
        r.nextSlot = max + 1;
        return r.nextSlot;
    }

    private static int nextSlot(TranslateConfig t) {
        int max = Math.max(0, t.nextSlot);
        for (ApiProvider p : t.providers) {
            if (p != null && p.slot > max) {
                max = p.slot;
            }
        }
        t.nextSlot = max + 1;   // 水位只增不减 ✓
        return t.nextSlot;
    }
    /** 朗读用的按名字取服务商（ReadAloudConfig.providers）。 */
    private static ApiProvider ensureProvider(ReadAloudConfig r, String name, String type) {
        for (ApiProvider p : r.providers) {
            if (p != null && name.equals(p.name)) {
                return p;
            }
        }
        ApiProvider p = new ApiProvider();
        p.name = name;
        p.title = name;
        p.type = type;
        p.enabled = false;
        p.slot = nextSlot(r);   // ★ 朗读侧新建也发号 ✓
        r.providers.add(p);
        return p;
    }
    /** 探测本机常见 TTS/翻译服务端口，在跑就把对应条目设为启用。 */
    /** 供后台轮询调用：探一次端口，探到就启用。 */
    public static synchronized void autoDetectNow() {
        try {
            autoDetectLocal(obro1961.chatpatches.translate.ConfigStore.translate(), obro1961.chatpatches.translate.ConfigStore.readAloud());
        } catch (Throwable ignored) {
        }
    }

    private static void autoDetectLocal(TranslateConfig t, ReadAloudConfig r) {
        enableIfUp(t.providers, "混元", "http://127.0.0.1:8090/v1", 8090);
        enableIfUp(t.providers, "本地", "http://127.0.0.1:11434/v1", 11434);
        enableIfUp(r.providers, "Kokoro", "http://127.0.0.1:8101", 8101);
        enableIfUp(r.providers, "GPT-SoVITS", "http://127.0.0.1:9880", 9880);
    }

    private static void enableIfUp(java.util.List<ApiProvider> list, String name, String url, int port) {
        if (list == null || !portOpen(port)) {
            return;
        }
        for (ApiProvider p : list) {
            if (p == null || !name.equals(p.name)) {
                continue;
            }
            if (p.baseUrl == null || p.baseUrl.isBlank()) {
                p.baseUrl = url;
            }
            if (!p.enabled) {
                p.enabled = true;
                obro1961.chatpatches.ChatPatches.LOGGER.info("[chatpatch] 检测到 {} 在运行（端口 {}），已自动启用", name, port);
            }
            return;
        }
    }

    private static boolean portOpen(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    // ------------------------------------------------------------ 校验

    /** 已知语言名（中/英/日/韩/俄/西/法/越 + 自动检测）。 */
    private static final List<String> KNOWN_LANGS = List.of(
            "自动检测", "中文", "简体中文", "英语", "English", "日语", "日本語",
            "韩语", "한국어", "俄语", "Русский", "西班牙语", "Español", "法语", "Français",
            "越南语", "Tiếng Việt");

    /** 轻校验：返回不合法的项描述（空 = 全部合法）。不阻止保存。 */
    /** 保存后反馈语言识别结果：四项各一行，避免挤在一起。 */
    private static void captureLanguageSnapshot(TranslateConfig t) {
        if (t == null) {
            return;
        }
        LAST_SOURCE_LANG = t.sourceLanguage;
        LAST_TARGET_LANG = t.targetLanguage;
        LAST_OUT_SOURCE_LANG = t.outgoingSourceLanguage;
        LAST_OUT_TARGET_LANG = t.outgoingTargetLanguage;
    }

    /** 只提示本次真正改动的那一项，避免按一个键刷出四条。 */
    private static void reportLanguages(TranslateConfig t) {
        if (t == null) {
            return;
        }
        if (!java.util.Objects.equals(LAST_SOURCE_LANG, t.sourceLanguage)) {
            reportLang("入站源语言", t.sourceLanguage, true);
            LAST_SOURCE_LANG = t.sourceLanguage;
        }
        if (!java.util.Objects.equals(LAST_TARGET_LANG, t.targetLanguage)) {
            reportLang("入站目标语言", t.targetLanguage, false);
            LAST_TARGET_LANG = t.targetLanguage;
        }
        if (!java.util.Objects.equals(LAST_OUT_SOURCE_LANG, t.outgoingSourceLanguage)) {
            reportLang("出站源语言", t.outgoingSourceLanguage, false);
            LAST_OUT_SOURCE_LANG = t.outgoingSourceLanguage;
        }
        if (!java.util.Objects.equals(LAST_OUT_TARGET_LANG, t.outgoingTargetLanguage)) {
            reportLang("出站目标语言", t.outgoingTargetLanguage, true);
            LAST_OUT_TARGET_LANG = t.outgoingTargetLanguage;
        }
    }

    private static void reportLang(String labelKey, String value, boolean allowAuto) {
        String label = obro1961.chatpatches.util.I18n.tr(labelKey);
        String line;
        if (value == null || value.isBlank()) {
            line = obro1961.chatpatches.util.I18n.tr("chatstudy.notice.langLineEmpty", label);
        } else if ("自动检测".equals(value.trim())) {
            line = allowAuto
                    ? obro1961.chatpatches.util.I18n.tr("chatstudy.notice.langLineAuto", label)
                    : obro1961.chatpatches.util.I18n.tr("chatstudy.notice.langLineNoAuto", label);
        } else {
            String code = obro1961.chatpatches.translate.DialogLanguages.codeOf(value);
            if (code == null) {
                line = obro1961.chatpatches.util.I18n.tr("chatstudy.notice.langLineBad", label,
                        MinecraftLanguageCatalog.displayName(value));
            } else {
                line = obro1961.chatpatches.util.I18n.tr("chatstudy.notice.langLineOk", label,
                        MinecraftLanguageCatalog.displayName(value), code);
            }
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(line), false);
        }
        obro1961.chatpatches.ChatPatches.LOGGER.info(line);
    }

    private static List<String> invalidLanguages(TranslateConfig t) {
        List<String> bad = new ArrayList<>();
        checkLang(bad, "入站源语言", t.sourceLanguage, true);
        checkLang(bad, "入站目标语言", t.targetLanguage, false);
        checkLang(bad, "出站源语言", t.outgoingSourceLanguage, false);
        checkLang(bad, "出站目标语言", t.outgoingTargetLanguage, true);
        return bad;
    }

    private static void checkLang(List<String> bad, String label, String value, boolean allowAuto) {
        if (value == null || value.isBlank()) {
            bad.add(label + "（空）");
            return;
        }
        if (!allowAuto && "自动检测".equals(value)) {
            bad.add(label + "（不能为自动检测）");
            return;
        }
        if (!KNOWN_LANGS.contains(value)) {
            bad.add(label + "：" + value);
        }
    }
    // ------------------------------------------------------------ 工具

    /** 总开关：清全部记忆；打开时立刻探一轮。 */
    private static void kickAllDetect(boolean on) {
        try {
            obro1961.chatpatches.translate.ServiceWatchdog.resetState();
            if (on) {
                obro1961.chatpatches.translate.ServiceWatchdog.kick();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 开：立刻探一次；关：只清记忆，等下次开再探。 */
    private static void kickDetect(ApiProvider p, boolean on) {
        try {
            obro1961.chatpatches.translate.ServiceWatchdog.resetFor(p); // 只清这一个
            if (on) {
                obro1961.chatpatches.translate.ServiceWatchdog.kick();
            }
        } catch (Throwable ignored) {
        }
    }

    private static Option<Boolean> bool(String name, String desc, boolean def, Supplier<Boolean> get, Consumer<Boolean> set) {
        return Option.<Boolean>createBuilder()
                .name(tr(name))
                .description(OptionDescription.of(tr(desc)))
                .binding(def, get, set)
                .controller(o -> BooleanControllerBuilder.create(o).coloured(true)
                        .formatValue(state -> trKey(
                                state ? "text.chatpatches.on" : "text.chatpatches.off",
                                state ? "开" : "关")))
                .build();
    }

    private static Option<String> cycle(String name, String desc, String def, List<String> values, Supplier<String> get, Consumer<String> set) {
        return Option.<String>createBuilder()
                .name(tr(name))
                .description(OptionDescription.of(tr(desc)))
                .binding(def, get, set)
                .controller(o -> CyclingListControllerBuilder.create(o).values(values).formatValue(v -> Component.literal(v)))
                .build();
    }

    private static Option<Integer> integerKey(String key, String fallback, String descKey, String descFallback,
                                               int def, int min, int max, int step,
                                               Supplier<Integer> get, Consumer<Integer> set) {
        return Option.<Integer>createBuilder()
                .name(trKey(key, fallback))
                .description(OptionDescription.of(trKey(descKey, descFallback)))
                .binding(def, get, set)
                .controller(o -> IntegerSliderControllerBuilder.create(o).range(min, max).step(step))
                .build();
    }
    private static Option<Integer> integer(String name, String desc, int def, int min, int max, int step, Supplier<Integer> get, Consumer<Integer> set) {
        return Option.<Integer>createBuilder()
                .name(tr(name))
                .description(OptionDescription.of(tr(desc)))
                .binding(def, get, set)
                .controller(o -> IntegerSliderControllerBuilder.create(o).range(min, max).step(step))
                .build();
    }

    private static Option<Float> flt(String name, String desc, float def, float min, float max, float step, Supplier<Float> get, Consumer<Float> set) {
        return Option.<Float>createBuilder()
                .name(tr(name))
                .description(OptionDescription.of(tr(desc)))
                .binding(def, get, set)
                .controller(o -> FloatSliderControllerBuilder.create(o).range(min, max).step(step))
                .build();
    }

    private static String extraOf(ApiProvider p, String key) {
        if (p == null || p.extraBody == null) {
            return "auto";
        }
        Object v = p.extraBody.get(key);
        return v == null || String.valueOf(v).isBlank() ? "auto" : String.valueOf(v);
    }

    private static void setExtraOf(ApiProvider p, String key, String v) {
        if (p == null) {
            return;
        }
        if (p.extraBody == null) {
            p.extraBody = new java.util.LinkedHashMap<>();
        }
        p.extraBody.put(key, v == null ? "auto" : v);
    }
    /** 读写 ApiProvider.extraBody["prompt_text"]（GPT-SoVITS 的参考文本）。 */
    private static String promptOf(ApiProvider p) {
        if (p == null || p.extraBody == null) {
            return "";
        }
        Object v = p.extraBody.get("prompt_text");
        return v == null ? "" : String.valueOf(v);
    }

    private static void setPromptOf(ApiProvider p, String v) {
        if (p == null) {
            return;
        }
        if (p.extraBody == null) {
            p.extraBody = new java.util.LinkedHashMap<>();
        }
        p.extraBody.put("prompt_text", v == null ? "" : v);
    }
    /** 只显示提示的伪条目：左标签 + 右侧灰字，不参与保存。 */
    private static Option<String> hint(String name, String text) {
        return Option.<String>createBuilder()
                .name(tr(name))
                .binding("", () -> "", v -> { })
                .controller(o -> () -> new HintController(o, text))
                .build();
    }
        /** 稳定翻译 key；中文 fallback 只用于语言包缺失时保底。 */
    private static Component trKey(String key, String zhFallback) {
        return Component.translatableWithFallback(key, zhFallback);
    }
    /**
     * <b>中文文案 → 规范 key 的映射表。</b>
     *
     * <h2>为什么需要它</h2>
     * <p>这套界面最早是把<b>中文原文当 key</b> 用的（{@code Component.translatable("翻译")}）。
     * 那样能跑，但<b>没法做多语言</b>：
     * <pre>
     *   代码:  tr("翻译")
     *   语言文件: "翻译": "Translation"
     *   问题: 德语文件里写 "翻译": "Übersetzung" 的含义是什么？译者看不懂 key。
     *        而且 key 里带中文，任何基于 ASCII 的工具链都会出岔子。
     * </pre>
     *
     * <h2>现在怎么做</h2>
     * <p>调用点<b>一个字都不用改</b>（继续写人们看得懂的中文），
     * 但 {@link #tr(String)} 会先查这张表转成规范 key：
     * <pre>
     *   tr("翻译")  →  "chatstudy.ui.tab.translate"
     *                   ↓ 语言文件里按 locale 找
     *                  en: Translation / de: Übersetzung / ja: 翻訳
     * </pre>
     *
     * <p>表里没有的（新加的文案还没登记）→ <b>原样当中文 key 用</b>，
     * 至少不会显示成一串 {@code chatstudy.ui.xxx}。
     */
    private static final Map<String, String> KEY_MAP = Map.ofEntries(
            // ---- 顶层 ----
            Map.entry("聊天补丁 · 详细配置", "chatstudy.ui.title"),
            // ---- 分组名 ----
            Map.entry("翻译", "chatstudy.ui.tab.translate"),
            Map.entry("朗读", "chatstudy.ui.tab.tts"),
            Map.entry("基础", "chatstudy.ui.group.basic"),
            Map.entry("高级", "chatstudy.ui.group.advanced"),
            Map.entry("参数", "chatstudy.ui.group.params"),
            Map.entry("在线翻译", "chatstudy.ui.group.online"),
            Map.entry("本地翻译", "chatstudy.ui.group.local"),
            Map.entry("混元专属（Hy-MT2）", "chatstudy.ui.group.hunyuan"),
            Map.entry("免费 API", "chatstudy.ui.group.freeApi"),
            Map.entry("在线朗读（OpenAI 兼容）", "chatstudy.ui.group.onlineTts"),
            Map.entry("系统语音", "chatstudy.ui.group.systemVoice"),
            Map.entry("GPT-SoVITS", "chatstudy.ui.group.sovits"),
            Map.entry("Piper", "chatstudy.ui.group.piper"),
            Map.entry("Kokoro", "chatstudy.ui.group.kokoro"),
            // ---- 选项名 ----
            Map.entry("启用翻译", "chatstudy.ui.opt.enableTranslate"),
            Map.entry("自动翻译", "chatstudy.ui.opt.autoTranslate"),
            Map.entry("入站源语言", "chatstudy.ui.opt.inSource"),
            Map.entry("入站目标语言", "chatstudy.ui.opt.inTarget"),
            Map.entry("翻译系统消息", "chatstudy.ui.opt.translateSystem"),
            Map.entry("翻译自己说的话", "chatstudy.ui.opt.translateOwn"),
            Map.entry("出站翻译", "chatstudy.ui.opt.outgoing"),
            Map.entry("出站源语言", "chatstudy.ui.opt.outSource"),
            Map.entry("出站目标语言", "chatstudy.ui.opt.outTarget"),
            Map.entry("启用在线翻译", "chatstudy.ui.opt.enableOnline"),
            Map.entry("启用本地翻译", "chatstudy.ui.opt.enableLocal"),
            Map.entry("启用混元", "chatstudy.ui.opt.enableHunyuan"),
            Map.entry("启用免费 API", "chatstudy.ui.opt.enableFreeApi"),
            Map.entry("模型名", "chatstudy.ui.opt.model"),
            Map.entry("地址", "chatstudy.ui.opt.url"),
            Map.entry("音色", "chatstudy.ui.opt.voice"),
            Map.entry("超时 (ms)", "chatstudy.ui.opt.timeout"),
            Map.entry("退避最长等待", "chatstudy.ui.opt.backoffMax"),
            Map.entry("启用系统语音", "chatstudy.ui.opt.enableSystemVoice"),
            Map.entry("朗读音量", "chatstudy.ui.opt.volume"),
            Map.entry("太短的消息不朗读", "chatstudy.ui.opt.skipShort"),
            Map.entry("读原文", "chatstudy.ui.opt.readOriginal"),
            Map.entry("读系统消息", "chatstudy.ui.opt.readSystem"),
            Map.entry("自己发的也朗读", "chatstudy.ui.opt.readOwn"),
            Map.entry("启用右键菜单", "chatstudy.ui.opt.enableMenu"),
            Map.entry("消息前显示玩家名", "chatstudy.ui.opt.showName"),
            Map.entry("时间格式", "chatstudy.ui.opt.timeFormat"),
            Map.entry("最大消息数", "chatstudy.ui.opt.maxMessages"),
            Map.entry("缓存条数", "chatstudy.ui.opt.cacheSize"),
            Map.entry("恢复天数", "chatstudy.ui.opt.restoreDays"),
            Map.entry("每次启动只恢复一次", "chatstudy.ui.opt.restoreOnce"),
            Map.entry("搜索语言", "chatstudy.ui.opt.searchLang"),
            // ---- 值 ----
            Map.entry("开", "chatstudy.ui.val.on"),
            Map.entry("关", "chatstudy.ui.val.off"),
            Map.entry("自动", "chatstudy.ui.val.auto"),
            Map.entry("自填", "chatstudy.ui.val.custom"),
            Map.entry("默认 bilibili", "chatstudy.ui.val.defaultBili"),
            Map.entry("留空 = 不使用", "chatstudy.ui.val.emptyUnused"),
            Map.entry("留空 = 自动", "chatstudy.ui.val.emptyAuto"),
            Map.entry("留空 = 自动（服务端 /health）", "chatstudy.ui.val.emptyHealth")
    );

    /**
     * 其余文案的映射 —— <b>选项名与说明文本</b>。
     *
     * <p>数量大（102 条），{@code Map.ofEntries} 塞一起太长，单独一张表，
     * 和 {@link #KEY_MAP} 合并查询。表内顺序无所谓。
     */
    private static final Map<String, String> KEY_MAP_MORE = buildMoreKeys();

    private static Map<String, String> buildMoreKeys() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        // ==================== 选项名（续） ====================
        m.put("429 退避上限 (ms)", "chatstudy.ui.opt.backoffCap");
        m.put("429 退避起点 (ms)", "chatstudy.ui.opt.backoffBase");
        m.put("API Key", "chatstudy.ui.opt.apiKey");
        m.put("URL", "chatstudy.ui.opt.urlFull");
        m.put("免费API 1 名字", "chatstudy.ui.opt.free1Name");
        m.put("免费API 1 地址", "chatstudy.ui.opt.free1Url");
        m.put("免费API 2 名字", "chatstudy.ui.opt.free2Name");
        m.put("免费API 2 地址", "chatstudy.ui.opt.free2Url");
        m.put("免费API 3 名字", "chatstudy.ui.opt.free3Name");
        m.put("免费API 3 地址", "chatstudy.ui.opt.free3Url");
        m.put("分界结束文本", "chatstudy.ui.opt.boundaryEnd");
        m.put("分界起始文本", "chatstudy.ui.opt.boundaryStart");
        m.put("删除前警告", "chatstudy.ui.opt.deleteWarn");
        m.put("删除音效", "chatstudy.ui.opt.deleteSound");
        m.put("区分大小写", "chatstudy.ui.opt.caseSensitive");
        m.put("历史分界", "chatstudy.ui.opt.historyBoundary");
        m.put("参考文本", "chatstudy.ui.opt.refText");
        m.put("参考音频", "chatstudy.ui.opt.refAudio");
        m.put("启用 GPT-SoVITS", "chatstudy.ui.opt.enableSovits");
        m.put("启用 Kokoro", "chatstudy.ui.opt.enableKokoro");
        m.put("启用 Piper", "chatstudy.ui.opt.enablePiper");
        m.put("启用在线朗读", "chatstudy.ui.opt.enableOnlineTts");
        m.put("启用搜索", "chatstudy.ui.opt.enableSearch");
        m.put("启用朗读", "chatstudy.ui.opt.enableTts");
        m.put("回复格式", "chatstudy.ui.opt.replyFormat");
        m.put("恢复上限", "chatstudy.ui.opt.restoreLimit");
        m.put("时间戳", "chatstudy.ui.opt.timestamp");
        m.put("显示名字", "chatstudy.ui.opt.showNameFull");
        m.put("最短长度", "chatstudy.ui.opt.minLength");
        m.put("最长长度", "chatstudy.ui.opt.maxLength");
        m.put("正则搜索", "chatstudy.ui.opt.regexSearch");
        m.put("消息计数", "chatstudy.ui.opt.counter");
        m.put("空搜索显示全部", "chatstudy.ui.opt.emptySearchAll");
        m.put("聊天宽度", "chatstudy.ui.opt.chatWidth");
        m.put("聊天高度", "chatstudy.ui.opt.chatHeight");
        m.put("读自己的消息", "chatstudy.ui.opt.readOwnFull");
        m.put("读译文", "chatstudy.ui.opt.readTranslation");
        m.put("音量", "chatstudy.ui.opt.volumeFull");
        // ==================== 说明文本 ====================
        m.put("0 = 不缓存", "chatstudy.ui.desc.danijof");
        m.put("0 = 默认", "chatstudy.ui.desc.dn9mo3j");
        m.put("GPT-SoVITS 声音克隆 TTS：支持中文/日语/英语/韩语/粤语。项目：https://github.com/RVC-Boss/GPT-SoVITS （api.py 默认端口 9880）", "chatstudy.ui.desc.d867gfm");
        m.put("HH:mm:ss", "chatstudy.ui.desc.dzv2ohs");
        m.put("Kokoro 是轻量开源 TTS：支持英语/中文/日语/西班牙语/法语等。服务端：https://github.com/remsky/Kokoro-FastAPI （默认端口 8880，若用其它端口请改地址）", "chatstudy.ui.desc.dsr4ksq");
        m.put("OpenAI 兼容地址", "chatstudy.ui.desc.d24oc7v");
        m.put("Piper 是离线神经网络 TTS，支持中/英/俄/西/法/德等 30+ 语言。走 piper_server.py 的 HTTP 接口", "chatstudy.ui.desc.d12no8uf");
        m.put("TTS 总开关；关掉后右键「朗读这条」仍可用", "chatstudy.ui.desc.dijbpq0");
        m.put("Windows 自带 TTS", "chatstudy.ui.desc.d3806mk");
        m.put("bilibili 接口地址", "chatstudy.ui.desc.d300lty");
        m.put("https://api.openai.com/v1 或本地兼容服务", "chatstudy.ui.desc.ddhuxil");
        m.put("mymemory GET 地址（含 {text} 占位）", "chatstudy.ui.desc.d6hiy01");
        m.put("piper_server.py 的服务地址。示例：http://127.0.0.1:8100", "chatstudy.ui.desc.dmis24g");
        m.put("{date} 会替换成日期", "chatstudy.ui.desc.dqpsqzd");
        m.put("任意 OpenAI 兼容的云服务：DeepSeek（https://platform.deepseek.com ，模型 deepseek-chat）、硅基流动、各类中转站；地址通常以 /v1 结尾", "chatstudy.ui.desc.dsd331b");
        m.put("任意 OpenAI 兼容的本地模型：Ollama（https://ollama.com ，默认 11434）、LM Studio（1234）、vLLM（8000）。能翻哪些语言取决于你装的模型", "chatstudy.ui.desc.dwyzaav");
        m.put("例如 deepseek-chat", "chatstudy.ui.desc.d19ikwt4");
        m.put("例如 http://127.0.0.1:11434/v1", "chatstudy.ui.desc.drxc2wr");
        m.put("例如 http://127.0.0.1:9880", "chatstudy.ui.desc.dyyyyf1");
        m.put("例如 tts-1 / kokoro；留空 = kokoro", "chatstudy.ui.desc.dgdfv5g");
        m.put("关掉后 bilibili / mymemory / 免费槽 全部不参与", "chatstudy.ui.desc.d1s2q293");
        m.put("关键字为空时显示全部消息", "chatstudy.ui.desc.d1q22upp");
        m.put("关闭 = 不用这一栏", "chatstudy.ui.desc.drda6g4");
        m.put("删除时的呲啦声", "chatstudy.ui.desc.d1m2lune");
        m.put("删除消息前弹确认框", "chatstudy.ui.desc.dpxa7y1");
        m.put("别人说的话本来是什么语言。支持语言：英语/English、简体中文/中文、日语/日本語、韩语/한국어、俄语、西班牙语、法语、德语、葡萄牙语、意大利语、越南语、泰语、阿拉伯语、土耳其语、印尼语；也可填「自动检测」", "chatstudy.ui.desc.dt2l2go");
        m.put("单次合成超时", "chatstudy.ui.desc.dhxilj9");
        m.put("历史记录结束行", "chatstudy.ui.desc.dvsscbz");
        m.put("参考音频里那句话，可留空", "chatstudy.ui.desc.db6w5hu");
        m.put("回复按钮生成的命令，$ 是占位符", "chatstudy.ui.desc.d2uu73g");
        m.put("在原文之后朗读译文", "chatstudy.ui.desc.d6yaka5");
        m.put("左键点消息弹出菜单", "chatstudy.ui.desc.d9ube2a");
        m.put("往前恢复几天", "chatstudy.ui.desc.d1bnf8w1");
        m.put("总开关；关掉后自动翻译与出站翻译都不生效", "chatstudy.ui.desc.dn86kot");
        m.put("我打的话是什么语言。支持语言：英语/English、简体中文/中文、日语/日本語、韩语/한국어、俄语、西班牙语、法语、德语、葡萄牙语、意大利语、越南语、泰语、阿拉伯语、土耳其语、印尼语", "chatstudy.ui.desc.d1yz6cro");
        m.put("把输入当作正则表达式", "chatstudy.ui.desc.dxebpy6");
        m.put("搜索匹配区分大小写", "chatstudy.ui.desc.d1kih07f");
        m.put("收到消息自动翻译", "chatstudy.ui.desc.d1pklyl1");
        m.put("明文保存", "chatstudy.ui.desc.ddanj10");
        m.put("明文保存，本地服务可空", "chatstudy.ui.desc.d1jnapaz");
        m.put("显示/隐藏分界行", "chatstudy.ui.desc.d11c60yc");
        m.put("显示第几条消息", "chatstudy.ui.desc.do7borc");
        m.put("最多恢复多少条", "chatstudy.ui.desc.daf5isl");
        m.put("服务器公告等系统消息也朗读", "chatstudy.ui.desc.dryrif5");
        m.put("服务器公告等系统消息也翻译", "chatstudy.ui.desc.dryvqsz");
        m.put("服务器本地 wav 绝对路径（必填）", "chatstudy.ui.desc.d13vc62w");
        m.put("服务端已加载的模型名，可访问 地址/health 查看。示例：zh_CN-huayan-medium", "chatstudy.ui.desc.dzitbgy");
        m.put("朗读原消息（名字 + 原文）", "chatstudy.ui.desc.d1nr1djw");
        m.put("本地模型名，例如 qwen2.5:7b", "chatstudy.ui.desc.dexznyv");
        m.put("消息前显示时间", "chatstudy.ui.desc.dsxeo5c");
        m.put("混元 Hy-MT2 是腾讯的翻译专用小模型（1.8B，Q4 约 1GB），只做翻译、不做朗读。模型：https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF （国内可用 https://hf-mirror.com）", "chatstudy.ui.desc.d1jtln57");
        m.put("留空 = 按语言自动取（/health）", "chatstudy.ui.desc.dw3eako");
        m.put("翻成对方语言。支持语言：英语/English、简体中文/中文、日语/日本語、韩语/한국어、俄语、西班牙语、法语、德语、葡萄牙语、意大利语、越南语、泰语、阿拉伯语、土耳其语、印尼语", "chatstudy.ui.desc.de9qo7f");
        m.put("翻译成什么语言。支持语言：英语/English、简体中文/中文、日语/日本語、韩语/한국어、俄语、西班牙语、法语、德语、葡萄牙语、意大利语、越南语、泰语、阿拉伯语、土耳其语、印尼语（不能填自动检测）", "chatstudy.ui.desc.dhlkytr");
        m.put("聊天搜索框", "chatstudy.ui.desc.dg26rcx");
        m.put("自己发的话自动翻成对方语言", "chatstudy.ui.desc.dna5sqx");
        m.put("被限流后的第一次等待", "chatstudy.ui.desc.dxsjhcn");
        m.put("超长消息截断", "chatstudy.ui.desc.d1tj86mc");
        m.put("避免重复灌回", "chatstudy.ui.desc.dm5vzj4");
        m.put("默认 Hy-MT2-1.8B", "chatstudy.ui.desc.d1eco3wx");
        m.put("默认 http://127.0.0.1:8090/v1", "chatstudy.ui.desc.d10k0w0");
        m.put("默认 http://127.0.0.1:8101", "chatstudy.ui.desc.dm88pn9");
        m.put("默认 mymemory", "chatstudy.ui.desc.d1bdrn9d");
        m.put("默认关：自己打的字不必再翻。打开后连服务器回显给你的那条也翻 —— 注意出站已经翻过一次，可能出现往返失真", "chatstudy.ui.desc.dm926lu");


        // ---- 最后补齐的分组名 ----
        m.put("右键菜单", "chatstudy.ui.group.contextMenu");
        m.put("开发者", "chatstudy.ui.group.developer");
        m.put("搜索", "chatstudy.ui.group.search");
        m.put("显示", "chatstudy.ui.group.display");
        m.put("记录与恢复", "chatstudy.ui.group.record");        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * 中文文案 → 显示文字。
     *
     * <p>先查 {@link #KEY_MAP} / {@link #KEY_MAP_MORE} 转成规范 key，
     * 再交给 MC 按当前语言解析。两张表都没有的原样当中文 key
     * （至少显示中文，不会变成 {@code chatstudy.ui.xxx}）。
     */
    private static Component tr(String zh) {
        String key = KEY_MAP.get(zh);
        if (key == null) {
            key = KEY_MAP_MORE.get(zh);
        }
        return Component.translatable(key == null ? zh : key);
    }

    /** 语言选择：点按钮进列表，不再手打。 */
    /** 语言选择：YACL 自己的按钮控件；点开进列表，选完重建界面让文字立刻更新。 */
    private static Option<java.util.function.BiConsumer<dev.isxander.yacl3.gui.YACLScreen, dev.isxander.yacl3.api.ButtonOption>>
    langButton(String name, String desc, String def, boolean allowAuto, Supplier<String> get, Consumer<String> set) {
        String cur = get.get();
        Component shown = Component.literal(MinecraftLanguageCatalog.displayName(cur));
        return dev.isxander.yacl3.api.ButtonOption.createBuilder()
                .name(tr(name))
                .description(OptionDescription.of(tr(desc)))
                .text(shown)
                .action((screen, opt) -> {
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    mc.setScreen(new LanguagePickerScreen(screen, get.get(), allowAuto, value -> {
                        set.accept(value);
                        ConfigStore.saveTranslate();
                        mc.setScreen(build(LAST_PARENT));   // 重建，按钮文字立即反映新值
                    }));
                })
                .build();
    }

    private static Option<String> lang(String name, String desc, String def, boolean allowAuto,
                                       Supplier<String> get, Consumer<String> set) {
        return Option.<String>createBuilder()
                .name(tr(name))
                .description(OptionDescription.of(tr(desc)))
                .binding(def, get, set)
                .controller(o -> () -> new LanguageController(o, allowAuto))
                .build();
    }

    private static Option<String> str(String name, String desc, String def, Supplier<String> get, Consumer<String> set) {
        return Option.<String>createBuilder()
                .name(tr(name))
                .description(OptionDescription.of(tr(desc)))
                .binding(def, get, set)
                .controller(o -> StringControllerBuilder.create(o))
                .build();
    }

    /** 服务商下拉：显示「名字（URL）」，空值显示「自动」。 */
    private static Option<String> provider(String name, String desc, String def, List<ApiProvider> providers,
                                           Supplier<String> get, Consumer<String> set) {
        List<String> names = new ArrayList<>();
        names.add("");
        for (ApiProvider p : providers) {
            if (p != null && p.name != null) {
                names.add(p.name);
            }
        }
        return Option.<String>createBuilder()
                .name(tr(name))
                .description(OptionDescription.of(tr(desc)))
                .binding(def, get, set)
                .controller(o -> CyclingListControllerBuilder.create(o)
                        .values(names)
                        .formatValue(v -> {
                            if (v == null || v.isEmpty()) {
                                return Component.literal("自动");
                            }
                            for (ApiProvider p : providers) {
                                // ★ active 现在是槽位号 ✓ 两种都认
                                boolean hit = p != null && (v.matches("\\d+")
                                        ? p.slot == Integer.parseInt(v) : v.equals(p.name));
                                if (hit) {
                                    String url = p.baseUrl == null ? "" : p.baseUrl;
                                    return Component.literal(url.isEmpty() ? v : v + "（" + url + "）");
                                }
                            }
                            return Component.literal(v);
                        }))
                .build();
    }
}