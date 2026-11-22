package obro1961.chatpatches.chatlog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>{@code 5-record.json}</b> —— 记录层配置（灌回条数 / 历史分界 / 删除策略 / debug）。
 *
 * <p>为什么单独一份文件（用户 10-19 D5）：「常改的在前、难改的在后」。
 * 这份里的东西一般不用动，所以排到 5，并在 {@code 说明.txt} 里写清"想干什么改哪个文件"。
 *
 * <p><b>这个类刻意不依赖 Minecraft</b>（和 {@link ChatLogStore} 一样），
 * 所以能被离线自测直接构造、直接断言默认值。
 */
public class RecordConfig {

    /** 配置格式版本（迁移用）。 */
    public int schemaVersion = 1;

    /** 一次灌回多少条；<b>0 = 全灌</b>（D4/R9）。 */
    public int restoreLimit = 2000;

    /**
     * 灌回读几天：<b>今天 + 前一天 = 2</b>（用户 2026-10-21 澄清：「我的意思是今天和前一天」）。
     *
     * <p>之前我按"读满 7 天"实现，是我自己放大的，不是需求。
     * 留成配置项只是不想再写死；默认必须是 2。
     */
    public int restoreDays = 2;



    /** 一次游戏启动只灌一次（D6/R9：世界只有一条时间线，换服不重贴）。 */
    public boolean restoreOncePerLaunch = true;

    /**
     * 已发出的最大 seq。<b>持久化下限</b>：
     * 删光 chatlogs 后编号也不会从 1 重来；恢复备份也不会和新记录撞号。
     * 启动时取 max(文件最大值, 这个值)，之后由 bridge 每几秒回写一次。
     */
    public int lastSeq = 0;

    /** 历史分界线（只显示，不落盘）。 */
    public HistoryBoundary historyBoundary = new HistoryBoundary();

    public static class HistoryBoundary {
        public boolean enabled = true;
        /** 顶部分界；{@code {date}} 会被换成日期。 */
        public String startFormat = "──────── 历史记录 {date} ────────";
        public String endText = "──────── 以上是历史记录 ────────";
        /** 灰色（D14 方案 B：和 stock 的青色方括号分界一眼分开）。 */
        public String color = "§7";
    }

    // ================= 纯逻辑（离线自测直接打） =================

    /**
     * 按 <b>(time, seq)</b> 排序 —— 世界上只有一条时间线（S10）。
     *
     * <p><b>为什么必须排</b>：现在的读法是「先读新布局、再读旧布局」，
     * 直接拼起来的话，旧文件里的 00:35 会被排到新文件 15:37 的<b>后面</b>，
     * 灌回后历史是倒的（实测已经发生过）。时间相同时按 {@code seq} 定先后。
     *
     * <p>{@code time} 缺失（session 头等）的记录排最前，保持相对顺序。
     */
    /**
     * 译文配对的键：<b>日期 + seq</b>。
     *
     * <p>为什么不能只用 seq：`seq` 是<b>按天重置</b>的（方案 A 的决定）。
     * 灌回会合并"今天 + 前一天"，两天的 seq 各有 1、2、3… —— 只按 seq 配，
     * 今天的译文会挂到昨天同号的消息上（实机："译文位置错了"）。
     */
    public static String pairKey(String day, Integer seq) {
        return (day == null ? "" : day) + "#" + (seq == null ? "" : seq);
    }

    public static List<ChatLogStore.Line> sortByTime(List<ChatLogStore.Line> lines) {
        List<ChatLogStore.Line> out = new ArrayList<>(lines);
        // ★ 必须【先按日期，再按时间】：灌回会合并"今天 + 前一天"，两天的 time 都只有 HH:mm:ss。
        //   只按 time 排会把今天 00:10 排到昨天 23:50 前面 —— 历史顺序乱掉
        //   （用户实机反馈：「分割线上面是昨天的消息」就是这个）。
        out.sort(Comparator
                .comparing((ChatLogStore.Line l) -> l.day() == null ? "" : l.day(),
                        Comparator.naturalOrder())
                .thenComparing(l -> l.entry() == null ? null : l.entry().time,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(l -> l.entry() != null && l.entry().seq != null ? l.entry().seq : 0));
        return out;
    }

    /**
     * 去掉「同一句消息在两份文件里各写了一次」的重复。
     *
     * <p>场景：今天换过 jar / 换过布局，同一句话可能同时躺在
     * {@code 日期/聊天记录.jsonl}（新）和 {@code 日期/服务器/聊天记录.jsonl}（旧）里。
     * 灌回时不去重，就等于把同一条时间线贴两遍（D6 的初衷）。
     *
     * <p>只按 <b>(type, time, name, text)</b> 判重，而且<b>只在新旧两批之间</b>判 ——
     * 同一个人在同一秒真的复读同一句时，两条都在同一批里，不会被误删。
     *
     * @param primary   新布局读出来的记录（优先保留）
     * @param secondary 旧布局读出来的记录（被判重的丢掉）
     */
    public static List<ChatLogStore.Line> mergeDistinct(List<ChatLogStore.Line> primary,
                                                        List<ChatLogStore.Line> secondary) {
        List<ChatLogStore.Line> out = new ArrayList<>(primary);
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (ChatLogStore.Line l : primary) {
            seen.put(keyOf(l), Boolean.TRUE);
        }
        for (ChatLogStore.Line l : secondary) {
            String k = keyOf(l);
            if (seen.containsKey(k)) {
                continue;
            }
            seen.put(k, Boolean.TRUE);
            out.add(l);
        }
        return out;
    }

    private static String keyOf(ChatLogStore.Line l) {
        LogEntry e = l == null ? null : l.entry();
        if (e == null) {
            return "null";
        }
        return (l.day() == null ? "" : l.day()) + "\u0000" + e.type + "\u0000" + e.time + "\u0000" + e.name + "\u0000" + e.text;
    }

    /** 顶部分界文案（{@code {date}} 换成日期）。 */
    public String startText(String day) {
        String f = historyBoundary == null || historyBoundary.startFormat == null
                ? "──────── 历史记录 {date} ────────" : historyBoundary.startFormat;
        return color() + f.replace("{date}", day == null ? "" : day);
    }

    /** 底部分界文案。 */
    public String endText() {
        String t = historyBoundary == null || historyBoundary.endText == null
                ? "──────── 以上是历史记录 ────────" : historyBoundary.endText;
        return color() + t;
    }

    public String color() {
        return historyBoundary == null || historyBoundary.color == null
                ? "§7" : historyBoundary.color;
    }
}
