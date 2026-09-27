# mediarank

一个**内容协商排序库**（Java 17，只用 JDK 标准库，无第三方依赖）。
它把一条 `Accept` 风格的请求头与一批候选媒体类型排成一条**确定**的优先级序列，并选出最优的那个。

```java
MediaRanker ranker = new MediaRanker();

ranker.rank("text/*;q=0.8, */*;q=0.9, text/html;q=0.8",
        List.of("text/html", "application/json", "text/plain", "image/png", "application/json"));
// ["application/json", "image/png", "application/json", "text/html", "text/plain"]

ranker.select("text/*;q=0.5, application/json;q=0.9",
        List.of("text/plain", "application/json", "text/html"));
// Optional["application/json"]

MediaRange r = MediaRange.parse("TEXT/HTML;Level=1;Charset=UTF-8;q=0.8");
r.type();     // "TEXT"（原文）
r.subtype();  // "HTML"（原文）
r.q();        // 0.8
r.params();   // {level=1, charset=UTF-8}（有序：键小写，值原文）
```

## 目录

```
src/main/java/mediarank/MediaRange.java    一条 media range（当前为空壳）
src/main/java/mediarank/MediaRanker.java   排序与选择（当前为空壳）
src/main/java/mediarank/RankException.java 非法范围时抛出的异常
scripts/build.sh                           编译 src/main/java 到 out/
scripts/check.sh                           固定验收入口（勿改）
check/Checker.java                         固定验收程序（勿改）
```

## 语言版本前提

- Java 17（`javac` / `java`）。
- **只用 JDK 标准库**：不引入 Maven / Gradle，不引入任何第三方依赖。
- 构建产物放在 `out/` 与 `out-check/`，已在 `.gitignore` 中忽略。

## 怎么跑

```bash
bash scripts/build.sh                                  # 编译 src/main/java 到 out/
bash scripts/check.sh                                  # 固定验收，支持 -list 与 --only <组名>
bash scripts/check.sh -list                            # 列出全部 11 个验收场景
bash scripts/check.sh --only rank                       # 只跑一组
```

起点状态：`src/main/java` 下的方法都是空壳，调用即抛 `UnsupportedOperationException`；
`bash scripts/build.sh` 能编译通过（**没有语法错**）；`bash scripts/check.sh`
打印 `结果：通过 0/11` 并以退出码 1 结束。

> `check/` 下的是**固定验收程序，勿改**。公开的**类型名、方法名、签名与异常类型**
> 已经是最终形态（可以新增内部方法与私有成员），不要改动下面 `API` 一节列出的这些。

## 对外契约

下面 11 条是 `mediarank` 的**对外契约**，它们是本题验收点的唯一出处。
**它们是契约，不是「当前行为」的转述**；实现方式不限，但必须让这 11 条同时成立。

1. **`MediaRange` 的形状**。`MediaRange` 表示一条 media range（形如 `type/subtype;name=value;q=0.5`），
   有四个访问器：`type()`、`subtype()`、`q()`（`double`）、`params()`（**有序** `Map<String,String>`）。
   `type` / `subtype` / 参数**名**大小写不敏感，参数**值**大小写敏感。
   落地口径：`type()` / `subtype()` 返回**原文**（不折叠大小写）；`params()` 的**键**是折叠为小写的
   参数名（`Locale.ROOT`），**值**保留原文；`params()` **不含** `q`；`params()` 按参数在串里
   出现的先后排列（不是按键排序），返回的映射不可修改。
   `type` 与 `subtype` 之间用 `/` 分隔：`subtype` 位置只允许普通 token 或 `*`；`type` 位置只允许
   普通 token 或 `*`，且 `type` 为 `*` 时 `subtype` 也必须是 `*`（`*/html` 不合法）。
   参数之间用 `;` 分隔，media range 之间用 `,` 分隔；每个条目的首尾空白先剥掉，之后 `;` 与 `=`
   两侧允许可选的空格或制表符（OWS），而 `type` / `subtype` / 参数名 / 未加引号的参数值**内部**
   不得含空白。引号值 `name="..."` 里的 `,` 与 `;` **不算分隔符**；引号未闭合 → 该条 malformed（见第 8 条）。
2. **`q` 与三位小数定点化**。`q` 缺省为 `1.0`；`q` 只允许出现一次，取值必须在 `[0,1]`。
   `q` 的值是非负十进制字面量（`1`、`0.5`、`0.123`；不接受符号与指数记法），
   名字大小写不敏感（`q` 与 `Q` 等价）。**最多 3 位小数**：多于 3 位时**向下截断**到 3 位
   （`q=0.1234` → `0.123`、`q=0.9999` → `0.999`、`q=1.0000` → `1.0`），截断后的值参与一切比较
   —— 实现上请把它定点化到千分位再比较，直接用二进制 `double` 会在 `0.1` / `0.2` 这类输入上
   给出不可复现的排序。截断可能把极小的 `q` 变成 `0`（`q=0.0009` → `0.0`，即拒绝）。
   非法（`q=abc`、`q=1.5`、`q=-0.1`、`q` 重复）→ 只忽略该条范围（见第 8 条）。
3. **`q = 0` 是明确拒绝**。只要一个候选被**任意**一条 `q = 0` 的范围命中，它就被排除 ——
   这条**压在第 5 条之上**：即使另有一条特异性更高、`q > 0` 的范围也命中了它，该候选仍被排除。
   被排除的候选在 `rank` 结果里排在最后。
