package obro1961.chatpatches.translate;

import obro1961.chatpatches.tts.ReadAloudConfig;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务连通性看门狗：
 * <ul>
 *   <li>进游戏后对<b>所有已启用</b>的服务尝试连接；</li>
 *   <li>连上 → 提示一次，之后该服务不再轮询；</li>
 *   <li>连不上 → 每 {@link #INTERVAL_MS} 毫秒提示一次，直到连上。</li>
 * </ul>
 * 消息里用<b>可读名字</b>（如「混元 Hy-MT2」「GPT-SoVITS」），不是内部条目名。
 */
public final class ServiceWatchdog {

    /** key = 稳定状态 id；nameKey = 语言文件里的服务名。 */
    private record Ref(String key, String nameKey) {
    }

    private static final long INTERVAL_MS = 12_000L;
    private static final Set<String> ONLINE = ConcurrentHashMap.newKeySet();
    /** 当前探测结果为“连不上”的服务（供降级链跳过，省掉每次请求的连接超时）。 */
    private static final Set<String> OFFLINE = ConcurrentHashMap.newKeySet();

    /** 已提示过“未配置/文件缺失”的服务，避免每 12 秒刷屏。 */
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();
    private static Thread thread;

    private ServiceWatchdog() {
    }

    /** 进游戏时调用一次。 */
    public static synchronized void start() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        thread = new Thread(ServiceWatchdog::loop, "chatpatch-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    /** 立即检测一轮（打开开关/进世界时调用）。 */
    public static void kick() {
        new Thread(() -> { try { tick(); } catch (Throwable ignored) {} }, "chatpatch-watchdog-kick").start();
    }

    /** 只清掉这一个服务的全部记忆（在线、离线、已提示未配置）。 */
    public static void resetFor(ApiProvider p) {
        Ref ref = refFor(p, false);
        if (ref == null) {
            ref = refFor(p, true);
        }
        if (ref != null) {
            String key = ref.key();
            ONLINE.remove(key);
            OFFLINE.remove(key);
            MISSING.remove(key);
            ServiceFeedback.reset(key);
        }
    }

    /** 重置“已在线”记忆，让下一次检测重新提示。 */
    public static void resetState() {
        ONLINE.clear();
        OFFLINE.clear();
        MISSING.clear();
    }

    private static void loop() {
        while (true) {
            try {
                tick();
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static void tick() {
        TranslateConfig cfg0 = ConfigStore.translate();
        if (cfg0 == null || !cfg0.connectCheckEnabled) {
            return;
        }
        TranslateConfig t = ConfigStore.translate();
        ReadAloudConfig r = ConfigStore.readAloud();

        if (t != null && t.enabled) {
            for (ApiProvider p : t.providers) {
                check(p, refFor(p, false));
            }
        }
        if (r != null && r.enabled) {
            for (ApiProvider p : r.providers) {
                check(p, refFor(p, true));
            }
        }
    }

    /** 探测结果为“连不上”的服务：降级链可以直接跳过它。 */
    public static boolean isKnownDown(ApiProvider p) {
        Ref ref = refFor(p, false);
        if (ref == null) {
            ref = refFor(p, true);
        }
        return ref != null && OFFLINE.contains(ref.key());
    }

    private static void check(ApiProvider p, Ref ref) {
        if (p == null || !p.enabled || ref == null) {
            return;
        }
        String key = ref.key();
        String name = obro1961.chatpatches.util.I18n.tr(ref.nameKey());
        if (ONLINE.contains(key)) {
            return;
        }
        if (MISSING.contains(key)) {
            return;
        }
        if (!configured(p)) {
            MISSING.add(key);
            OFFLINE.add(key);
            ServiceFeedback.cantReach(key, name,
                    obro1961.chatpatches.util.I18n.tr("chatstudy.notice.unconfigured"));
            return;
        }
        if (reachable(p)) {
            ONLINE.add(key);
            OFFLINE.remove(key);
            MISSING.remove(key);
            ServiceFeedback.connected(key, name);
        } else if ("piper".equalsIgnoreCase(p.type)) {
            MISSING.add(key);
            OFFLINE.add(key);
            ServiceFeedback.cantReach(key, name,
                    obro1961.chatpatches.util.I18n.tr("chatstudy.notice.unconfigured"));
        } else {
            OFFLINE.add(key);
            ServiceFeedback.cantReach(key, name,
                    obro1961.chatpatches.util.I18n.tr("chatstudy.notice.notRunning"));
        }
    }

    /**
     * <b>这个源"配没配"</b>（≠ 通没通）。
     *
     * <p>piper 走文件（{@code extraBody} 里的 exe/model），其余走 URL。
     * 系统语音（{@code system}）与纯本地引擎（{@code type=local} 且没有 URL）不需要地址 ✓
     */
    private static boolean configured(ApiProvider p) {
        String type = p.type == null ? "" : p.type;
        if ("system".equalsIgnoreCase(type)) {
            return true;
        }
        if ("piper".equalsIgnoreCase(type)) {
            return p.extraBody != null
                    && p.extraBody.get("piper_exe") != null
                    && p.extraBody.get("piper_model") != null;
        }
        String url = obro1961.chatpatches.tts.PiperTtsProvider.effectiveUrl(p);
        return url != null && !url.isBlank();
    }

    /** 探活：本地引擎看文件，其它看端口。 */
    private static boolean reachable(ApiProvider p) {
        // ★★ 2026-11-01 修：**`local` 不再被当成"系统本地能力"直接判通** ✗✗
        //
        //   原来的写法（用户报「明明没有服务，但是显示连接成功」的根子 ✓）：
        //     if ("system".equals(p.type) || "local".equals(p.type) && baseUrl 为空) return true;
        //     // 注释写的是"系统语音，无需网络"
        //
        //   两个问题：
        //   ① **语义混了** ✗ —— `system`（系统语音 ✓ 真的不需要网络）和 `local`
        //      （**本地服务** ✓ 比如 Ollama / 混元，必须连端口 ✗）被塞进同一个短路
        //   ② **短路后直接 true** ✗ —— 于是"地址都清空的本地源"也会被报成已连接 ✗
        //
        //   现在：只有 `system` 免网 ✓；`local` 一律走下面的 Socket 探测 ✓
        //   （地址为空的走 configured() 先拦掉 ✓ 报"未配置"而不是"已连接" ✗）
        if ("system".equalsIgnoreCase(p.type)) {
            return true; // 系统语音，无需网络 ✓
        }
        if ("piper".equalsIgnoreCase(p.type) && !obro1961.chatpatches.tts.PiperTtsProvider.hasHttp(p)) {
            Object exe = p.extraBody == null ? null : p.extraBody.get("piper_exe");
            Object model = p.extraBody == null ? null : p.extraBody.get("piper_model");
            return exe != null && model != null
                    && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(String.valueOf(exe)))
                    && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(String.valueOf(model)));
        }
        String url = obro1961.chatpatches.tts.PiperTtsProvider.effectiveUrl(p);
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI u = URI.create(url.trim());
            String host = u.getHost() == null ? "127.0.0.1" : u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 800);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 配置项 → 稳定状态 id + 服务名 key。
     * 翻译源和朗读源分开判断，避免「在线朗读」被当成「在线翻译」。
     */
    private static Ref refFor(ApiProvider p, boolean tts) {
        if (p == null) {
            return null;
        }
        String type = p.type == null ? "" : p.type.toLowerCase(Locale.ROOT);
        String name = p.name == null ? "" : p.name.toLowerCase(Locale.ROOT);
        String title = p.title == null ? "" : p.title.toLowerCase(Locale.ROOT);
        String text = name + " " + title;

        // 免费源 / 系统语音：不探活，也不显示连接提示
        if (text.contains("bili") || text.contains("mymemory") || text.contains("免费")) {
            return null;
        }
        if (type.contains("system") || text.contains("系统语音")) {
            return null;
        }

        if (tts) {
            if (type.contains("sovits") || text.contains("gpt-sovits")) {
                return new Ref("tts.sovits", "chatstudy.service.sovits");
            }
            if (type.contains("kokoro") || text.contains("kokoro")) {
                return new Ref("tts.kokoro", "chatstudy.service.kokoro");
            }
            if (type.contains("piper") || text.contains("piper")) {
                return new Ref("tts.piper", "chatstudy.service.piper");
            }
            return new Ref("tts.online", "chatstudy.service.onlineTts");
        }

        if (text.contains("混元") || text.contains("hy-mt")) {
            return new Ref("translate.hunyuan", "chatstudy.service.hunyuan");
        }
        if (type.contains("local") || text.contains("本地") || text.contains("ollama")) {
            return new Ref("translate.local", "chatstudy.service.local");
        }
        if (text.contains("在线") || text.contains("deepseek") || type.contains("api")) {
            return new Ref("translate.online", "chatstudy.service.online");
        }
        return null;
    }
}