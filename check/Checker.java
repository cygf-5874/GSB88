import mediarank.MediaRange;
import mediarank.MediaRanker;
import mediarank.RankException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * mediarank 的固定验收程序（勿改）。
 *
 * <p>11 个场景覆盖 README「对外契约」的 11 条：parse 3 / rank 3 / select 2 / robust 2 / determinism 1。
 * 每个场景都在**独立 JVM 子进程**里跑（{@link ProcessBuilder}），带看门狗，超时判不通过；
 * 某个场景失败不会遮蔽其余场景（失败不早退）。判据全部确定性：没有随机源、没有墙钟、
 * 没有性能门槛，顺序相关的断言只依赖（`q` → 特异性 → 原始下标）这条写死的规则。
 *
 * <p>用法：java -cp out;out-check Checker [-list] [--only &lt;组名&gt;[,&lt;组名&gt;...]]
 */
public final class Checker {

    /** 单场景看门狗（秒）。 */
    private static final long WATCHDOG_SECONDS = 30L;

    /** 被检库的实例：无状态，全场共用一个。 */
    private static final MediaRanker RANKER = new MediaRanker();

    private interface Body {
        /** 返回 null 表示 PASS，否则返回可判定的失败原因（内含 期望=… 实际=…）。 */
        String run() throws Exception;
    }

    private static final class Scenario {
        final String name;
        final String group;
        final Body body;

        Scenario(String name, Body body) {
            this.name = name;
            this.body = body;
            int slash = name.indexOf('/');
            this.group = slash < 0 ? name : name.substring(0, slash);
        }
    }

