import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/**
 * spend-stall run 분석: 5초 구간별 probe(마켓 조회)·predict 지연과 실패, 예측 결과 상태, Market 톰캣 사용 스레드 최댓값.
 *   java StallAnalyze.java <k6.json> <threads.csv> <stall_start_ms> <stall_end_ms>
 */
public class StallAnalyze {
    static final Pattern P = Pattern.compile("\"metric\":\"([a-z_]+)\",\"type\":\"Point\",\"data\":\\{\"time\":\"([^\"]+)\",\"value\":([0-9.eE+-]+),\"tags\":\\{([^}]*)\\}");

    public static void main(String[] a) throws Exception {
        long stallStart = Long.parseLong(a[2]), stallEnd = Long.parseLong(a[3]);
        record Pt(String metric, long t, double v, String name, String status) {}
        List<Pt> pts = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(a[0]))) {
            Matcher m = P.matcher(line);
            if (!m.find()) continue;
            String tags = m.group(4);
            pts.add(new Pt(m.group(1), Instant.parse(m.group(2)).toEpochMilli(), Double.parseDouble(m.group(3)),
                    tag(tags, "name"), tag(tags, "status")));
        }
        long t0 = pts.stream().mapToLong(Pt::t).min().orElseThrow();
        int W = 5000;
        TreeMap<Integer, Map<String, List<Double>>> lat = new TreeMap<>();
        TreeMap<Integer, Map<String, int[]>> fail = new TreeMap<>();
        TreeMap<Integer, Map<String, Integer>> status = new TreeMap<>();
        for (Pt p : pts) {
            int w = (int) ((p.t() - t0) / W);
            switch (p.metric()) {
                case "http_req_duration" -> lat.computeIfAbsent(w, k -> new HashMap<>()).computeIfAbsent(p.name(), k -> new ArrayList<>()).add(p.v());
                case "http_req_failed" -> { int[] c = fail.computeIfAbsent(w, k -> new HashMap<>()).computeIfAbsent(p.name(), k -> new int[2]); c[0]++; c[1] += (int) p.v(); }
                case "predict_outcome" -> status.computeIfAbsent(w, k -> new TreeMap<>()).merge(p.status(), 1, Integer::sum);
                default -> {}
            }
        }
        // threads.csv: epoch_ms, 톰캣 사용 스레드, DB 커넥션 사용 중, DB 커넥션 대기. 지표 응답이 없으면 빈 값
        TreeMap<Integer, Integer> threads = new TreeMap<>(), active = new TreeMap<>(), pending = new TreeMap<>();
        if (Files.exists(Path.of(a[1]))) for (String line : Files.readAllLines(Path.of(a[1]))) {
            String[] f = line.split(",", -1);
            int w = (int) ((Long.parseLong(f[0]) - t0) / W);
            maxInto(threads, w, f, 1); maxInto(active, w, f, 2); maxInto(pending, w, f, 3);
        }
        System.out.printf("stall: %+.1fs ~ %+.1fs (k6 시작 기준)%n", (stallStart - t0) / 1000.0, (stallEnd - t0) / 1000.0);
        System.out.println("구간(s)  | probe n  p95ms  maxms 실패 | predict n  p95ms  실패 | tomcat busy / DB active / DB pending (max) | 예측 결과");
        for (int w : lat.keySet()) {
            boolean inStall = (long) w * W + t0 < stallEnd && (long) (w + 1) * W + t0 > stallStart;
            System.out.printf("%3d-%-3d%s | %6s | %6s | %4d / %3d / %4d | %s%n", w * 5, w * 5 + 5, inStall ? "*" : " ",
                    stat(lat.get(w).get("probe"), fail.getOrDefault(w, Map.of()).get("probe"), true),
                    stat(lat.get(w).get("predict"), fail.getOrDefault(w, Map.of()).get("predict"), false),
                    threads.getOrDefault(w, -1), active.getOrDefault(w, -1), pending.getOrDefault(w, -1),
                    status.getOrDefault(w, Map.of()));
        }
        Map<String, Integer> total = new TreeMap<>();
        status.values().forEach(m -> m.forEach((k, v) -> total.merge(k, v, Integer::sum)));
        System.out.println("예측 결과 합계: " + total);
    }

    static String stat(List<Double> v, int[] f, boolean withMax) {
        if (v == null) return "     -";
        List<Double> s = new ArrayList<>(v); Collections.sort(s);
        double p95 = s.get(Math.min(s.size() - 1, (int) Math.ceil(s.size() * 0.95) - 1));
        return withMax ? String.format("%4d %6.0f %6.0f %4d", s.size(), p95, s.get(s.size() - 1), f == null ? 0 : f[1])
                       : String.format("%4d %6.0f %4d", s.size(), p95, f == null ? 0 : f[1]);
    }

    static void maxInto(TreeMap<Integer, Integer> into, int w, String[] f, int col) {
        if (f.length > col && !f[col].isBlank()) into.merge(w, (int) Double.parseDouble(f[col]), Math::max);
    }

    static String tag(String tags, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(tags);
        return m.find() ? m.group(1) : "";
    }
}
