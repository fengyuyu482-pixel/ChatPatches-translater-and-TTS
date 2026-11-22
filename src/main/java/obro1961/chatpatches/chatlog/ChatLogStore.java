package obro1961.chatpatches.chatlog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 聊天记录的存储 —— <b>一天一个文件夹、一个地点一个文件夹、一个文件</b>。
 *
 * <pre>
 * config/chatpatches/chatlogs/
 *   2026-10-10/                  ← 启动游戏那天的日期
 *     Minecraft服务器/            ← 服务器名（单人是 单人_世界名）
 *       聊天记录.jsonl
 * </pre>
 *
 * <p><b>核心原则：只追加，永不重写。</b>
 * 每次保存都把整个文件重写一遍的做法，写到一半崩了整份记录就废了；
 * 一行一条、只在末尾追加，崩了最多丢最后一行。这也是原版 Chat Patches 最大的隐患。
 *
 * <p>本类<b>刻意不依赖 Minecraft</b>（日期和地点由调用方传进来），所以能独立编译和测试。
 */
public final class ChatLogStore {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    /** 文件主名。 */
    public static final String FILE_NAME = "聊天记录.jsonl";

    /** 名字太长就截断。 */
    private static final int MAX_NAME_LEN = 40;

    private final Path root;

    /** @param root 根目录，一般是 {@code config/chatpatches/chatlogs} */
    public ChatLogStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }    // ================= M2b：新布局（一天一个文件） =================

    /** 新布局：{@code chatlogs/日期.jsonl} —— 一层，日期就是文件名。 */
    public Path dayFile(String day) {
        return root.resolve(sanitize(day) + ".jsonl");
    }

    /**
     * <b>全局递增</b>的 seq（2026-10-21 改）。
     *
     * <p>以前是"每天从 1 开始"，跨天会撞号：灌回合并两天时译文会挂到同号的另一天的消息上
     * （实测 1207 条译文里 172 条会错）。改成全局之后，一个号贯穿所有日期。
     *
     * <p>启动后第一次写记录时扫一遍所有 {@code .jsonl} 取最大 seq，之后内存 +1。
     * 5888 条大约几十毫秒，只发生一次。
     */
    /** 持久化下限（来自 5-record.json 的 lastSeq）：删光文件也不回到 1。 */
    private int minSeq = 0;

    /** 设置 seq 下限（启动时由 bridge 从配置传入）。 */
    public void setMinSeq(int minSeq) {
        this.minSeq = Math.max(0, minSeq);
        this.globalMax = null;   // 让下次 nextSeq 重新取 max
    }

    /** 当前已发出的最大 seq（没发过就返回 minSeq）。给 bridge 回写配置用。 */
    public int currentMaxSeq() {
        return globalMax == null ? minSeq : Math.max(minSeq, globalMax);
    }

    private Integer globalMax;

    private int nextSeq() throws IOException {
        if (globalMax == null) {
            int max = 0;
            if (Files.isDirectory(root)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
                    for (Path f : walk.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                        java.util.List<String> all = Files.readAllLines(f, StandardCharsets.UTF_8);
                        for (String s : all) {
                            LogEntry e = parse(s);
                            if (e != null && e.seq != null && e.seq > max) {
                                max = e.seq;
                            }
                        }
                    }
                }
            }
            globalMax = Math.max(max, minSeq);
        }
        return ++globalMax;
    }
    /** 新布局追加：自动分配 {@code seq}、自动补 {@code place}。返回写入的记录（含 seq）。 */
    public synchronized LogEntry append(String day, LogEntry entry) throws IOException {
        Path f = dayFile(day);
        Files.createDirectories(f.getParent());
        if (entry.needsSeq() && entry.seq == null) {
            entry.seq = nextSeq();
        }
        write(f, entry);
        return entry;
    }


    /** 按 {@code seq} 标记删除（不真删行）。 */
    public synchronized void markDeletedSeq(String day, int targetSeq, String by, String at) throws IOException {
        append(day, LogEntry.deletionOfSeq(targetSeq, by, at));
    }

    /** 新布局读：剔除 delete 命中（按 targetSeq 或旧 line）与 delete 行本身。 */
    public List<Line> readDay(String day) throws IOException {
        Path flat = dayFile(day);
        if (Files.isRegularFile(flat)) {
            return parseFile(flat, day);
        }
        // 兼容旧布局 B：日期/聊天记录.jsonl（目录还在、但文件没按新布局放）
        Path dirFile = root.resolve(sanitize(day)).resolve(FILE_NAME);
        return Files.isRegularFile(dirFile) ? parseFile(dirFile, day) : List.of();
    }

    /**
     * 旧布局读：{@code 日期/地点/聊天记录.jsonl}。
     * 补 {@code place}（目录名）、按行号造临时 {@code seq}、标 {@code legacy=true}。
     */
    public List<Line> readLegacyDay(String day) throws IOException {
        List<Line> out = new ArrayList<>();
        for (String place : places(day)) {
            Path f = file(day, place);
            if (!Files.isRegularFile(f)) {
                continue;
            }
            for (Line l : parseFile(f, day)) {
                LogEntry e = l.entry();
                if (e.place == null) {
                    e.place = place;
                }
                e.legacy = Boolean.TRUE;
                if (e.needsSeq() && e.seq == null) {
                    e.seq = l.no();
                }
                out.add(l);
            }
        }
        return out;
    }

    /** 解析一个文件：先收集删除标记，再逐行过滤。 */
    private List<Line> parseFile(Path f, String day) throws IOException {
        List<String> raw = Files.readAllLines(f, StandardCharsets.UTF_8);
        Set<Integer> deletedSeqs = new HashSet<>();
        for (String s : raw) {
            LogEntry e = parse(s);
            if (e != null && e.isDeletion()) {
                if (e.targetSeq != null) {
                    deletedSeqs.add(e.targetSeq);
                }
            }
        }
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            int no = i + 1;
            LogEntry e = parse(raw.get(i));
            if (e == null || e.isDeletion()) {
                continue;
            }
            if (e.seq != null && deletedSeqs.contains(e.seq)) {
                continue;
            }
            out.add(new Line(no, day, e));
        }
        return out;
    }

    // ---- 名字与路径 ----

    /**
     * 把名字里 Windows 文件名不允许的字符<b>直接删掉</b>。
     *
     * <p>{@code A/B 服} → {@code AB 服}。日期不会有非法字符，这条只对"服务器/世界名"起作用。
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "未知";
        }
        // Windows 不允许： \ / : * ? " < > |
        String s = raw.replaceAll("[\\\\/:*?\"<>|]", "");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) {
            s = "未知";
        }
        return s.length() > MAX_NAME_LEN ? s.substring(0, MAX_NAME_LEN) : s;
    }

    /** 单人世界的地点名：{@code 单人_世界名}。 */
    public static String singleplayerPlace(String worldName) {
        return "单人_" + sanitize(worldName);
    }

    /** 这个地点对应的文件。 */
    public Path file(String day, String place) {
        return root.resolve(sanitize(day)).resolve(sanitize(place)).resolve(FILE_NAME);
    }

    public Path folder(String day, String place) {
        return root.resolve(sanitize(day)).resolve(sanitize(place));
    }

    // ---- 写 ----

    /**
     * 追加一条记录（文件夹不存在会自动建）。
     *
     * @return <b>刚写进去那一行的物理行号</b>（1 起）。
     *         文件是只追加的，所以这个行号<b>永不再变</b> ——
     *         其它记录可以拿它当引用（比如译文行记下"我挂在第几行"、
     *         删除标记记下"我要删第几行"）。
     */
    public synchronized int append(String day, String place, LogEntry entry) throws IOException {
        if (entry.place == null) {
            entry.place = place;
        }
        LogEntry saved = append(day, entry); // 新布局：一天一个文件 + 自动 seq
        return saved.seq == null ? 0 : saved.seq;
    }

    /** 会话开始：在文件里追加一行 session 头（同一天同地点多次启动就多行，天然分场）。 */
    public int writeSession(String day, String place, String fullStart) throws IOException {
        return append(day, place, LogEntry.session(fullStart));
    }

    /**
     * 每个文件已写到第几行 —— <b>只数一次</b>。
     *
     * <p>为什么不每次数：{@code Files.lines().count()} 是 O(文件行数)，
     * 每条消息数一遍就是 O(n²)，记录大了会把主线程拖住。
     * 只追加的文件行数只增不减，所以数一次之后 +1 就够。
     */
    private final java.util.Map<Path, Integer> lineCounts = new java.util.concurrent.ConcurrentHashMap<>();

    private int write(Path f, LogEntry entry) throws IOException {
        String json = GSON.toJson(entry);
        Integer known = lineCounts.get(f);
        if (known == null) {
            known = 0;
            if (Files.exists(f)) {
                try (java.util.stream.Stream<String> s = Files.lines(f, StandardCharsets.UTF_8)) {
                    known = (int) s.count();
                }
            }
        }
        Files.writeString(f, json + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        int no = known + 1;
        lineCounts.put(f, no);
        return no;
    }

    // ---- 读 ----

    /**
     * 读回来的一条：行号 + <b>所属日期</b> + 内容。
     *
     * <p><b>为什么要带 day</b>：灌回会合并"今天 + 前一天"，两天的 `time` 都是 `HH:mm:ss`。
     * 只按 time 排会把今天 00:10 排到昨天 23:50 前面 —— 历史顺序整个乱掉（实机看到"分割线上面是昨天的消息"）。
     */
    public record Line(int no, String day, LogEntry entry) {
        /** 兼容老调用点（测试/合成数据）：没有日期。 */
        public Line(int no, LogEntry entry) {
            this(no, null, entry);
        }

        public boolean isChat() {
            return entry.isChat();
        }
    }

    /**
     * 读回整个文件。
     *
     * <p>已经<b>被标记删除的行会被剔掉</b>；会话头和删除标记本身也会返回
     * （调用方按需过滤，比如"只要聊天"就 {@code line.entry().isChat()}）。
     *
     * @return 按文件顺序排列的记录；文件不存在则返回空列表
     */
    public List<Line> read(String day, String place) throws IOException {
        return readDay(day); // 新布局：一天一个文件，地点只当标签
    }

    private List<Line> readLegacyFile(String day, String place) throws IOException {
        Path f = file(day, place);
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        List<String> raw = Files.readAllLines(f, StandardCharsets.UTF_8);

        // 第一遍：先收集"哪些行被标记删除了"
        Set<Integer> deleted = new HashSet<>();
        for (String s : raw) {
            if (s.isBlank()) {
                continue;
            }
            LogEntry e = parse(s);
            // 旧版按行号删除的标记：v1 迁移已转成 targetSeq，这里不再认 line
        }

        // 第二遍：按行号收集。
        // 跳过两类：① 被标记删除的 ② 删除标记本身（它是元数据，不是记录，不该出现在读取结果里）
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            int no = i + 1;
            String s = raw.get(i);
            if (s.isBlank()) {
                continue;
            }
            LogEntry e = parse(s);
            if (e == null || e.isDeletion()) {
                continue;
            }
            out.add(new Line(no, day, e));
        }
        return out;
    }

    /** 只取聊天（不含会话头、系统消息、删除标记）。 */
    public List<Line> readChats(String day, String place) throws IOException {
        List<Line> out = new ArrayList<>();
        for (Line l : read(day, place)) {
            if (l.entry().isChat()) {
                out.add(l);
            }
        }
        return out;
    }

    private static LogEntry parse(String line) {
        try {
            return GSON.fromJson(line, LogEntry.class);
        } catch (JsonSyntaxException e) {
            return null; // 单行坏了不该拖垮整个文件
        }
    }

    /** 列出一个日期下有哪些地点文件夹（仅旧布局）。 */
    public List<String> places(String day) throws IOException {
        Path d = root.resolve(sanitize(day));
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        try (var s = Files.list(d)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    /** 列出所有日期（新布局是 {@code 日期.jsonl} 文件，旧布局是日期文件夹），新的在前。 */
    public List<String> days() throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var s = Files.list(root)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jsonl") || Files.isDirectory(p))
                    .map(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".jsonl") ? n.substring(0, n.length() - 6) : n;
                    })
                    .distinct()
                    .sorted(java.util.Comparator.reverseOrder())
                    .toList();
        }
    }
}