    public static void main(String[] args) throws Exception {
        String scenarioOnly = null;
        List<String> onlyGroups = new ArrayList<>();
        boolean list = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-list".equals(a)) {
                list = true;
            } else if ("--only".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--only 需要一个组名");
                    System.exit(2);
                }
                for (String g : args[++i].split(",")) {
                    if (!g.isEmpty()) {
                        onlyGroups.add(g);
                    }
                }
            } else if ("--scenario".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--scenario 需要一个场景名");
                    System.exit(2);
                }
                scenarioOnly = args[++i];
            } else {
                System.out.println("未知参数：" + a);
                System.exit(2);
            }
        }

        List<Scenario> all = scenarios();

        if (list) {
            for (Scenario s : all) {
                System.out.println(s.name);
            }
            return;
        }

        if (scenarioOnly != null) {
            System.exit(runOne(all, scenarioOnly));
        }

        List<Scenario> selected = new ArrayList<>();
        for (Scenario s : all) {
            if (onlyGroups.isEmpty() || onlyGroups.contains(s.group)) {
                selected.add(s);
            }
        }
        if (selected.isEmpty()) {
            System.out.println("没有匹配的场景（--only " + onlyGroups + "）");
            System.exit(2);
        }

        int pass = 0;
        for (Scenario s : selected) {
            String line = runInChildProcess(s);
            System.out.println(line);
            if (line.startsWith("PASS ")) {
                pass++;
            }
        }
        System.out.println("结果：通过 " + pass + "/" + selected.size());
        if (pass != selected.size()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------- 子进程调度（一场景一 JVM）

    /** 在独立子进程里跑单个场景，把子进程给出的 PASS/FAIL 行原样返回。 */
    private static String runInChildProcess(Scenario s) {
        String name = s.name;
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(javaBin,
                "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8",
                "-cp", classpath, "Checker", "--scenario", name);
        pb.redirectErrorStream(true);

        Process proc;
        try {
            proc = pb.start();
        } catch (Exception ex) {
            return "FAIL " + name + "  期望=能在独立 JVM 子进程里跑该场景 实际=子进程启动失败：" + ex;
        }

        StringBuilder sink = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    synchronized (sink) {
                        sink.append(chunk);
                    }
                }
            } catch (Exception ignored) {
                // 子进程被强杀时读流会失败，忽略即可
            }
        }, "checker-child-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = proc.waitFor(WATCHDOG_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            proc.destroyForcibly();
            try {
                reader.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "FAIL " + name + "  期望=场景在 " + WATCHDOG_SECONDS + " 秒内给出 PASS/FAIL 实际="
                    + WATCHDOG_SECONDS + " 秒看门狗超时（子进程已强杀）";
        }
        try {
            reader.join(5000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        String out;
        synchronized (sink) {
            out = sink.toString().trim();
        }
        String line = null;
        for (String l : out.split("\n")) {
            String t = l.trim();
            if (t.startsWith("PASS ") || t.startsWith("FAIL ")) {
                line = t;
            }
        }
        if (line == null) {
            String shown = out.isEmpty() ? "(无输出)" : out.replace('\n', ' ');
            return "FAIL " + name + "  期望=子进程打印 PASS/FAIL 行 实际=退出码 "
                    + (proc.isAlive() ? "未知（仍存活）" : String.valueOf(proc.exitValue())) + "，输出=" + shown;
        }
        return line;
    }

    /** 子进程模式：就地跑一个场景，打印一行 PASS/FAIL，返回退出码。 */
    private static int runOne(List<Scenario> all, String name) {
        for (Scenario s : all) {
            if (!s.name.equals(name)) {
                continue;
            }
            String err;
            try {
                err = s.body.run();
            } catch (Throwable t) {
                err = "检查过程抛异常：" + t;
            }
            if (err == null) {
                System.out.println("PASS " + s.name);
                return 0;
            }
            if (!err.contains("期望=")) {
                err = "期望=场景 " + s.name + " 通过 实际=" + err;
            }
            System.out.println("FAIL " + s.name + "  " + err);
            return 1;
        }
        System.out.println("FAIL " + name + "  期望=场景名存在于固定件 实际=未找到该场景");
        return 1;
    }

    // ------------------------------------------------------------- 场景表

    private static List<Scenario> scenarios() {
        List<Scenario> out = new ArrayList<>();

        // ------------------------------------------------ parse（3）

        out.add(new Scenario("parse/case-and-params", () -> {
            String e;

            MediaRange r1 = MediaRange.parse("TEXT/HTML;Level=1;Charset=UTF-8;q=0.8");
            e = expectStr("type() 保留原文", r1.type(), "TEXT");
            if (e != null) return e;
            e = expectStr("subtype() 保留原文", r1.subtype(), "HTML");
            if (e != null) return e;
            e = expectQ("q()", r1.q(), 0.8);
            if (e != null) return e;
            e = expectParams("params()（键小写、值原文、按出现顺序、不含 q）", r1.params(),
                    List.of("level=1", "charset=UTF-8"));
            if (e != null) return e;

            MediaRange r2 = MediaRange.parse(" text/html ; charset=UTF-8 ");
            e = expectStr("OWS：type()", r2.type(), "text");
            if (e != null) return e;
            e = expectStr("OWS：subtype()", r2.subtype(), "html");
            if (e != null) return e;
            e = expectQ("OWS：q() 缺省", r2.q(), 1.0);
            if (e != null) return e;
            e = expectParams("OWS：params()", r2.params(), List.of("charset=UTF-8"));
            if (e != null) return e;

            MediaRange r3 = MediaRange.parse("text/html;charset=utf-8");
            e = expectParams("参数值大小写敏感", r3.params(), List.of("charset=utf-8"));
            if (e != null) return e;

            MediaRange r4 = MediaRange.parse("text/html;CHARSET=x");
            e = expectParams("参数名大小写不敏感", r4.params(), List.of("charset=x"));
            if (e != null) return e;

            MediaRange r5 = MediaRange.parse("text/html;b=2;a=1");
            e = expectParams("params() 按出现顺序（不是按键排序）", r5.params(), List.of("b=2", "a=1"));
            if (e != null) return e;

            MediaRange r6 = MediaRange.parse("text/*");
            e = expectStr("通配：subtype()", r6.subtype(), "*");
            if (e != null) return e;
            e = expectQ("通配：q()", r6.q(), 1.0);
            if (e != null) return e;
            e = expectParams("通配：params()", r6.params(), List.of());
            if (e != null) return e;

            MediaRange r7 = MediaRange.parse("*/*;q=0.5");
            e = expectStr("*/* 的 type()", r7.type(), "*");
            if (e != null) return e;
            e = expectStr("*/* 的 subtype()", r7.subtype(), "*");
            if (e != null) return e;
            e = expectQ("*/* 的 q()", r7.q(), 0.5);
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("parse/q-three-tiers", () -> {
            String[][] cases = {
                {"text/html", "1000"},
                {"text/html;q=1", "1000"},
                {"text/html;q=1.000", "1000"},
                {"text/html;q=0", "0"},
                {"text/html;q=0.5", "500"},
                {"text/html;Q=0.25", "250"},
                {"text/html;q=0.007", "7"},
                {"text/html;q=0.1", "100"},
                {"text/html;q=0.2", "200"},
                {"text/html;q=0.1234", "123"},
                {"text/html;q=0.9999", "999"},
                {"text/html;q=0.000", "0"},
            };
            for (String[] c : cases) {
                MediaRange r = MediaRange.parse(c[0]);
                String got = String.valueOf(milli(r.q()));
                if (!c[1].equals(got)) {
                    return "期望=parse(\"" + c[0] + "\") 的 q 定点化到千分位为 " + c[1]
                            + " 实际=" + got + "（q()=" + r.q() + "）";
                }
            }
            return null;
        }));

        out.add(new Scenario("parse/malformed-only-self", () -> {
            String e;

            List<MediaRange> mixed = MediaRange.parseHeader(
                    "text/plain;q=abc, text/html;q=0.5, application/json, q=0.9, text/*;q=1.5, text/xml;q=0.25");
            e = expectDescs("一条头里混着 malformed：只忽略它们自己", mixed,
                    List.of("text|html|500|", "application|json|1000|", "text|xml|250|"));
            if (e != null) return e;

            Object[][] alone = {
                {"text/html;q=0.5;q=0.6", "q 出现两次"},
                {"text/html;Q=0.5;q=0.6", "q 大小写不同的两次"},
                {"/html", "type 缺失"},
                {"text/", "subtype 缺失"},
                {"text", "没有 /"},
                {"*/html", "`*` 与普通 subtype 配对"},
                {"text/html;foo=\"unterminated", "引号未闭合"},
                {"text/html;q=2", "q > 1"},
                {"text/html;q=1.5", "q > 1（一位小数）"},
                {"text/html;q=-0.1", "q 带符号"},
                {"text/html;q=1e-1", "q 用指数记法"},
                {"text/html;q=", "q 没有值"},
                {"text/html;q", "q 没有 ="},
                {"text/pl ain", "token 内含空白"},
            };
            for (Object[] c : alone) {
                List<MediaRange> got = MediaRange.parseHeader((String) c[0]);
                if (!got.isEmpty()) {
                    return "期望=parseHeader(\"" + c[0] + "\")（" + c[1] + "）解析不出有效条目 实际="
                            + describeAll(got);
                }
            }

            e = expectDescs("空条目被忽略，其余照常", MediaRange.parseHeader("text/plain;q=0.5, "),
                    List.of("text|plain|500|"));
            if (e != null) return e;

            e = expectDescs("引号里的 `,` 不算分隔符", MediaRange.parseHeader("text/html;a=\"x,y\";q=0.5"),
                    List.of("text|html|500|a=x,y"));
            if (e != null) return e;

            e = expectDescs("零个有效条目 → 空列表", MediaRange.parseHeader("text/html;q=abc"),
                    List.of());
            if (e != null) return e;

            e = expectRankException(() -> MediaRange.parse("text/plain;q=abc"), "parse 单条：q=abc");
            if (e != null) return e;
            e = expectRankException(() -> MediaRange.parse("text/plain;q=0.5;q=0.6"), "parse 单条：q 重复");
            if (e != null) return e;
            e = expectStr("parse 单条：合法范围", MediaRange.parse("text/plain;q=0.5").type(), "text");
            if (e != null) return e;

            e = expectIae(() -> MediaRange.parse(null), "MediaRange.parse(null)");
            if (e != null) return e;
            e = expectIae(() -> MediaRange.parseHeader(null), "MediaRange.parseHeader(null)");
            if (e != null) return e;
            return null;
        }));

        // ------------------------------------------------ rank（3）

        out.add(new Scenario("rank/q-desc-spec-index", () -> {
            String e;
            List<String> offered = List.of(
                    "text/html", "application/json", "text/plain", "image/png", "application/json");
            e = expectList("q 降序 → 特异性降序 → 原始下标升序（重复项各自独立）",
                    rank("text/*;q=0.8, */*;q=0.9, text/html;q=0.8", offered),
                    List.of("application/json", "image/png", "application/json", "text/html", "text/plain"));
            if (e != null) return e;

            e = expectList("同 q 同特异性 → 原始下标升序",
                    rank("text/plain;q=0.1, text/html;q=0.1", List.of("text/html", "text/plain")),
                    List.of("text/html", "text/plain"));
            if (e != null) return e;

            e = expectList("同 q → 特异性降序",
                    rank("*/*;q=0.4, text/*;q=0.4", List.of("application/x", "text/y")),
                    List.of("text/y", "application/x"));
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("rank/most-specific-q", () -> {
            String e;
            e = expectList("多条命中取特异性最高的那条的 q（不是最大 q）",
                    rank("*/*;q=0.9, text/*;q=0.1, text/html;q=0.5",
                            List.of("text/plain", "text/html", "image/png")),
                    List.of("image/png", "text/html", "text/plain"));
            if (e != null) return e;

            e = expectList("三档通配同时命中时的特异性",
                    rank("*/*;q=1.0, text/*;q=0.2, text/html;q=0.4",
                            List.of("text/html", "text/plain", "application/json")),
                    List.of("application/json", "text/html", "text/plain"));
            if (e != null) return e;

            e = expectList("特异性更高的那条 q 更小 → 仍然用它",
                    rank("text/*;q=0.9, text/html;q=0.1", List.of("text/html", "text/plain")),
                    List.of("text/plain", "text/html"));
            if (e != null) return e;

            e = expectList("同一 q 下三档特异性依次降序",
                    rank("*/*;q=0.7, text/*;q=0.7, text/html;q=0.7",
                            List.of("application/x", "text/plain", "text/html")),
                    List.of("text/html", "text/plain", "application/x"));
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("rank/rejected-last", () -> {
            String e;
            e = expectList("被拒者垫底，且被拒者之间按特异性降序、下标升序",
                    rank("text/plain;q=0.9, application/json;q=0, image/png;q=0",
                            List.of("text/html", "image/png", "application/json", "text/plain")),
                    List.of("text/plain", "image/png", "application/json", "text/html"));
            if (e != null) return e;

            e = expectList("特异性能赢但 q=0 → 仍然垫底",
                    rank("*/*;q=0.5, text/html;q=0", List.of("text/html", "text/plain")),
                    List.of("text/plain", "text/html"));
            if (e != null) return e;

            e = expectList("没有 q=0 时全体被 */* 接受",
                    rank("*/*;q=0.3", List.of("text/html", "application/json")),
                    List.of("text/html", "application/json"));
            if (e != null) return e;
            return null;
        }));

        // ------------------------------------------------ select（2）

        out.add(new Scenario("select/highest", () -> {
            String e;
            e = expectOptional("取 rank 的第一个",
                    select("text/*;q=0.5, application/json;q=0.9",
                            List.of("text/plain", "application/json", "text/html")),
                    "application/json");
            if (e != null) return e;

            e = expectOptional("重复项里取第一个胜者",
                    select("*/*;q=0.3, text/html;q=0.9", List.of("text/html", "text/plain", "text/html")),
                    "text/html");
            if (e != null) return e;

            e = expectOptional("空头视同 */*;q=1.0",
                    select("", List.of("application/json")), "application/json");
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("select/all-rejected-empty", () -> {
            String e;
            e = expectEmpty("显式 q=0 拒绝全部", select("text/html;q=0", List.of("text/html", "application/json")));
            if (e != null) return e;
            e = expectEmpty("*/*;q=0 拒绝全部", select("*/*;q=0", List.of("a/b", "c/d")));
            if (e != null) return e;
            e = expectEmpty("唯一候选没被命中", select("text/html;q=0.5", List.of("application/json")));
            if (e != null) return e;
            e = expectEmpty("空 offered", select("text/html", List.of()));
            if (e != null) return e;
            e = expectEmpty("空头 + 空 offered", select("", List.of()));
            if (e != null) return e;
            e = expectList("全被拒时 rank 仍是全部候选",
                    rank("*/*;q=0", List.of("a/b", "c/d")), List.of("a/b", "c/d"));
            if (e != null) return e;
            return null;
        }));

        // ------------------------------------------------ robust（2）

        out.add(new Scenario("robust/zero-beats-wildcard", () -> {
            String e;
            e = expectList("q=0 的 */* 压过 q>0 的 text/*",
                    rank("text/*;q=0.8, */*;q=0", List.of("text/html", "application/json")),
                    List.of("text/html", "application/json"));
            if (e != null) return e;
            e = expectEmpty("同上，select 取不到任何候选",
                    select("text/*;q=0.8, */*;q=0", List.of("text/html", "application/json")));
            if (e != null) return e;
            e = expectList("q=0 写在前面也一样",
                    rank("*/*;q=0, text/*;q=0.8", List.of("text/html", "application/json")),
                    List.of("text/html", "application/json"));
            if (e != null) return e;
            e = expectList("只有 text/* 命中 q=0 时，命中的候选全部被排除",
                    rank("*/*;q=0.8, text/*;q=0", List.of("text/html", "application/json", "text/plain")),
                    List.of("application/json", "text/html", "text/plain"));
            if (e != null) return e;
            e = expectOptional("同上，select 取到未被命中的那一个",
                    select("*/*;q=0.8, text/*;q=0", List.of("text/html", "application/json", "text/plain")),
                    "application/json");
            if (e != null) return e;
            e = expectList("特异性更高的 q=0 仍然拒绝",
                    rank("text/*;q=0.8, text/html;q=0", List.of("text/html", "text/plain")),
                    List.of("text/plain", "text/html"));
            if (e != null) return e;
            e = expectEmpty("特异性更高的 q>0 压不住任意一条 q=0",
                    select("text/html;q=0.9, */*;q=0", List.of("text/html")));
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("robust/empty-offered-and-empty-header", () -> {
            String e;
            e = expectList("空 offered → 空列表", rank("", List.of()), List.of());
            if (e != null) return e;
            e = expectList("空 offered（头非空）→ 空列表", rank("text/html;q=0.5", List.of()), List.of());
            if (e != null) return e;
            e = expectEmpty("空 offered → select empty", select("", List.of()));
            if (e != null) return e;

            e = expectList("空 header 视同 */*;q=1.0",
                    rank("", List.of("text/html", "application/json")),
                    List.of("text/html", "application/json"));
            if (e != null) return e;
            e = expectList("解析后没有有效范围 → 同样视同 */*;q=1.0",
                    rank("q=0.5", List.of("text/html")), List.of("text/html"));
            if (e != null) return e;
            e = expectList("重复候选各自独立",
                    rank("text/html;q=0.5", List.of("text/html", "text/html")),
                    List.of("text/html", "text/html"));
            if (e != null) return e;

            e = expectIae(() -> rank(null, List.of()), "rank(null, ...)");
            if (e != null) return e;
            e = expectIae(() -> rank("text/html", null), "rank(..., null)");
            if (e != null) return e;
            e = expectIae(() -> rank("text/html", Arrays.asList("text/html", null)), "offered 含 null");
            if (e != null) return e;
            e = expectIae(() -> select(null, List.of("text/html")), "select(null, ...)");
            if (e != null) return e;
            e = expectIae(() -> select("text/html", null), "select(..., null)");
            if (e != null) return e;
            e = expectRankException(() -> rank("text/html", List.of("text/html;q=0.5")),
                    "offered 元素带参数");
            if (e != null) return e;
            return null;
        }));

        // ------------------------------------------------ determinism（1）

        out.add(new Scenario("determinism/repeat-identical", () -> {
            String header = "text/*;q=0.8, */*;q=0.3, text/html;q=0.8, application/json;Q=0.55, image/*;q=0.001";
            List<String> offered = List.of(
                    "text/html", "application/json", "text/plain", "image/png", "application/xml", "text/html");
            List<String> expectedRank = List.of(
                    "text/html", "text/html", "text/plain", "application/json", "application/xml", "image/png");

            String e = expectList("rank 首次", rank(header, offered), expectedRank);
            if (e != null) return e;
            e = expectOptional("select 首次", select(header, offered), "text/html");
            if (e != null) return e;

            for (int i = 0; i < 10; i++) {
                List<String> got = rank(header, offered);
                if (!expectedRank.equals(got)) {
                    return "期望=第 " + (i + 2) + " 次 rank 与首次逐元素相同 → " + expectedRank
                            + " 实际=" + got;
                }
            }
            for (int i = 0; i < 10; i++) {
                Optional<String> got = select(header, offered);
                if (got == null || !got.isPresent() || !"text/html".equals(got.get())) {
                    return "期望=第 " + (i + 2) + " 次 select 与首次相同 → Optional[text/html] 实际=" + got;
                }
            }

            List<String> copy = new ArrayList<>(offered);
            e = expectList("换一个 offered 实例结果不变", rank(header, copy), expectedRank);
            if (e != null) return e;

            String loose = " text/* ; q=0.8 , */* ; q=0.3 , text/html ; q=0.8 , "
                    + "application/json ; Q=0.55 , image/* ; q=0.001 ";
            e = expectList("只差空白与大小写的同一个头，结果相同", rank(loose, offered), expectedRank);
            if (e != null) return e;

            String text = "Text/HTML;Level=1;Charset=UTF-8;q=0.1234";
            List<String> base = describeAll(Collections.singletonList(MediaRange.parse(text)));
            e = expectList("parse 首次", base, List.of("Text|HTML|123|level=1,charset=UTF-8"));
            if (e != null) return e;
            for (int i = 0; i < 10; i++) {
                List<String> got = describeAll(Collections.singletonList(MediaRange.parse(text)));
                if (!base.equals(got)) {
                    return "期望=第 " + (i + 2) + " 次 parse 逐字段相同 → " + base + " 实际=" + got;
                }
            }

            List<String> headerBase = describeAll(MediaRange.parseHeader(header));
            for (int i = 0; i < 10; i++) {
                List<String> got = describeAll(MediaRange.parseHeader(header));
                if (!headerBase.equals(got)) {
                    return "期望=第 " + (i + 2) + " 次 parseHeader 逐条相同 → " + headerBase + " 实际=" + got;
                }
            }
            return null;
        }));

        return out;
    }

    // ------------------------------------------------------------- 辅助

    private static List<String> rank(String header, List<String> offered) {
        return RANKER.rank(header, offered);
    }

    private static Optional<String> select(String header, List<String> offered) {
        return RANKER.select(header, offered);
    }

    /** 把一条范围压成 `type|subtype|千分位q|k=v,k2=v2` 这样的可比字符串。 */
    private static String describe(MediaRange r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.type()).append('|').append(r.subtype()).append('|').append(milli(r.q())).append('|');
        sb.append(entries(r.params()));
        return sb.toString();
    }

    private static List<String> describeAll(List<MediaRange> ranges) {
        List<String> out = new ArrayList<>();
        if (ranges == null) {
            return out;
        }
        for (MediaRange r : ranges) {
            out.add(r == null ? "null" : describe(r));
        }
        return out;
    }

    private static String entries(Map<String, String> params) {
        if (params == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> en : params.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(en.getKey()).append('=').append(en.getValue());
        }
        return sb.toString();
    }

    private static long milli(double q) {
        return Math.round(q * 1000.0);
    }

    private static String expectStr(String what, String got, String expected) {
        if (!expected.equals(got)) {
            return "期望=" + what + " → " + expected + " 实际=" + got;
        }
        return null;
    }

    private static String expectQ(String what, double got, double expected) {
        if (Math.abs(got - expected) > 1e-9) {
            return "期望=" + what + " → " + expected + " 实际=" + got;
        }
        return null;
    }

    private static String expectParams(String what, Map<String, String> got, List<String> expected) {
        String exp = String.join(",", expected);
        String actual = entries(got);
        if (!exp.equals(actual)) {
            return "期望=" + what + " → {" + exp + "} 实际={" + actual + "}";
        }
        return null;
    }

    private static String expectDescs(String what, List<MediaRange> got, List<String> expected) {
        List<String> actual = describeAll(got);
        if (!expected.equals(actual)) {
            return "期望=" + what + " → " + expected + " 实际=" + actual;
        }
        return null;
    }

    private static String expectList(String what, List<String> got, List<String> expected) {
        if (got == null) {
            return "期望=" + what + " 返回非 null 的列表 实际=null";
        }
        if (!expected.equals(got)) {
            return "期望=" + what + " → " + expected + " 实际=" + got;
        }
        return null;
    }

    private static String expectOptional(String what, Optional<String> got, String expected) {
        if (got == null) {
            return "期望=" + what + " 返回非 null 的 Optional 实际=null";
        }
        if (expected == null) {
            if (got.isPresent()) {
                return "期望=" + what + " 返回 Optional.empty() 实际=" + got.get();
            }
            return null;
        }
        if (got.isEmpty()) {
            return "期望=" + what + " 选中 " + expected + " 实际=Optional.empty()";
        }
        if (!expected.equals(got.get())) {
            return "期望=" + what + " 选中 " + expected + " 实际=" + got.get();
        }
        return null;
    }

    private static String expectEmpty(String what, Optional<String> got) {
        return expectOptional(what, got, null);
    }

    private static String expectIae(Runnable body, String what) {
        try {
            body.run();
        } catch (IllegalArgumentException expected) {
            return null;
        } catch (Throwable t) {
            return "期望=" + what + " 抛 IllegalArgumentException 实际=抛了 " + t.getClass().getName();
        }
        return "期望=" + what + " 抛 IllegalArgumentException 实际=没有抛出";
    }

    private static String expectRankException(Runnable body, String what) {
        try {
            body.run();
        } catch (RankException expected) {
            return null;
        } catch (Throwable t) {
            return "期望=" + what + " 抛 RankException 实际=抛了 " + t.getClass().getName();
        }
        return "期望=" + what + " 抛 RankException 实际=没有抛出";
    }

    private Checker() {
    }
}
