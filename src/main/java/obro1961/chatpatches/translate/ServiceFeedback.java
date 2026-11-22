package obro1961.chatpatches.translate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 服务连接反馈：已连接 / 无法连接，直接显示在聊天栏。
 *
 * <p>通知按<b>稳定服务 id</b> 节流，不按显示名；显示名走语言文件，
 * 因此切换语言不会影响在线/离线状态。
 */
public final class ServiceFeedback {

    private static final long COOLDOWN_MS = 12_000L;
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    private static volatile Consumer<String> sink;

    private ServiceFeedback() {
    }

    /** 由模组启动时注入：把文本发到聊天栏。 */
    public static void setSink(Consumer<String> s) {
        sink = s;
    }

    public static void connected(String serviceId, String serviceName) {
        push("ok:" + serviceId,
                "§a" + obro1961.chatpatches.util.I18n.tr("chatstudy.notice.connected", serviceName));
    }

    public static void cantReach(String serviceId, String serviceName, String reason) {
        push("fail:" + serviceId,
                "§c" + obro1961.chatpatches.util.I18n.tr(
                        "chatstudy.notice.cannotReach", serviceName, shorten(reason)));
    }

    /** 兼容旧调用：没有单独显示名时，id 就是显示名。 */
    public static void connected(String id) {
        connected(id, id);
    }

    /** 兼容旧调用：没有单独显示名时，id 就是显示名。 */
    public static void cantReach(String id, String reason) {
        cantReach(id, id, reason);
    }

    private static void push(String key, String text) {
        long now = System.currentTimeMillis();
        Long last = LAST.get(key);
        if (last != null && now - last < COOLDOWN_MS) {
            return;
        }
        LAST.put(key, now);
        obro1961.chatpatches.ChatPatches.LOGGER.info("[chatpatch] {}", text.replaceAll("§.", ""));
        Consumer<String> s = sink;
        if (s != null) {
            try {
                s.accept(text);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String shorten(String s) {
        if (s == null || s.isBlank()) {
            return obro1961.chatpatches.util.I18n.tr("chatstudy.notice.unknownReason");
        }
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    /** 清掉一个服务的冷却，开关它时立刻能重新提示。 */
    public static void reset(String serviceId) {
        LAST.remove("ok:" + serviceId);
        LAST.remove("fail:" + serviceId);
    }

    public static void reset() {
        LAST.clear();
    }
}