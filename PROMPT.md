协商结果必须可复现：同样的请求头与候选列表，任何进程里都得选出同一个。
mediarank 是 Java 17 的内容协商库，只用 JDK 标准库，构建与自检走 `scripts/*.sh`
（`scripts/check.sh` 是固定验收程序，别改）。

`src/main/java` 下三个类现在都是空壳，方法体一律抛 `UnsupportedOperationException`。

任务：按 README「对外契约」的 11 条把它实现出来，让固定件全过。

验收：
- bash scripts/build.sh 退出码 0；
- bash scripts/check.sh 退出码 0，11 个场景全过（parse 3 + rank 3 + select 2 + robust 2 + determinism 1）。

约束：
1. 不改 `check/`；已公开的类与方法签名不变（可新增）。
2. 不引入第三方依赖，不用 Maven / Gradle，只用 JDK 标准库。
3. 结果必须与 `HashMap` 迭代顺序无关，大小写折叠必须显式用 `Locale.ROOT`。
4. 参数非法一律抛 `IllegalArgumentException`，不许 `NullPointerException`。
