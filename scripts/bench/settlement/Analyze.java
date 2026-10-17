import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * k6 JSON 출력에서 정산 전/중 spend 지연을 구간별로 계산한다 (docs/architecture/ASYNC_POINT_FLOW.md §15.2).
 *
 *   java Analyze.java <k6.json> <mpDoneEpochMs|timeout> <marketDoneEpochMs|timeout>
 *
 * 모든 시각은 Docker VM 시계 기준 (k6 컨테이너, DB 폴러 공통).
 * 구간: before = 요청 시작 < t0, during = t0 ≤ 요청 시작 < MP 반영 완료.
 * 출력: run.sh가 읽는 key=value 줄.
 */
public class Analyze {

    private static final Pattern METRIC = Pattern.compile("\"metric\":\"([^\"]+)\"");
    private static final Pattern TIME = Pattern.compile("\"time\":\"([^\"]+)\"");
    private static final Pattern VALUE = Pattern.compile("\"value\":([-0-9.eE+]+)");
    private static final Pattern NAME = Pattern.compile("\"name\":\"([^\"]+)\"");

    public static void main(String[] args) throws IOException {
        Path json = Path.of(args[0]);
        Long mpDone = parseOrNull(args[1]);
        Long marketDone = parseOrNull(args[2]);

        Double t0 = null;
        Double settleApi = null;
        List<double[]> spends = new ArrayList<>();   // {startMs, durationMs}
        List<Double> failedStarts = new ArrayList<>();
        double lastSpendEnd = 0;

        for (String line : Files.readAllLines(json)) {
            if (!line.contains("\"type\":\"Point\"")) {
                continue;
            }
            String metric = group(METRIC, line);
            String name = group(NAME, line);
            double value = Double.parseDouble(group(VALUE, line));
            double endMs = OffsetDateTime.parse(group(TIME, line)).toInstant().toEpochMilli();
            switch (metric) {
                case "settle_start_ms" -> t0 = value;
                case "settle_api_ms" -> settleApi = value;
                case "http_req_duration" -> {
                    if ("spend".equals(name)) {
                        spends.add(new double[] {endMs - value, value});
                        lastSpendEnd = Math.max(lastSpendEnd, endMs);
                    }
                }
                case "http_req_failed" -> {
                    if ("spend".equals(name) && value > 0) {
                        failedStarts.add(endMs);
                    }
                }
                default -> { }
            }
        }
        if (t0 == null) {
            System.out.println("error=settle_start_ms not found");
            System.exit(1);
        }

        double during_end = mpDone != null ? mpDone : lastSpendEnd;
        List<Double> before = new ArrayList<>();
        List<Double> during = new ArrayList<>();
        for (double[] s : spends) {
            if (s[0] < t0) {
                before.add(s[1]);
            } else if (s[0] < during_end) {
                during.add(s[1]);
            }
        }
        final double start = t0;
        long failedDuring = failedStarts.stream().filter(t -> t >= start && t < during_end).count();

        System.out.printf("settle_api_ms=%s%n", settleApi == null ? "NA" : String.format("%.0f", settleApi));
        System.out.printf("mp_done_ms=%s%n", mpDone == null ? "timeout" : String.valueOf(mpDone - t0.longValue()));
        System.out.printf("market_done_ms=%s%n", marketDone == null ? "timeout" : String.valueOf(marketDone - t0.longValue()));
        print("before", before);
        print("during", during);
        System.out.printf("during_failed=%d%n", failedDuring);
        System.out.printf("load_truncated=%s%n", mpDone != null && mpDone > lastSpendEnd);
    }

    private static void print(String label, List<Double> values) {
        Collections.sort(values);
        System.out.printf("%s_count=%d%n", label, values.size());
        System.out.printf("%s_p50=%s%n", label, percentile(values, 50));
        System.out.printf("%s_p95=%s%n", label, percentile(values, 95));
        System.out.printf("%s_p99=%s%n", label, percentile(values, 99));
        System.out.printf("%s_max=%s%n", label, values.isEmpty() ? "NA" : String.format("%.1f", values.get(values.size() - 1)));
    }

    /** nearest-rank */
    private static String percentile(List<Double> sorted, int p) {
        if (sorted.isEmpty()) {
            return "NA";
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return String.format("%.1f", sorted.get(Math.max(rank, 1) - 1));
    }

    private static String group(Pattern pattern, String line) {
        Matcher m = pattern.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private static Long parseOrNull(String value) {
        return "timeout".equals(value) ? null : Long.parseLong(value.trim());
    }
}
