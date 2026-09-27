package mediarank;

/**
 * media range 不合法时抛出的异常：`type` 或 `subtype` 缺失、`q` 不是十进制字面量、
 * `q` 超出 `[0,1]`、`q` 出现两次、`*` 与普通 `type` 配对、引号值未闭合等
 * （见 README「对外契约」第 1、2、8 条）。
 *
 * <p>它继承 {@link IllegalArgumentException}，因此「参数非法一律抛
 * IllegalArgumentException」这条口径对请求头与候选列表两侧同时成立。
 *
 * <p>类名与继承关系是对外契约的一部分，不要改动。
 */
public class RankException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public RankException(String message) {
        super(message);
    }

    public RankException(String message, Throwable cause) {
        super(message, cause);
    }
}
