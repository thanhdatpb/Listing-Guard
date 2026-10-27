package com.thanhdatpb.listingguard.rag.embedding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic bag-of-words vectors via the hashing trick. It has no semantics: two texts are close
 * only when they share words. That is exactly what tests need (no Ollama, stable results). Not for
 * production use.
 */
public final class HashingEmbeddingProvider implements EmbeddingProvider {

    private final int dimensions;

    public HashingEmbeddingProvider(int dimensions) {
        if (dimensions < 1) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        this.dimensions = dimensions;
    }

    @Override
    public String modelId() {
        return "hashing:" + dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts, Kind kind) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embedOne(text));
        }
        return vectors;
    }

    private float[] embedOne(String text) {
        float[] vector = new float[dimensions];
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (token.isEmpty()) {
                continue;
            }
            int hash = fnv1a(token);
            int index = Math.floorMod(hash, dimensions);
            vector[index] += (hash & 0x40000000) == 0 ? 1f : -1f;
        }
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        if (norm == 0) {
            vector[0] = 1f;
            return vector;
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
        return vector;
    }

    private static int fnv1a(String token) {
        int hash = 0x811C9DC5;
        for (int i = 0; i < token.length(); i++) {
            hash ^= token.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }
}
