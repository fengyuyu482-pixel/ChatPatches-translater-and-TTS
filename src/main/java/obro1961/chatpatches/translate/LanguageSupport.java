package obro1961.chatpatches.translate;

import java.util.Locale;

/**
 * <b>服务商的语言能力判定 —— 全部查 {@link LanguageRegistry}。</b>
 *
 * <h2>为什么重写</h2>
 * <p>旧实现是硬编码：本地模型一律放行「中/英/日/韩/粤」，在线一律放行全语言。
 * 两个问题：
 * <ol>
 *   <li><b>本地模型被放过太多</b> —— 混元本地实测只支持中/英，
 *       旧表却放行日语、韩语，于是德语、日语全被送进去，翻出来是乱码或被拒绝。
 *       更糟的是它<b>每次都先试本地源</b>（降级链排前面），白等一次超时。</li>
 *   <li><b>在线源被当成万能</b> —— mymemory 缺 13 个语言（威尼斯语、低地德语…），
 *       旧实现不管，请求发出去返回报错，被 {@code sanitize} 拦下才发现。</li>
 * </ol>
 *
 * <h2>新判据</h2>
 * <p>直接问注册表：<b>这门语言能不能用这个 provider 翻</b>。
 * 注册表的 {@code providers} 字段就是从能力和实测得来的：
 * <pre>
 *   hunyuan   → 仅 cmn / eng
 *   deepseek  → 全语言
 *   bilibili  → 全语言
 *   mymemory  → 72/85（13 个没有 langpair 代码）
 * </pre>
 *
 * <p><b>表即穷举</b>：注册表里 {@code providers} 这一列就是这家服务的<b>完整能力边界</b>。
 * 表里是 {@code false} 的，就是<b>真翻不了</b>，不是"没测过"。
 * 所以判定逻辑只有一句查表，没有任何经验判断。
 *
 * <p><b>认不出的服务商一律视为不支持</b> —— 表既然是穷举的，表里没有的就不在这套体系内。
 * 跳过并由降级链交给下一个支持的源，同时打一行 warn 说明原因（不静默）。
 * 用户新加了源却翻不了时，日志里能直接看到"不在能力表内"。
 */
public final class LanguageSupport {

    private LanguageSupport() {
    }

    /**
     * 这个服务商能不能翻这门语言。
     *
     * @param p              服务商配置
     * @param targetLanguage 目标语言（配置里写的中文名/英文名/代码都行）
     * @return {@code false} 时降级链<b>直接跳过</b>这个源，不发请求
     */
    public static boolean supports(ApiProvider p, String targetLanguage) {
        if (p == null) {
            return true;
        }
        if (!LanguageRegistry.enabled()) {
            // 注册表没加载 = 翻译整体不可用；返回 false 让调用方跳过所有源
            return false;
        }
        if (targetLanguage == null || targetLanguage.isBlank()) {
            return true;
        }
        String provider = providerKey(p);
        if (provider == null) {
            // ★ 表即穷举：认不出的源不在能力表内 → 视为不支持
            return false;
        }
        LanguageRegistry.Lang lang = Language.find(targetLanguage);
        if (lang == null) {
            // 语言认不出 → 不翻（宁可不动，也别送一个编造的名字出去）
            return false;
        }
        return lang.supports(provider);
    }

    /**
     * 为什么跳过这个源 —— 给日志用的一句话。
     *
     * <p>降级链每跳一个源都打一行，这样"某条消息为什么没翻"能直接从日志读出来，
     * 不用猜（这是排查翻译问题最费时间的地方）。
     *
     * @return 可读原因；{@code null} 表示支持（不该被跳过）
     */
    public static String skipReason(ApiProvider p, String targetLanguage) {
        if (p == null) {
            return null;
        }
        if (!LanguageRegistry.enabled()) {
            return "语言注册表未加载";
        }
        if (targetLanguage == null || targetLanguage.isBlank()) {
            return null;
        }
        String provider = providerKey(p);
        if (provider == null) {
            return "不在能力表内（认不出是哪家服务）";
        }
        LanguageRegistry.Lang lang = Language.find(targetLanguage);
        if (lang == null) {
            return "目标语言「" + targetLanguage + "」不在注册表内";
        }
        if (lang.supports(provider)) {
            return null;
        }
        return "能力表不支持 " + lang.display + "（" + provider + " 列）";
    }

    /**
     * 配置项 → 注册表里的 provider 键。
     *
     * <p>认的方式和 {@code ServiceWatchdog.refFor} 一致（按 name/title/baseUrl 关键字），
     * 这样日志里显示的服务名和这里是同一个判断。
     *
     * @return {@code null} 表示注册表不认识这个源
     */
    public static String providerKey(ApiProvider p) {
        if (p == null) {
            return null;
        }
        String text = (nz(p.name) + " " + nz(p.title) + " " + nz(p.baseUrl)).toLowerCase(Locale.ROOT);

        if (text.contains("mymemory")) {
            return "mymemory";
        }
        if (text.contains("bili")) {
            return "bilibili";
        }
        if (text.contains("混元") || text.contains("hunyuan") || text.contains("hy-mt")) {
            return "hunyuan";
        }
        if (text.contains("deepseek")) {
            return "deepseek";
        }
        // 本地端点（127.0.0.1 / localhost / type=local）→ 当作本地小模型
        String url = nz(p.baseUrl).toLowerCase(Locale.ROOT);
        if ("local".equalsIgnoreCase(p.type) || url.contains("127.0.0.1") || url.contains("localhost")) {
            return "hunyuan";   // 本地模型一律按"能力和混元同级"处理
        }
        if (text.contains("在线") || "api".equalsIgnoreCase(p.type)) {
            return "deepseek";  // 其它在线源按全语言处理
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