4. **通配与特异性**。`*/*` 匹配一切候选；`type/*` 只匹配 `type` 相同的候选（大小写不敏感）；
   `type/subtype` 还要求 `subtype` 也相同（大小写不敏感）。
   特异性：`type/subtype` = **2**、`type/*` = **1**、`*/*` = **0**。
5. **多条命中取特异性最高的那条的 `q`**。一个候选被多条范围命中时，取**特异性最高**那条的 `q`
   —— 不是取最大的 `q`，也不是取最先出现的那条；特异性并列时取**先出现**的那条。
   （被任意 `q = 0` 命中时按第 3 条走，不适用这一条。）
   `rank` 里用于排序的**特异性**，是该候选全部命中范围里的**最大特异性**。
6. **`rank(header, offered)`**。返回**全部**候选的排序结果（`List<String>`，元素就是 `offered`
   里的原串）：先 `q` **降序**、再特异性**降序**、再**提供列表里的原始下标**升序
   （完全并列时稳定，不下沉也不上浮）。被拒的候选（`q = 0`，**含没有任何范围命中的候选**）
   排在最后；被拒者之间同样按「特异性降序、原始下标升序」排列。
   `offered` 里的重复项各自独立参与排序与结果，结果长度恒等于 `offered` 的长度。
7. **`select(header, offered)`**。返回 `rank(header, offered)` 里**第一个 `q > 0`** 的候选
   （即排序结果里的第一个非拒绝项）；没有这样的候选时返回 `Optional.empty()`。
8. **malformed 范围与非法入参**。malformed 的范围（`type` 或 `subtype` 缺失、`q = abc`、`q = 1.5`、
   `q` 重复、`*` 与普通 `type` 配对、参数值含未闭合引号）→ **只忽略该条**，
   同一头里的其余范围照常参与排序与选择；`header == null` → `IllegalArgumentException`。
   单条解析入口 `MediaRange.parse(String)` 遇到 malformed 抛 `RankException`。
9. **纯函数**。`parse` / `rank` / `select` 不得读系统属性或环境变量；大小写折叠一律 `Locale.ROOT`
   （结果与 JVM 默认 Locale 无关）；不得依赖 `HashMap` 的迭代顺序。
10. **边界**。`offered` 为空 → `rank` 返回空列表、`select` 返回 `Optional.empty()`；
    `offered` 含重复项 → 各自独立参与排序与结果；
    `header` 为空串（或解析后没有任何有效范围）→ 视同 `*/*;q=1.0`；
    `offered` 的元素必须是 `type/subtype` 形式（不得带参数），否则抛 `RankException`；
    `offered` 为 `null`、或其元素为 `null` / 空串、或其元素不合法 → `IllegalArgumentException`
    （`RankException` 继承自它，所以合法）。
11. **确定性**。同一输入任意多次调用，结果逐字段相同：`rank` 的列表顺序、`select` 的选择、
    `parse` 出来的 `type` / `subtype` / `q` / `params` 全都确定，跨进程也相同。

## API

```
mediarank.MediaRange
  static MediaRange parse(String text)
                                   （malformed 抛 RankException；null 抛 IllegalArgumentException）
  static List<MediaRange> parseHeader(String header)
                                   （按 `,` 切成多条，malformed 的条目只忽略自己、其余照常返回；
                                     header 为 null 抛 IllegalArgumentException）
  #type()                          -> String              （原文，不折叠大小写）
  #subtype()                       -> String              （原文，不折叠大小写）
  #q()                             -> double              （已在千分位定点化）
  #params()                        -> Map<String,String>  （有序、不可修改；键小写、值原文；不含 q）

mediarank.MediaRanker
  MediaRanker()
  #rank(String header, List<String> offered)   -> List<String>
                                   （q 降序 → 特异性降序 → 原始下标升序；被拒者垫底）
  #select(String header, List<String> offered) -> Optional<String>
                                   （第一个 q > 0 的候选；没有则 Optional.empty()）

mediarank.RankException extends IllegalArgumentException
```

## 验收

`check/Checker.java` 是固定验收程序，**不要修改**。它按 5 组共 11 个场景检查上面的契约。
**每个场景都在独立 JVM 子进程里跑，带 30 秒看门狗**，超时判不通过；
一个场景失败不会遮蔽其余场景（失败不早退）。

| 组 | 场景 | 对应契约 |
| --- | --- | --- |
| `parse` | `case-and-params` | 1、2 |
| `parse` | `q-three-tiers` | 2 |
| `parse` | `malformed-only-self` | 8、1 |
| `rank` | `q-desc-spec-index` | 4、5、6 |
| `rank` | `most-specific-q` | 5、4 |
| `rank` | `rejected-last` | 3、6 |
| `select` | `highest` | 7、6 |
| `select` | `all-rejected-empty` | 7、10 |
| `robust` | `zero-beats-wildcard` | 3、5 |
| `robust` | `empty-offered-and-empty-header` | 10、8 |
| `determinism` | `repeat-identical` | 11、9 |

共 11 个场景。判据全部确定性：没有墙钟、没有随机源、没有性能门槛，
顺序相关的断言只依赖（`q` → 特异性 → 原始下标）这条写死的规则。
