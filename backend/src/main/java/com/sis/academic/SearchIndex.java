package com.sis.academic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Autocomplete over one collection (students, courses, ...).
 *
 * Every name word and ID becomes a lowercase token. A prefix trie maps each token prefix to the set of
 * item ids below it, so finding candidates costs O(length of query), independent of the number of items.
 * Multi-word queries need every word to match. A word with no prefix match is retried allowing one typo.
 * Ranking: exact ID, ID prefix, label starts with query, whole word, word prefix, typo match.
 *
 * Instances are immutable after build(), so many request threads can search one concurrently.
 */
public final class SearchIndex {
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");

    public record Item(String id, String label, String sub) {
    }

    public record Result(String id, String label, String sub, double score) {
    }

    private static final class Node {
        final Map<Character, Node> next = new HashMap<>();
        final Set<String> ids = new HashSet<>();
    }

    private final Node root = new Node();
    private final Map<String, Item> items = new HashMap<>();
    private final Map<String, List<String>> tokens = new HashMap<>();
    private final Set<String> vocab = new HashSet<>();

    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Build once, then only search. extra is more searchable text (for example a department). */
    public void add(String id, String label, String sub, String extra) {
        List<String> toks = tokenize(label + " " + id + " " + (extra == null ? "" : extra));
        String digits = id.replaceAll("\\D", "");
        if (digits.length() >= 3) {
            toks.add(digits);
        }
        items.put(id, new Item(id, label, sub));
        tokens.put(id, toks);
        for (String t : new HashSet<>(toks)) {
            Node n = root;
            for (char c : t.toCharArray()) {
                n = n.next.computeIfAbsent(c, k -> new Node());
                n.ids.add(id);
            }
            vocab.add(t);
        }
    }

    public int size() {
        return items.size();
    }

    public int vocabularySize() {
        return vocab.size();
    }

    public List<Item> allItems() {
        return new ArrayList<>(items.values());
    }

    private Set<String> prefixIds(String prefix) {
        Node n = root;
        for (char c : prefix.toCharArray()) {
            n = n.next.get(c);
            if (n == null) {
                return Set.of();
            }
        }
        return n.ids;
    }

    /** Optimal string alignment distance (insert, delete, substitute, swap), stopping early past limit. */
    static int editDistance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) {
            return limit + 1;
        }
        int[] prev2 = null;
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    cur[j] = Math.min(cur[j], prev2[j - 2] + 1);
                }
            }
            prev2 = prev;
            prev = cur;
        }
        return prev[b.length()];
    }

    /** The query word is within one typo of the start of the token (tried at three prefix lengths). */
    static boolean fuzzyPrefix(String q, String token) {
        int n = q.length();
        for (int k = n - 1; k <= n + 1; k++) {
            if (k > 0 && editDistance(q, token.substring(0, Math.min(k, token.length())), 1) <= 1) {
                return true;
            }
        }
        return false;
    }

    private static double wordScore(String q, List<String> toks, boolean allowFuzzy) {
        double best = 0;
        for (int pos = 0; pos < toks.size(); pos++) {
            String t = toks.get(pos);
            double penalty = 0.1 * Math.min(pos, 5);
            if (t.equals(q)) {
                best = Math.max(best, 4.0 - penalty);
            } else if (t.startsWith(q)) {
                best = Math.max(best, 3.0 - penalty);
            } else if (allowFuzzy && q.length() >= 3 && fuzzyPrefix(q, t)) {
                best = Math.max(best, 1.0);
            }
        }
        return best;
    }

    public List<Result> search(String query, int limit) {
        List<String> qtokens = tokenize(query == null ? "" : query);
        if (qtokens.isEmpty()) {
            return List.of();
        }
        String qNorm = query.trim().toLowerCase(Locale.ROOT);
        List<Set<String>> candidateSets = new ArrayList<>();
        boolean[] fuzzy = new boolean[qtokens.size()];
        for (int i = 0; i < qtokens.size(); i++) {
            String qt = qtokens.get(i);
            Set<String> ids = prefixIds(qt);
            if (ids.isEmpty() && qt.length() >= 3) {
                fuzzy[i] = true;
                Set<String> union = new HashSet<>();
                for (String v : vocab) {
                    if (fuzzyPrefix(qt, v)) {
                        union.addAll(prefixIds(v));
                    }
                }
                ids = union;
            }
            if (ids.isEmpty()) {
                return List.of();
            }
            candidateSets.add(ids);
        }
        Set<String> cands = new HashSet<>(candidateSets.get(0));
        for (Set<String> s : candidateSets) {
            cands.retainAll(s);
        }
        List<Result> scored = new ArrayList<>();
        for (String id : cands) {
            List<String> toks = tokens.get(id);
            double score = 0;
            for (int i = 0; i < qtokens.size(); i++) {
                double s = wordScore(qtokens.get(i), toks, fuzzy[i]);
                if (s == 0) {
                    score = 0;
                    break;
                }
                score += s;
            }
            if (score == 0) {
                continue;
            }
            Item it = items.get(id);
            String lowId = id.toLowerCase(Locale.ROOT);
            if (lowId.equals(qNorm)) {
                score += 10;
            } else if (lowId.startsWith(qNorm)) {
                score += 4;
            }
            if (it.label().toLowerCase(Locale.ROOT).startsWith(qNorm)) {
                score += 3;
            }
            scored.add(new Result(it.id(), it.label(), it.sub(), Math.round(score * 100.0) / 100.0));
        }
        scored.sort(Comparator.comparingDouble(Result::score).reversed()
                .thenComparingInt((Result r) -> r.label().length())
                .thenComparing(Result::label)
                .thenComparing(Result::id));
        return scored.size() > limit ? new ArrayList<>(scored.subList(0, limit)) : scored;
    }
}
