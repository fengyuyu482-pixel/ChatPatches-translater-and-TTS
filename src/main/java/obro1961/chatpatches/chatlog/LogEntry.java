package obro1961.chatpatches.chatlog;

/**
 * 一条日志记录 —— <b>一行就是一条</b>。
 *
 * <p>字段用不到时**留 null，Gson 默认不写 null**，所以每行只出现实际有值的字段，文件很干净。
 *
 * <p>各 type 用到哪些字段：
 * <pre>
 * session  start（完整启动时间 "2026-10-10 14:00:12"）
 * chat     time / name / text / comp / raw
 * system   time / text
 * death    time / text
 * join     time / text        （进服/退服）
 * delete   targetSeq / by / at （标记删除：按 seq 记账）
 * </pre>
 */
public class LogEntry {

    public static final String SOURCE_SERVER = "server";
    public static final String SOURCE_MOD = "mod";
    public static final String SOURCE_SELF = "self";

    public boolean isMod() {
        return SOURCE_MOD.equals(source);
    }

    public static final String TYPE_SESSION = "session";
    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_SYSTEM = "system";
    public static final String TYPE_DEATH = "death";
    public static final String TYPE_JOIN = "join";
    public static final String TYPE_DELETE = "delete";
    public static final String TYPE_OUTGOING = "outgoing";
    public static final String TYPE_TRANSLATION = "translation";
    public static final String TYPE_BOUNDARY = "boundary";

    /** 这条是什么：session / chat / system / death / join / delete */
    /** 来源：server = 服务器下发；mod = 本模组插入；self = 玩家自己发的。 */
    public String source = SOURCE_SERVER;

    public String type;

    /** 时间，<b>只有 HH:mm:ss</b>（日期在文件夹名里，不重复写）。 */
    public String time;

    /** 会话头专用：这次启动游戏的完整时间。 */
    public String start;

    /** 谁说的（chat 才有）。 */
    public String name;

    /** 内容，带 § 颜色码。 */
    public String text;

    /**
     * <b>那条消息的 Component JSON</b> —— 灌回历史时靠它还原样式（颜色 / 悬浮 / 点击 / 插入）。
     *
     * <p><b>为什么必须有它</b>：{@link #text} 来自 {@code Component.getString()}，
     * 那是<b>纯文本，样式全丢</b> —— 时间戳的粉色（{@code #FF55FF}）、名字的白色、
     * 悬浮提示、右键建议命令、玩家实体 hover，都不在里面。
     * 灌回时用 {@code Component.literal(text)} 重建，就只能得到一坨"素的"文字。
     *
     * <p>原版 Chat Patches 的 {@code logs/chatlog.json} 存的就是这种结构，
     * 所以它灌回来是保真的 —— 这就是"原版可以、我们不行"的全部原因。
     *
     * <p>旧记录没有这个字段（Gson 留 null）→ 灌回时回落到 {@code Component.literal(text)}，
     * 内容还在，只是没有样式。
     */
    public String comp;

    // ★ 2026-10-19：删掉了 `public String tr;`
    //
    //   那是早期设计：把译文塞进同一条 chat 记录里。
    //   后来改成独立的 `translation` 记录（带 refSeq）—— 因为译文是异步回来的，
    //   塞进原行就必须**改写已写行**，违反"只追加"。
    //
    //   删之前核实过：
    //     ① 7 天的记录文件里 `"tr":` 出现 0 次；
    //     ② 代码里没有任何读取 `entry.tr` 的地方（译文一律走 translation）；
    //     ③ Gson 读旧 JSON 时未知字段直接忽略，删掉不会让旧文件读不出来。
    //   留着只会让人误以为它有用，再写一遍就变成两套机制。

    /** 当天编号（1 起）。**只有消息类分配**（chat/system/death/join/outgoing）。 */
    public Integer seq;

    /** 这条消息来自哪里（服务器名 / {@code 单人_世界名}）。 */
    public String place;

    /** 发送者 UUID（取不到就是 null）。 */
    public String uuid;

    /** translation 专用：挂在【哪条消息的 seq】上。 */
    public Integer refSeq;

    /** delete 专用：删的是【哪条消息的 seq】。 */
    public Integer targetSeq;

    /** translation 专用：译文的目标语言。 */
    public String lang;

    /** translation 专用：只在失败时写 false。 */
    public Boolean ok;

    /** boundary 专用：{@code start} / {@code end}（只显示，不落盘）。 */
    public String kind;

    /** 从旧布局读出来的记录：标 true，便于统计和排错。 */
    public Boolean legacy;

    /** 原始整行（排错用）。 */
    public String raw;



    /** delete 专用：谁删的。 */
    public String by;

    /** delete 专用：什么时候删的（HH:mm:ss）。 */
    public String at;

    public LogEntry() {
    }

    public static LogEntry session(String fullStart) {
        LogEntry e = new LogEntry();
        e.type = TYPE_SESSION;
        e.start = fullStart;
        return e;
    }

    public static LogEntry chat(String time, String name, String text) {
        LogEntry e = new LogEntry();
        e.type = TYPE_CHAT;
        e.time = time;
        e.name = name;
        e.text = text;
        return e;
    }

    /** 我发出去的原文（出站第一行）。 */
    public static LogEntry outgoing(String time, String place, String text) {
        LogEntry e = new LogEntry();
        e.type = TYPE_OUTGOING;
        e.time = time;
        e.place = place;
        e.text = text;
        return e;
    }

    /** 译文记录：refSeq 指回原文的 seq。 */
    public static LogEntry translation(int refSeq, String time, String text, String lang) {
        LogEntry e = new LogEntry();
        e.type = TYPE_TRANSLATION;
        e.refSeq = refSeq;
        e.time = time;
        e.text = text;
        e.lang = lang;
        return e;
    }

    /** 翻译失败：写 ok=false，text 放失败提示。 */
    public static LogEntry translationFailed(int refSeq, String time, String text, String lang) {
        LogEntry e = translation(refSeq, time, text, lang);
        e.ok = Boolean.FALSE;
        return e;
    }

    /** 按 seq 标记删除（新语义）。 */
    public static LogEntry deletionOfSeq(int targetSeq, String by, String at) {
        LogEntry e = new LogEntry();
        e.type = TYPE_DELETE;
        e.targetSeq = targetSeq;
        e.by = by;
        e.at = at;
        return e;
    }

    public static LogEntry of(String type, String time, String text) {
        LogEntry e = new LogEntry();
        e.type = type;
        e.time = time;
        e.text = text;
        return e;
    }

    public boolean isChat() {
        return TYPE_CHAT.equals(type);
    }

    public boolean isDeletion() {
        return TYPE_DELETE.equals(type);
    }

    /** 这条记录是否该分配 seq（只有消息类才有）。 */
    public boolean needsSeq() {
        return TYPE_CHAT.equals(type) || TYPE_SYSTEM.equals(type) || TYPE_DEATH.equals(type)
                || TYPE_JOIN.equals(type) || TYPE_OUTGOING.equals(type);
    }

    public boolean isSession() {
        return TYPE_SESSION.equals(type);
    }

    @Override
    public String toString() {
        return type + " " + (time == null ? start : time) + " " + (name == null ? "" : name + ": ") + text;
    }
}

