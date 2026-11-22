package obro1961.chatpatches.translate;

import java.util.Locale;

/**
 * 字形识别：判断一条消息是不是已经是目标语言。
 *
 * <p>为什么不用"含汉字就跳过"那种土办法：日文里也有汉字，那样会把日文误跳。
 * 按字形区分就不会：有假名 → 日文；只有汉字无假名 → 中文。
 *
 * <p>翻译和朗读共用它：
 * <ul>
 *   <li>翻译：已经是目标语言就不翻</li>
 *   <li>朗读：只读"需要翻译的那些"原文 —— 也就是这个判断为 false 的</li>
 * </ul>
 */
public final class ScriptDetector {

    private ScriptDetector() {
    }

    public static boolean hasHan(String s) {
        return anyInRange(s, 0x4E00, 0x9FFF) || anyInRange(s, 0x3400, 0x4DBF);
    }

    public static boolean hasKana(String s) {
        return anyInRange(s, 0x3040, 0x30FF);
    }

    public static boolean hasHangul(String s) {
        return anyInRange(s, 0xAC00, 0xD7AF) || anyInRange(s, 0x1100, 0x11FF);
    }

    public static boolean hasCyrillic(String s) {
        return anyInRange(s, 0x0400, 0x04FF);
    }

    public static boolean hasLatinLetter(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasDigit(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一条文本的字形画像 —— <b>全项目只在这里判断"这段文本是什么构成"</b>。
     *
     * <p><b>为什么要收拢</b>：这个判断原本散在五处各写一遍 ——
     * 翻译判"有没有真文字"、朗读判"能不能念"、GET 接口猜语言代码、
     * 嗓音选择判中英文、目标语言判断。改一处忘一处，已经出过一次真实故障：
     * 朗读那处没把数字算进去，于是"数字读英文"这条规则根本不生效，
     * 而代码上完全看不出来（两处各自的逻辑都自洽）。
     *
     * <p>现在只算一次，五处引用同一份结果 —— 要加字形只改这里。
     */
    public record Profile(boolean han, boolean kana, boolean hangul,
                          boolean cyrillic, boolean latin, boolean digit) {

        /** 有"真正是文字"的字符（<b>不含数字</b>）—— 翻译用它判断这段值不值得送出去。 */
        public boolean hasLetters() {
            return han || kana || hangul || cyrillic || latin;
        }

        /** 有"能念出来"的东西 —— 朗读用它。<b>数字也算</b>，因为 2333 是要念的。 */
        public boolean hasSpeakable() {
            return hasLetters() || digit;
        }

        /** 东亚文字（中文/日文）—— 决定用中文还是英文嗓音。 */
        public boolean isCjk() {
            return han || kana;
        }

        /** 猜一个语言代码。GET 式翻译接口要用，它们不接受 {@code auto}。 */
        public String langCode() {
            if (han) {
                return "zh-CN";
            }
            if (kana) {
                return "ja";
            }
            if (hangul) {
                return "ko";
            }
            if (cyrillic) {
                return "ru";
            }
            return "en";
        }
    }

    /** 算一次，到处用。 */
    public static Profile profile(String s) {
        return new Profile(hasHan(s), hasKana(s), hasHangul(s), hasCyrillic(s),
                hasLatinLetter(s), hasDigit(s));
    }

    private static boolean anyInRange(String s, int lo, int hi) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp >= lo && cp <= hi) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * 这条消息是否已经是目标语言（是就不用翻了）。
     *
     * <p>认不出来的目标语言一律返回 false —— 宁可多翻一次，也别该翻的不翻。
     */
    public static boolean isTargetLanguage(String text, String targetLanguage) {
        if (text == null || text.isBlank() || targetLanguage == null) {
            return false;
        }
        String t = targetLanguage.toLowerCase(Locale.ROOT);
        Profile p = profile(text);
        boolean han = p.han();
        boolean kana = p.kana();
        boolean hangul = p.hangul();
        boolean cyrillic = p.cyrillic();
        boolean latin = p.latin();

        if (t.contains("中文") || t.contains("chinese") || t.startsWith("zh")) {
            return han && !kana && !hangul;
        }
        // ★ 西/法必须在 English 之前判：它们也是拉丁字母，否则 "hola qué tal" 会被当成"已经是英文"。
        if (t.contains("西班牙") || t.contains("西语") || t.contains("spanish") || t.startsWith("es")
                || t.contains("español") || t.contains("espanol")) {
            return !isVietnamese(text) && (hasSpanishMarks(text) || looksLike(text, ES_WORDS));
        }
        if (t.contains("法语") || t.contains("法文") || t.contains("french") || t.startsWith("fr")
                || t.contains("français") || t.contains("francais")) {
            return !isVietnamese(text) && (hasFrenchMarks(text) || looksLike(text, FR_WORDS));
        }
        if (t.contains("english") || t.contains("英文") || t.startsWith("en")) {
            // ★ 必须排除越南语：越南语也是拉丁字母，{@code latin} 对它为 true ✗ ——
            //   不排除的话 "cảm ơn" 会被判成"已经是英文"，于是越南语的值会被英文目标误用。
            return latin && !isVietnamese(text) && !han && !kana && !hangul && !cyrillic;
        }
        if (t.contains("日本語") || t.contains("日文") || t.contains("japanese") || t.startsWith("ja")) {
            return kana;
        }
        if (t.contains("한국") || t.contains("korean") || t.startsWith("ko")) {
            return hangul;
        }
        if (t.contains("рус") || t.contains("russian") || t.contains("俄语") || t.contains("俄文") || t.startsWith("ru")) {
            return cyrillic;
        }
        if (t.contains("越南") || t.contains("vietnam") || t.startsWith("vi")) {
            // ★ 越南语必须【单独】认 —— 它是拉丁字母，上面那条 English 分支会把
            //   "cảm ơn" 也判成英文 ✗（latin=true、没有汉字假名韩文西里尔）。
            //   所以这条要放在前面（见下面 isVietnamese 的说明）。
            return isVietnamese(text);
        }
        return false;
    }

    /**
     * 这段文本是不是越南语 —— 靠它的<b>专属</b>字符认，不靠"是不是拉丁字母"。
     *
     * <p><b>为什么不能只看拉丁字母</b>：越南语<b>就是</b>拉丁字母 ✗，
     * {@link Profile#latin()} 对 "cảm ơn" 和 "hello" 一视同仁 —— 分不出来。
     *
     * <p>但它有两类别的语言不用的记号：
     * <ul>
     *   <li>{@code ă â đ ê ô ơ ư} 及大写（拉丁扩展-A 里越南专用的那几个）</li>
     *   <li>{@code U+1EA0–U+1EFF}（拉丁扩展附加区）—— <b>这一整块基本就是越南语</b></li>
     * </ul>
     * 比 {@code é à ã}（法语/西语/葡语也大量用）可靠得多，
     * 不会因为聊天里有人写 "café" 就误判。
     *
     * <p><b>代价（如实说）</b>：不打声调的越南语（"toi di ngu"）认不出来 ✗ ——
     * 那和英文/拼音在字形上真的没区别。实际聊天里越南人基本都打声调，够用。
     */
    public static boolean isVietnamese(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u1EA0' && c <= '\u1EFF') {
                return true;
            }
            if (c >= '\u0102' && c <= '\u0103') {   // Ă ă
                return true;
            }
            if (c >= '\u0110' && c <= '\u0111') {   // Đ đ
                return true;
            }
            if (c >= '\u01A0' && c <= '\u01B0') {   // Ơ ơ Ư ư
                return true;
            }
        }
        return false;
    }

    /**
     * <b>提示词里该用的语言名</b> —— 必须转成<b>英语名</b>。
     *
     * <p>实测（2026-10-13，同一个 B站端点、同样的输入）：
     * <pre>
     *   提示词写 "Translate the text into 越南语"     → oh 出来是「不错」✗（竟然翻成中文）
     *   提示词写 "Translate the text into Vietnamese" → oh →「ồ」✓ yes →「Vâng」✓
     * </pre>
     * 写中文语言名时，那个模型<b>会把它当成"翻成中文"</b> ✗ ——
     * 用户实测反馈过这个现象：「越南语版本好像还是把英语翻译成中文而不是越南语」。
     *
     * <p>所以内部继续用中文名（配置里写「越南语」对用户友好 ✓），
     * <b>但发给模型前一律转成英语名</b> ✓
     *
     * <p>未知语言原样传下去 —— 总比丢掉强（用户可能填 "Tiếng Việt" 之类）。
     */
    public static String promptLanguageName(String target) {
        // ★ 注册表是唯一来源：{@code fr_fr → French}、{@code 简体中文 → Mandarin Chinese}
        LanguageRegistry.Lang lang = Language.find(target);
        if (lang != null && lang.promptName != null && !lang.promptName.isBlank()) {
            return lang.promptName;
        }
        // 注册表认不出 → 原样传（用户可能填了 "Tiếng Việt" 这种自定义写法）
        // 注意：这里不再走"中文原样返回"的老特例 —— 注册表已经给了 Mandarin Chinese，
        //      而写 "Translate into Chinese" 和 "Mandarin Chinese" 模型都懂，但后者更准。
        if (target == null || target.isBlank()) {
            return "English";
        }
        return target;
    }

    /**
     * <b>文本里有没有【这门语言】的字符</b> —— 出站闸门的唯一判据。
     *
     * <p>★ 这是用户要求「严格按照配置文件」后的形态：
     * 语言从<b>配置</b>来（{@code outgoingSourceLanguage}），
     * 这里只回答"有没有它的字" ✓ —— 不再有"我猜这是哪国话" ✗。
     *
     * <p>每种语言的字符集是<b>固定的、可查的规则</b>（不是启发式）：
     * <pre>
     *   中文    汉字（CJK 统一表意）              → 有汉字就翻 ✓
     *   越南语  带变音符号的拉丁字母 + 越南专属字   → à é ơ ư 都在内 ✓
     *   英语    纯 ASCII 拉丁字母                  → 一般用不上（打英文本来就不翻）
     *   日语    假名（含汉字则交给中文？不 —— 只认假名，见下）
     *   韩语    谚文      俄语  西里尔
     * </pre>
     *
     * <p>{@code "auto"} 是<b>显式选择</b>的老行为：按 profile 猜 ✗（保留但不再默认）。
     */
    /** 西语专用记号：倒问号/倒叹号 + ñ + 重音元音。 */
    public static boolean hasSpanishMarks(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("¿¡ñÑáéíóúÁÉÍÓÚüÜ".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** 法语专用记号：ç œ æ + 各类重音（à è ê ë î ï ô û ù ÿ）。 */
    public static boolean hasFrenchMarks(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("çÇœŒæÆàèêëîïôûùÿÀÈÊËÎÏÔÛÙŸ".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** 西语高频功能词（用来在"只有拉丁字母"时区分西/法/英）。 */
    private static final java.util.Set<String> ES_WORDS = java.util.Set.of(
            "que", "de", "el", "la", "los", "las", "un", "una", "es", "y", "en", "por", "para",
            "con", "no", "si", "sí", "pero", "como", "más", "mas", "muy", "hola", "gracias", "vale",
            "bueno", "bien", "mal", "qué", "cómo", "dónde", "cuando", "porque", "también", "yo", "tú");

    /** 法语高频功能词。 */
    private static final java.util.Set<String> FR_WORDS = java.util.Set.of(
            "le", "la", "les", "des", "du", "un", "une", "est", "et", "en", "pour", "avec", "pas",
            "que", "qui", "dans", "sur", "je", "tu", "il", "elle", "nous", "vous", "ils", "bonjour",
            "salut", "merci", "oui", "non", "très", "bien", "au", "aux", "ce", "cette", "mon", "ton");

    /** 按空格/标点切词后，命中比例够高就算这门语言。 */
    private static boolean looksLike(String text, java.util.Set<String> words) {
        if (text == null) {
            return false;
        }
        String cleaned = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]+", " ").trim();
        if (cleaned.isEmpty()) {
            return false;
        }
        String[] parts = cleaned.split(" +");
        int hit = 0;
        for (String w : parts) {
            if (words.contains(w)) {
                hit++;
            }
        }
        // 至少 1 个功能词，且占词数 20% 以上（短句也能判：hola → 0 个，'hola qué tal' → 2/3）
        return hit >= 1 && hit * 5 >= Math.max(1, parts.length);
    }
    public static boolean hasCharsOf(String text, String language) {
        if (text == null || text.isBlank() || language == null || language.isBlank()) {
            return false;
        }
        String lang = language.trim().toLowerCase(Locale.ROOT);
        if (lang.equals("auto")) {
            // 老的启发式（显式选 auto 才走这里）。
            // ★ 两个越南语判据【都要】—— 它们覆盖的码位不重叠：
            //     isVietnamese              U+1EA0–1EFF 等（越南语声调区，如 ủ U+1EE7）
            //     hasAccentedLatinLetters   U+00C0–024F（à é ơ ư 这类）
            //   少任何一个都会漏 —— 实测「ngủ ngon」只含 U+1EE7，
            //   只调后者会判成 false ✗（这是我第一版漏写的，自测当场抓到）
            Profile p = profile(text);
            return p.han() || p.kana() || p.hangul() || p.cyrillic()
                    || isVietnamese(text) || hasAccentedLatinLetters(text);
        }

        // ★★ 其它语言一律查注册表，按登记的文字系统判 —— <b>不再逐语言硬编码</b> ✗
        //
        //   【为什么要删掉那 30 行 if】
        //   旧代码对中/越/日/韩/俄/西/法/英各写一条 if，其余落到"有字母就翻"。
        //   三宗罪：
        //     ① 只覆盖 8 种，第 9 种就退化（法语 fr_ca 走不到，"英语→法语"时中文也翻）
        //     ② 条件靠字符串 contains，`fr_ca` 不含「法语」也不以 `fr-` 开头 → 全不命中
        //     ③ 与注册表重复，改一处忘一处（`Language` 已经是唯一事实源了）
        //
        //   【现在的判据】注册表给这门语言登记了 script，就按那个 script 比对文本构成。
        //   `en_us → eng → Latin`、`fr_ca → fra → Latin`、`cmn → 汉字比例`……
        //   新增语言不用改这里（数据表里加一行即可）。
        LanguageRegistry.Lang entry = Language.find(language);
        if (entry == null) {
            return false;   // 语言不在注册表内 → 不翻（这是"严格定向"）
        }

        // ★ 有一条纯 script 判定做不到的：<b>同一 script 下的近亲语言</b>。
        //   Spanish / French / Vietnamese 都是拉丁字母，看字形分不出来 ——
        //   但它们的特征字符（ñ ¿ ¡ / ç œ / ơ ư ạ）恰恰是决定性证据。
        //   所以先让这些"特征字"有机会发言，命中就用它。
        String id = entry.id == null ? "" : entry.id;
        if ("vie".equals(id) && (isVietnamese(text) || hasAccentedLatinLetters(text))) {
            return true;    // 越南语声调符号是硬证据
        }
        if ("spa".equals(id) && hasSpanishMarks(text)) {
            return true;    // ¿ ¡ ñ 是西语专属
        }
        if ("fra".equals(id) && hasFrenchMarks(text)) {
            return true;    // ç œ æ 是法语专属
        }

        return matchesScript(text, entry);
    }

    /**
     * 文本的主要字符构成，是否符合这门语言的 script。
     *
     * <p><b>这是"严格定向"的核心</b>：配了源语言 = 英语时，
     * 一条纯中文消息的 {@code matchesScript} 必须返回 false，
     * 否则定向翻译形同虚设。
     *
     * <p>判据是"<b>该 script 的字符数占全部字母的比例</b>"，不是"有没有"——
     * 「这个 build 很 buggy」里中英文都有，按比例判给占多数的那边。
     */
    public static boolean matchesScript(String text, LanguageRegistry.Lang lang) {
        if (text == null || text.isBlank() || lang == null) {
            return false;
        }
        String script = lang.script == null ? "" : lang.script;
        Profile p = profile(text);

        // 先把"字母总数"算出来，作为分母
        int letters = countLetters(text);
        if (letters == 0) {
            return false;
        }

        // 拉丁语系：拉丁字母占绝对多数才算（阈值 0.5 —— 混排句子按多数归）
        if ("Latin".equalsIgnoreCase(script)) {
            int latin = countLatin(text);
            return latin * 2 >= letters;
        }
        // 汉字系（cmn 是 franc 的单语言 script 键名）
        if ("cmn".equalsIgnoreCase(script) || "Han".equalsIgnoreCase(script)) {
            int han = countHan(text);
            return han * 2 >= letters;
        }
        // 日文：假名或汉字都算（日文里汉字是常态）
        if ("jpn".equalsIgnoreCase(script) || "Japanese".equalsIgnoreCase(script)) {
            return p.kana() || (p.han() && !p.hangul());
        }
        if ("kor".equalsIgnoreCase(script) || "Hangul".equalsIgnoreCase(script)) {
            return p.hangul();
        }
        if ("Cyrillic".equalsIgnoreCase(script)) {
            return p.cyrillic();
        }
        if ("Arabic".equalsIgnoreCase(script)) {
            return hasArabic(text);
        }
        if ("Hebrew".equalsIgnoreCase(script)) {
            return hasHebrew(text);
        }
        if ("Devanagari".equalsIgnoreCase(script)) {
            return hasDevanagari(text);
        }
        if ("Thai".equalsIgnoreCase(script) || "tha".equalsIgnoreCase(script)) {
            return hasThai(text);
        }
        if ("Greek".equalsIgnoreCase(script) || "ell".equalsIgnoreCase(script)) {
            return hasGreek(text);
        }
        // 其它 script（Myanmar / Ethiopic / Tibetan / Canadian_Aboriginal…）：
        // 只要文本里不是拉丁/汉字/假名/谚文这类"常见混入字符"，就当作是它
        // —— 这些语言在 MC 聊天里极少出现，宽松一点不影响体验
        return !p.latin() && !p.han() && !p.kana() && !p.hangul() && !p.cyrillic();
    }

    // ---- 字符计数（matchesScript 用） ----

    private static int countLetters(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetter(cp)) {
                n++;
            }
            i += Character.charCount(cp);
        }
        return n;
    }

    private static int countLatin(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetter(cp) && cp < 0x250 && !isAccentedNonLatin(cp)) {
                n++;
            }
            i += Character.charCount(cp);
        }
        return n;
    }

    /** 排除希腊/西里尔等也在 0x250 以下但不是拉丁的字母。 */
    private static boolean isAccentedNonLatin(int cp) {
        return (cp >= 0x0370 && cp <= 0x03FF)      // 希腊
            || (cp >= 0x0400 && cp <= 0x04FF)      // 西里尔
            || (cp >= 0x0590 && cp <= 0x05FF)      // 希伯来
            || (cp >= 0x0600 && cp <= 0x06FF);     // 阿拉伯
    }

    private static int countHan(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if ((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)) {
                n++;
            }
            i += Character.charCount(cp);
        }
        return n;
    }

    private static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp >= 0x0600 && cp <= 0x06FF) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean hasHebrew(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp >= 0x0590 && cp <= 0x05FF) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean hasDevanagari(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp >= 0x0900 && cp <= 0x097F) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean hasThai(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp >= 0x0E00 && cp <= 0x0E7F) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean hasGreek(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp >= 0x0370 && cp <= 0x03FF) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /** 任意 Unicode 字母；给官方语言表里没有专门字形规则的语言兜底。 */
    public static boolean hasAnyLetter(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetter(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * <b>有没有"带变音符号的拉丁字母"</b> —— 也就是<b>不是纯英文</b>。
     *
     * <p>覆盖 {@code U+00C0–U+024F}（Latin-1 Supplement 的字母 + Latin Extended-A/B）：
     * <pre>
     *   à á ả ã è é ẻ ẽ ì í î ï ò ó ô õ ö ù ú û ü ý
     *   ă â đ ê ô ơ ư   ñ ç ø å ä  …
     * </pre>
     *
     * <p>★ <b>2026-10-13 加它是因为踩了个真坑</b>：
     * {@link #isVietnamese} 只认越南语<b>专属</b>的字符（{@code ă â đ ê ô ơ ư} + 声调区
     * {@code U+1EA0–U+1EFF}），当初是怕把法语 {@code café} 误判成越南语 ✗。
     * 但 <b>{@code à} / {@code é} 这些恰恰是越南语最常用的声调</b> ✗ ——
     * 用户打 {@code Chào nhé} 时，出站闸门判定「这是英文」→ <b>原样发出去，根本没翻</b> ✗
     * （用户实测：「我发越南语为什么没有用」）。
     *
     * <p><b>取舍我想反了</b>：把 {@code café} 当越南语翻一遍<b>完全无害</b> ✓✓，
     * 而漏掉越南人日常打的字<b>是致命的</b> ✗。所以出站闸门改用这条宽判据，
     * 只做「是不是纯英文」的区分；{@link #isVietnamese} 保持严格 ——
     * 那是给「识别具体语种」用的，两件事不该共用一条规则 ✓。
     */
    public static boolean hasAccentedLatinLetters(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u00C0' && c <= '\u024F'
                    && c != '\u00D7' && c != '\u00F7') {   // 排除 ×(乘号) ÷(除号)
                return true;
            }
        }
        return false;
    }
}