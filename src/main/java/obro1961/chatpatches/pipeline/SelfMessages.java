package obro1961.chatpatches.pipeline;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本模组自己往聊天栏插入的消息标记。
 *
 * <p>这些消息不该再进流水线（不记录、不翻译、不朗读），否则会出现
 * “提示 → 被翻译/朗读 → 失败 → 又提示”的自激循环。
 *
 * <p>做法是插入前按文本登记，流水线入口按文本消费一次；10 秒后自动过期。
 */
public final class SelfMessages {

    private static final long TTL_MS = 10_000L;
    private static final Map<String, Long> MARKED = new ConcurrentHashMap<>();

    private SelfMessages() {
    }

    /** 插入前登记。 */
    public static void mark(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        MARKED.put(text.trim(), System.currentTimeMillis());
        if (MARKED.size() > 64) {
            long now = System.currentTimeMillis();
            MARKED.entrySet().removeIf(e -> now - e.getValue() > TTL_MS);
        }
    }

    /** 是不是本模组插入的（只看包含关系，容忍前后缀）。 */
    public static boolean isSelf(String text) {
        if (text == null || MARKED.isEmpty()) {
            return false;
        }
        String t = text.trim();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : MARKED.entrySet()) {
            if (now - e.getValue() > TTL_MS) {
                MARKED.remove(e.getKey());
                continue;
            }
            if (t.contains(e.getKey())) {
                return true;
            }
        }
        return false;
    }
}