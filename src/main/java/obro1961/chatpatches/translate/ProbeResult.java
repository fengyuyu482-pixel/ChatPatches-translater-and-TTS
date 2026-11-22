package obro1961.chatpatches.translate;

/**
 * 一次连通性自检的结果。
 *
 * <p>这里的中文文案是给“文本框”复用的统一口径：成功就显示 {@link #OK_TEXT}，
 * 失败就显示 {@link #FAIL_TEXT} + 具体原因，别在别处再另造一套说法。
 */
public record ProbeResult(boolean ok, String detail, long ms) {

    public static final String OK_TEXT = "✓ 启动成功";
    public static final String FAIL_TEXT = "✗ 启动失败";

    public static ProbeResult ok(long ms, String detail) {
        return new ProbeResult(true, detail, ms);
    }

    public static ProbeResult fail(String detail, long ms) {
        return new ProbeResult(false, detail, ms);
    }

    /** 带颜色的短标记，用于聊天框/文本框。 */
    public String mark() {
        return ok ? "§a" + OK_TEXT : "§c" + FAIL_TEXT;
    }

    /** 一行完整描述。 */
    public String line(String id, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(mark()).append(" §f").append(title)
                .append(" §8[").append(id).append("] §7").append(detail);
        if (ms > 0) {
            sb.append(" §8(").append(ms).append("ms)");
        }
        return sb.toString();
    }
}
