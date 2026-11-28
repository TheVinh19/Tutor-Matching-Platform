package vn.giasu.ai.assistant;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retrieval-mode answering while the model provider is not chosen (P17): Vietnamese text without diacritics, words and word pairs,
 * weighted by how rare they are in the knowledge base. A question matches an entry only above {@link #THRESHOLD}; otherwise the
 * assistant says it does not know instead of guessing.
 * shortcut: lexical matching only, upgrade to embeddings or the chosen LLM with these entries as grounding.
 */
final class Retriever {
    static final double THRESHOLD = 0.5;

    enum SourceType { FAQ, POLICY }

    record Entry(SourceType type, String ref, String question, String answer, String tags) {}

    record Hit(Entry entry, double score) {}

    private static final Set<String> STOP = Set.of(
            "la", "thi", "co", "khong", "duoc", "cua", "toi", "minh", "ban", "o", "dau", "va", "cho", "de", "khi", "bao", "lam", "sao", "gi",
            "mot", "nhung", "cac", "voi", "trong", "neu", "hay", "nhu", "ra", "nao", "the", "nay", "ay", "a", "oi", "nhe", "em", "anh", "chi",
            "can", "muon", "phai", "se", "da", "dang", "vay", "giup", "xin", "hoi", "cach", "nhieu", "may", "len", "di", "con", "tai", "vi");

    private record Doc(Entry entry, Set<String> title, Set<String> tags, Set<String> body) {}

    private final List<Doc> docs = new ArrayList<>();
    private final Map<String, Double> idf = new HashMap<>();

    Retriever(List<Entry> entries) {
        Map<String, Integer> df = new HashMap<>();
        for (Entry e : entries) {
            Set<String> tags = new LinkedHashSet<>();
            for (String tag : e.tags() == null ? new String[0] : e.tags().split(",")) tags.addAll(terms(tag));
            Doc d = new Doc(e, terms(e.question()), tags, terms(e.answer()));
            docs.add(d);
            Set<String> all = new LinkedHashSet<>(d.title());
            all.addAll(d.tags());
            all.addAll(d.body());
            all.forEach(t -> df.merge(t, 1, Integer::sum));
        }
        int n = Math.max(1, entries.size());
        df.forEach((t, count) -> idf.put(t, Math.log(1 + (double) n / count)));
    }

    /** Best entries first; score is the share of the question's word weight found in the entry, matched word pairs adding a bonus (can pass 1). */
    List<Hit> search(String question, int limit) {
        Set<String> query = terms(question);
        double total = 0;
        for (String t : query) if (t.indexOf(' ') < 0) total += weight(t);
        if (total == 0) return List.of();
        List<Hit> hits = new ArrayList<>();
        for (Doc d : docs) {
            double got = 0;
            for (String t : query) {
                double field = d.title().contains(t) || d.tags().contains(t) ? 1.0 : d.body().contains(t) ? 0.5 : 0;
                got += field * idf.getOrDefault(t, 0.0) * (t.indexOf(' ') > 0 ? 2 : 1);
            }
            if (got > 0) hits.add(new Hit(d.entry(), got / total));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.subList(0, Math.min(limit, hits.size()));
    }

    /** Unknown words weigh like the rarest known word, so an off-topic question cannot match on a few common words. */
    private double weight(String term) {
        double max = idf.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        return idf.getOrDefault(term, max);
    }

    /** Content words (stop words removed) plus adjacent word pairs, e.g. "doi lich", "khung gio". */
    static Set<String> terms(String text) {
        String[] words = normalize(text).split(" ");
        Set<String> out = new LinkedHashSet<>();
        String prev = null;
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (!STOP.contains(w)) out.add(w);
            if (prev != null && !(STOP.contains(prev) && STOP.contains(w))) out.add(prev + " " + w);
            prev = w;
        }
        return out;
    }

    static String normalize(String text) {
        String s = Normalizer.normalize(text.toLowerCase().replace('đ', 'd'), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.replaceAll("[^a-z0-9]+", " ").strip();
    }
}
