package com.tessera.fleet.search;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight fuzzy string matching for the in-memory customer-site search
 * (FR-6.3). A trigram (Jaccard) similarity, boosted for prefix/substring hits —
 * comparable to PostgreSQL {@code pg_trgm}, which is the scale path (SRS §3.2).
 */
public final class TextSimilarity {

    private TextSimilarity() { }

    /** 0.0–1.0; higher is a better match of {@code query} against {@code target}. */
    public static double score(String query, String target) {
        if (query == null || target == null) {
            return 0;
        }
        String q = normalize(query);
        String t = normalize(target);
        if (q.isEmpty() || t.isEmpty()) {
            return 0;
        }
        if (t.equals(q)) {
            return 1.0;
        }

        double trigram = jaccardTrigrams(q, t);
        double bonus = 0;
        if (t.startsWith(q)) {
            bonus = 0.45;
        } else if (t.contains(q)) {
            bonus = 0.30;
        } else if (containsAllTokens(q, t)) {
            bonus = 0.20;
        }
        return Math.min(1.0, trigram * 0.6 + bonus);
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("'", "")               // fold possessives: "mary's" -> "marys"
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static Set<String> trigrams(String s) {
        String padded = "  " + s + " ";
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 3 <= padded.length(); i++) {
            out.add(padded.substring(i, i + 3));
        }
        return out;
    }

    private static double jaccardTrigrams(String a, String b) {
        Set<String> ta = trigrams(a);
        Set<String> tb = trigrams(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        int inter = 0;
        for (String g : ta) {
            if (tb.contains(g)) {
                inter++;
            }
        }
        int union = ta.size() + tb.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    private static boolean containsAllTokens(String q, String t) {
        for (String tok : q.split(" ")) {
            if (!tok.isBlank() && !t.contains(tok)) {
                return false;
            }
        }
        return true;
    }
}
