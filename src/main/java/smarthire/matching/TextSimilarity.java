package smarthire.matching;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Shared comparison corpus, raw term frequency and smoothed IDF; separate from structured scores. */
public final class TextSimilarity {
    public List<Double> compare(String cv, List<String> jobs) {
        var documents = new ArrayList<Map<String, Integer>>(); documents.add(tokens(cv));
        jobs.forEach(job -> documents.add(tokens(job)));
        var frequencies = new HashMap<String, Integer>();
        documents.forEach(doc -> doc.keySet().forEach(term -> frequencies.merge(term, 1, Integer::sum)));
        var vectors = new ArrayList<Map<String, Double>>();
        for (var doc : documents) {
            var vector = new HashMap<String, Double>();
            doc.forEach((term, count) -> vector.put(term, count * (Math.log((documents.size() + 1.0) / (frequencies.get(term) + 1.0)) + 1)));
            vectors.add(vector);
        }
        var result = new ArrayList<Double>();
        for (int i = 1; i < vectors.size(); i++) { result.add(cosine(vectors.getFirst(), vectors.get(i))); }
        return List.copyOf(result);
    }
    private static Map<String, Integer> tokens(String text) {
        var result = new HashMap<String, Integer>();
        var matcher = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}+#]*").matcher(java.text.Normalizer.normalize(text == null ? "" : text, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        while (matcher.find()) { result.merge(matcher.group(), 1, Integer::sum); }
        return result;
    }
    private static double cosine(Map<String, Double> first, Map<String, Double> second) {
        double dot = first.entrySet().stream().mapToDouble(e -> e.getValue() * second.getOrDefault(e.getKey(), 0.0)).sum();
        double norm = Math.sqrt(first.values().stream().mapToDouble(v -> v * v).sum()) * Math.sqrt(second.values().stream().mapToDouble(v -> v * v).sum());
        return norm == 0 ? 0 : Math.min(1, Math.max(0, dot / norm));
    }
}
