package mediarank;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 内容协商排序：把一条 `Accept` 风格请求头与一批候选媒体类型排成确定的优先级序列。
 */
public final class MediaRanker {

    private static final Pattern TOKEN =
            Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]+");

    /** 单个候选的评分结果：原串、是否被 q=0/未命中拒绝、定点 q、最大特异性、原始下标。 */
    private static final class Score {
        final String raw;
        final boolean rejected;
        final int qMilli;
        final int maxSpecificity;
        final int index;

        Score(String raw, boolean rejected, int qMilli, int maxSpecificity, int index) {
            this.raw = raw;
            this.rejected = rejected;
            this.qMilli = qMilli;
            this.maxSpecificity = maxSpecificity;
            this.index = index;
        }
    }

    public List<String> rank(String header, List<String> offered) {
        List<Score> scores = score(header, offered);
        scores.sort(MediaRanker::compare);
        List<String> result = new ArrayList<>(scores.size());
        for (Score score : scores) {
            result.add(score.raw);
        }
        return result;
    }

    public Optional<String> select(String header, List<String> offered) {
        Score best = null;
        for (Score score : score(header, offered)) {
            if (!score.rejected && (best == null || compare(score, best) < 0)) {
                best = score;
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.raw);
    }

    /** 接受者在前；同类内 q 降序 → 最大特异性降序 → 原始下标升序。 */
    private static int compare(Score x, Score y) {
        if (x.rejected != y.rejected) {
            return x.rejected ? 1 : -1;
        }
        int c = Integer.compare(y.qMilli, x.qMilli);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(y.maxSpecificity, x.maxSpecificity);
        if (c != 0) {
            return c;
        }
        return Integer.compare(x.index, y.index);
    }

    private List<Score> score(String header, List<String> offered) {
        if (header == null) {
            throw new IllegalArgumentException("header 不能为 null");
        }
        if (offered == null) {
            throw new IllegalArgumentException("offered 不能为 null");
        }

        List<MediaRange> ranges = MediaRange.parseHeader(header);
        if (ranges.isEmpty()) {
            ranges = List.of(MediaRange.parse("*/*"));
        }

        List<Score> scores = new ArrayList<>(offered.size());
        for (int i = 0; i < offered.size(); i++) {
            String raw = offered.get(i);
            if (raw == null || raw.isEmpty()) {
                throw new IllegalArgumentException("offered 的元素不能为 null 或空串");
            }
            String[] parts = splitCandidate(raw);

            int bestQ = 0;
            int bestSpec = -1;
            int maxSpec = 0;
            boolean rejected = false;
            for (MediaRange range : ranges) {
                if (!range.matches(parts[0], parts[1])) {
                    continue;
                }
                int spec = range.specificity();
                if (spec > maxSpec) {
                    maxSpec = spec;
                }
                if (range.qMilli() == 0) {
                    rejected = true;
                } else if (spec > bestSpec) {
                    bestSpec = spec;
                    bestQ = range.qMilli();
                }
            }

            boolean noPositiveMatch = bestSpec < 0;
            scores.add(new Score(raw, rejected || noPositiveMatch,
                    noPositiveMatch ? 0 : bestQ, maxSpec, i));
        }
        return scores;
    }

    /**
     * 校验并拆分候选：必须是 {@code type/subtype}（不得带参数），
     * 且 type、subtype 都不允许是 {@code *}。
     */
    private static String[] splitCandidate(String raw) {
        int slash = raw.indexOf('/');
        if (slash <= 0 || raw.indexOf('/', slash + 1) >= 0 || raw.indexOf(';') >= 0) {
            throw new RankException("候选必须是 type/subtype 形式且不带参数：" + raw);
        }
        String type = raw.substring(0, slash);
        String subtype = raw.substring(slash + 1);
        if ("*".equals(type) || "*".equals(subtype)
                || !TOKEN.matcher(type).matches() || !TOKEN.matcher(subtype).matches()) {
            throw new RankException("候选类型不合法：" + raw);
        }
        return new String[] {type, subtype};
    }
}
