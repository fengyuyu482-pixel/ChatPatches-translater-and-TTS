package obro1961.chatpatches.util;

/**
 * 运行时文案翻译：把 key + 参数解析成当前语言下的字符串。
 *
 * <p>用于聊天栏提示这类拼接出来的文字。核心类不直接依赖 Minecraft（测试环境没有 MC 类），
 * 实际解析函数由模组启动时通过 {@link #setResolver(Resolver)} 注入。
 */
public final class I18n {

    /** 由模组启动时注入：调用 Minecraft 的语言文件解析。 */
    @FunctionalInterface
    public interface Resolver {
        String tr(String key, Object... args);
    }

    private static volatile Resolver resolver;

    private I18n() {
    }

    public static void setResolver(Resolver r) {
        resolver = r;
    }

    /** 解析失败或未初始化时返回 key 本身，避免抛异常影响主流程。 */
    public static String tr(String key, Object... args) {
        Resolver r = resolver;
        if (r == null) {
            return key;
        }
        try {
            return r.tr(key, args);
        } catch (Throwable t) {
            return key;
        }
    }
}
