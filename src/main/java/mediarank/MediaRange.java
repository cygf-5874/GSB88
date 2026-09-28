package mediarank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 一条 media range，形如 {@code type/subtype;name=value;q=0.5}。
 */
public final class MediaRange {

    /** RFC 7230 token 允许的字符集（不含分隔符）。 */
    private static final Pattern TOKEN =
            Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]+");

    /** q 的十进制字面量：非负、无符号、无指数；小数位长度不做限制（超出 3 位截断）。 */
    private static final Pattern Q_LITERAL = Pattern.compile("(\\d+)(?:\\.(\\d+))?");

    private final String type;
    private final String subtype;
    private final int qMilli;
    private final Map<String, String> params;

    private MediaRange(String type, String subtype, int qMilli, LinkedHashMap<String, String> params) {
        this.type = type;
        this.subtype = subtype;
        this.qMilli = qMilli;
        this.params = Collections.unmodifiableMap(params);
    }

    /**
     * 解析**单条** media range。
     *
     * @throws RankException            {@code text} 是 malformed
     * @throws IllegalArgumentException {@code text} 为 {@code null}
     */
    public static MediaRange parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("media range 不能为 null");
        }
        List<String> segments = splitSemicolons(text);
        if (segments.isEmpty()) {
            throw new RankException("media range 为空：" + text);
        }

        String mediaType = trimOws(segments.get(0));
        int slash = mediaType.indexOf('/');
        if (slash < 0 || mediaType.indexOf('/', slash + 1) >= 0) {
            throw new RankException("media range 必须形如 type/subtype：" + text);
        }
        String type = mediaType.substring(0, slash);
        String subtype = mediaType.substring(slash + 1);
        if (!isToken(type) || !isTokenOrStar(subtype)) {
            throw new RankException("type/subtype 不合法：" + text);
        }
        if ("*".equals(type) && !"*".equals(subtype)) {
            throw new RankException("type 为 * 时 subtype 也必须为 *：" + text);
        }

        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        int qMilli = -1;
        for (int i = 1; i < segments.size(); i++) {
            String segment = trimOws(segments.get(i));
            if (segment.isEmpty()) {
                throw new RankException("存在空参数段：" + text);
            }
            int eq = segment.indexOf('=');
            if (eq <= 0) {
                throw new RankException("参数必须形如 name=value：" + text);
            }
            String name = trimOws(segment.substring(0, eq));
            if (!isToken(name)) {
                throw new RankException("参数名不合法：" + text);
            }
            String rest = segment.substring(eq + 1);
            String value = parseValue(rest, text);

            if (name.toLowerCase(Locale.ROOT).equals("q")) {
                if (qMilli >= 0) {
                    throw new RankException("q 只允许出现一次：" + text);
                }
                qMilli = parseQ(value, text);
            } else {
                params.put(name.toLowerCase(Locale.ROOT), value);
            }
        }
        if (qMilli < 0) {
            qMilli = 1000;
        }
        return new MediaRange(type, subtype, qMilli, params);
    }

    /**
     * 按 `,` 把一条请求头切成多条 media range 并逐条解析。
     * malformed 的条目只忽略它自己；空条目忽略。
     *
     * @throws IllegalArgumentException {@code header} 为 {@code null}
     */
    public static List<MediaRange> parseHeader(String header) {
        if (header == null) {
            throw new IllegalArgumentException("header 不能为 null");
        }
        List<MediaRange> result = new ArrayList<>();
        for (String entry : splitCommas(header)) {
            String trimmed = trimOws(entry);
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                result.add(parse(trimmed));
            } catch (RankException ignored) {
                // malformed 条目只忽略自己
            }
        }
        return result;
    }

    /** `type` 的**原文**（不折叠大小写）。 */
    public String type() {
        return type;
    }

    /** `subtype` 的**原文**（不折叠大小写）；`type/*` 形式时为 `"*"`。 */
    public String subtype() {
        return subtype;
    }

    /** 质量因子，缺省 `1.0`，已在千分位定点化。 */
    public double q() {
        return qMilli / 1000.0;
    }

    /** 参数（不含 `q`）：键小写（`Locale.ROOT`）、值原文、按出现顺序、不可修改。 */
    public Map<String, String> params() {
        return params;
    }

    // -------------------------------------------------------- 内部工具

    /** 千分位定点 q（包内可见，供排序使用，避免二进制 double 误差）。 */
    int qMilli() {
        return qMilli;
    }

    /** 特异性：具体类型 2，子类型通配 1，全通配 0。 */
    int specificity() {
        if ("*".equals(type)) {
            return 0;
        }
        return "*".equals(subtype) ? 1 : 2;
    }

    /** 大小写不敏感（{@link Locale#ROOT}）地判断是否命中给定候选类型。 */
    boolean matches(String candidateType, String candidateSubtype) {
        if ("*".equals(type)) {
            return true;
        }
        if (!type.toLowerCase(Locale.ROOT).equals(candidateType.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return "*".equals(subtype)
                || subtype.toLowerCase(Locale.ROOT).equals(candidateSubtype.toLowerCase(Locale.ROOT));
    }

    private static boolean isToken(String s) {
        return !s.isEmpty() && TOKEN.matcher(s).matches();
    }

    private static boolean isTokenOrStar(String s) {
        return "*".equals(s) || isToken(s);
    }

    /** 去掉串首尾的 OWS（空格或制表符）。 */
    private static String trimOws(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isOws(s.charAt(start))) {
            start++;
        }
        while (end > start && isOws(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isOws(char c) {
        return c == ' ' || c == '\t';
    }

    /**
     * 解析参数值：允许引号值 {@code name="..."}（其中 `,`、`;` 为普通字符），
     * 否则必须是不含空白的 token 字面量。
     */
    private static String parseValue(String rest, String whole) {
        String afterNameOws = trimLeadingOws(rest);
        if (afterNameOws.isEmpty()) {
            throw new RankException("参数缺少值：" + whole);
        }
        if (afterNameOws.charAt(0) == '"') {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            boolean closed = false;
            while (i < afterNameOws.length()) {
                char c = afterNameOws.charAt(i);
                if (c == '"') {
                    closed = true;
                    i++;
                    break;
                }
                if (c == '\\' && i + 1 < afterNameOws.length()) {
                    sb.append(afterNameOws.charAt(i + 1));
                    i += 2;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            if (!closed) {
                throw new RankException("引号值未闭合：" + whole);
            }
            if (!trimOws(afterNameOws.substring(i)).isEmpty()) {
                throw new RankException("引号值后存在多余字符：" + whole);
            }
            return sb.toString();
        }
        String value = trimOws(afterNameOws);
        if (!isToken(value)) {
            throw new RankException("未加引号的参数值不合法：" + whole);
        }
        return value;
    }

    private static String trimLeadingOws(String s) {
        int i = 0;
        while (i < s.length() && isOws(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    /** 把 q 的十进制字面量向下截断到千分位；非法或超出 [0,1] 抛 {@link RankException}。 */
    private static int parseQ(String value, String whole) {
        var m = Q_LITERAL.matcher(value);
        if (!m.matches()) {
            throw new RankException("q 不是十进制字面量：" + whole);
        }
        String intPart = m.group(1);
        String fracPart = m.group(2) == null ? "" : m.group(2);
        String frac3 = (fracPart + "000").substring(0, 3);
        int milli;
        try {
            int wholeInt = Integer.parseInt(intPart);
            int fracInt = Integer.parseInt(frac3);
            milli = Math.multiplyExact(wholeInt, 1000) + fracInt;
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new RankException("q 数值超出范围：" + whole);
        }
        if (milli < 0 || milli > 1000) {
            throw new RankException("q 必须落在 [0,1]：" + whole);
        }
        return milli;
    }

    /** 按 `,` 切分，引号内的 `,` 不算分隔符；引号未闭合则整体判 malformed。 */
    private static List<String> splitCommas(String s) {
        List<String> out = new ArrayList<>();
        int start = 0;
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        // 引号未闭合交给单条解析判 malformed（parseHeader 里只忽略该条）
        out.add(s.substring(start));
        return out;
    }

    /** 按 `;` 切分单条 range，引号内的 `;` 不算分隔符；引号未闭合判 malformed。 */
    private static List<String> splitSemicolons(String s) {
        List<String> out = new ArrayList<>();
        int start = 0;
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ';' && !inQuotes) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (inQuotes) {
            throw new RankException("引号未闭合：" + s);
        }
        out.add(s.substring(start));
        return out;
    }
}
