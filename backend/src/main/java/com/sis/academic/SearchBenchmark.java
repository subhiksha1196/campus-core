package com.sis.academic;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Measures suggestion accuracy: simulated users type a partial query for a known target and we check
 * whether the target is suggested.
 *   first  - target is suggestion number 1
 *   top5   - target is within the first five
 *   mrr    - mean reciprocal rank (1 / position, 0 when missing)
 * Query styles: leading letters of the name, a word plus the start of the next, the complete ID,
 * and a name with one typo.
 */
public final class SearchBenchmark {
    private SearchBenchmark() {
    }

    private static final class Tally {
        int n;
        int first;
        int top5;
        double rr;
    }

    public static Map<String, Object> run(Map<String, SearchIndex> indexes, int n, long seed) {
        Random rnd = new Random(seed);
        Map<String, Tally> cats = new TreeMap<>();
        List<Double> millis = new ArrayList<>();
        int per = Math.max(1, n / 4);
        for (String kind : List.of("student", "course")) {
            SearchIndex idx = indexes.get(kind);
            if (idx == null || idx.size() == 0) {
                continue;
            }
            List<SearchIndex.Item> items = idx.allItems();
            for (int i = 0; i < per; i++) {
                SearchIndex.Item it = items.get(rnd.nextInt(items.size()));
                String[] words = it.label().split("\\s+");
                String label = it.label();

                String q1 = label.substring(0, Math.min(label.length(), 3 + rnd.nextInt(3)));
                record(cats, millis, kind + ": first letters", it.id(), timed(idx, q1, millis));

                if (words.length > 1) {
                    String q2 = words[0] + " " + words[1].substring(0, Math.min(words[1].length(), 2 + rnd.nextInt(2)));
                    record(cats, millis, kind + ": word + start of next", it.id(), timed(idx, q2, millis));
                }
                String digits = it.id().replaceAll("\\D", "");
                String q3 = (kind.equals("course") || digits.isEmpty() || rnd.nextBoolean()) ? it.id() : digits;
                record(cats, millis, kind + ": full ID", it.id(), timed(idx, q3, millis));

                List<Integer> longWords = new ArrayList<>();
                for (int w = 0; w < words.length; w++) {
                    if (words[w].length() >= 5) {
                        longWords.add(w);
                    }
                }
                if (!longWords.isEmpty()) {
                    int pick = longWords.get(rnd.nextInt(longWords.size()));
                    StringBuilder sb = new StringBuilder();
                    for (int w = 0; w < words.length; w++) {
                        if (w > 0) {
                            sb.append(' ');
                        }
                        sb.append(w == pick ? typo(words[w].toLowerCase(), rnd) : words[w]);
                    }
                    record(cats, millis, kind + ": one typo", it.id(), timed(idx, sb.toString(), millis));
                }
            }
        }
        Tally total = new Tally();
        Map<String, Object> categories = new LinkedHashMap<>();
        for (Map.Entry<String, Tally> e : cats.entrySet()) {
            Tally t = e.getValue();
            total.n += t.n;
            total.first += t.first;
            total.top5 += t.top5;
            total.rr += t.rr;
            categories.put(e.getKey(), summary(t));
        }
        Collections.sort(millis);
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("avg", millis.isEmpty() ? null : round4(millis.stream().mapToDouble(d -> d).average().orElse(0)));
        latency.put("p50", pick(millis, 50));
        latency.put("p95", pick(millis, 95));
        latency.put("p99", pick(millis, 99));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overall", total.n == 0 ? null : summary(total));
        out.put("categories", categories);
        out.put("latencyMs", latency);
        out.put("queries", total.n);
        out.put("ranAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return out;
    }

    private static List<SearchIndex.Result> timed(SearchIndex idx, String q, List<Double> millis) {
        long t0 = System.nanoTime();
        List<SearchIndex.Result> r = idx.search(q, 5);
        millis.add((System.nanoTime() - t0) / 1_000_000.0);
        return r;
    }

    private static void record(Map<String, Tally> cats, List<Double> millis, String cat, String target,
                               List<SearchIndex.Result> results) {
        int rank = 0;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).id().equals(target)) {
                rank = i + 1;
                break;
            }
        }
        Tally t = cats.computeIfAbsent(cat, k -> new Tally());
        t.n++;
        if (rank == 1) {
            t.first++;
        }
        if (rank >= 1 && rank <= 5) {
            t.top5++;
        }
        if (rank > 0) {
            t.rr += 1.0 / rank;
        }
    }

    private static Map<String, Object> summary(Tally t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", t.n);
        m.put("first", Math.round(1000.0 * t.first / t.n) / 10.0);
        m.put("top5", Math.round(1000.0 * t.top5 / t.n) / 10.0);
        m.put("mrr", Math.round(1000.0 * t.rr / t.n) / 1000.0);
        return m;
    }

    private static Double pick(List<Double> sorted, int p) {
        if (sorted.isEmpty()) {
            return null;
        }
        return round4(sorted.get(Math.min(sorted.size() - 1, (int) (p / 100.0 * sorted.size()))));
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** One realistic typing mistake: drop, swap or substitute a letter (never the first or last). */
    static String typo(String word, Random rnd) {
        if (word.length() < 5) {
            return word;
        }
        int pos = 1 + rnd.nextInt(word.length() - 2);
        switch (rnd.nextInt(3)) {
            case 0:
                return word.substring(0, pos) + word.substring(pos + 1);
            case 1:
                return word.substring(0, pos) + word.charAt(pos + 1) + word.charAt(pos) + word.substring(pos + 2);
            default:
                char c;
                do {
                    c = (char) ('a' + rnd.nextInt(26));
                } while (c == word.charAt(pos));
                return word.substring(0, pos) + c + word.substring(pos + 1);
        }
    }
}
