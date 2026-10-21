package com.donotmiss.backend.capabilityv2;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * In-memory read model for the curated function-bucket seed library.
 * Stage 03 deliberately keeps retrieval deterministic and local; Stage 09 can replace
 * the lookup implementation with vector retrieval without changing the seed contract.
 */
@Component
public class FunctionBucketSeedCatalog {
    private static final String RESOURCE_PATH = "capability-v2/function-buckets.seed.txt";
    private static final Pattern DOMAIN_HEADING = Pattern.compile("^[一二三四五六七八九十百]+、.+");
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[、,，;；/\\s]+");

    private volatile List<FunctionBucketSeed> seeds = List.of();

    @PostConstruct
    void initialize() {
        reload();
    }

    public void reload() {
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        try (InputStream stream = resource.getInputStream()) {
            seeds = List.copyOf(parse(stream));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load function bucket seed library: " + RESOURCE_PATH, exception);
        }
    }

    public List<FunctionBucketSeed> all() {
        return seeds;
    }

    public List<FunctionBucketSeed> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        String normalizedQuery = normalize(query);
        Set<String> queryTerms = splitTerms(query);
        return seeds.stream()
                .map(seed -> new ScoredSeed(seed, lexicalScore(seed, normalizedQuery, queryTerms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredSeed::score).reversed()
                        .thenComparing(scored -> scored.seed().displayName()))
                .limit(limit)
                .map(ScoredSeed::seed)
                .toList();
    }

    static List<FunctionBucketSeed> parse(InputStream stream) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
        }

        List<FunctionBucketSeed> parsed = new ArrayList<>();
        String currentDomain = "通用能力";
        String currentGroup = "未分组";
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("吸收：")) {
                continue;
            }
            if (DOMAIN_HEADING.matcher(line).matches()) {
                currentDomain = line.substring(line.indexOf('、') + 1).trim();
                currentGroup = "未分组";
                continue;
            }

            String next = index + 1 < lines.size() ? lines.get(index + 1) : "";
            if (next.startsWith("吸收：")) {
                String description = next.substring("吸收：".length()).trim();
                LinkedHashSet<String> aliases = new LinkedHashSet<>();
                aliases.add(line);
                aliases.addAll(splitTerms(description));
                parsed.add(new FunctionBucketSeed(
                        currentDomain,
                        currentGroup,
                        line,
                        description,
                        List.copyOf(aliases)
                ));
                index++;
                continue;
            }
            currentGroup = line;
        }
        return parsed;
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isLetterOrDigit(current)) {
                normalized.append(Character.toLowerCase(current));
            }
        }
        return normalized.toString();
    }

    private static Set<String> splitTerms(String value) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return terms;
        }
        for (String raw : SPLIT_PATTERN.split(value)) {
            String normalized = normalize(raw);
            if (!normalized.isBlank()) {
                terms.add(normalized);
            }
        }
        String whole = normalize(value);
        if (!whole.isBlank()) {
            terms.add(whole);
        }
        return terms;
    }

    private static int lexicalScore(FunctionBucketSeed seed, String normalizedQuery, Collection<String> queryTerms) {
        String normalizedName = normalize(seed.displayName());
        String normalizedDescription = normalize(seed.description());
        String normalizedAliases = normalize(String.join(" ", seed.aliases()));
        int score = 0;
        if (normalizedName.equals(normalizedQuery)) {
            score += 120;
        } else if (normalizedName.contains(normalizedQuery) || normalizedQuery.contains(normalizedName)) {
            score += 70;
        }
        for (String term : queryTerms) {
            if (term.length() < 2) {
                continue;
            }
            if (normalizedName.contains(term)) {
                score += 28;
            }
            if (normalizedAliases.contains(term)) {
                score += 12;
            }
            if (normalizedDescription.contains(term)) {
                score += 8;
            }
        }
        return score;
    }

    public record FunctionBucketSeed(
            String domainName,
            String groupName,
            String displayName,
            String description,
            List<String> aliases
    ) {
        public String normalizedName() {
            return normalize(displayName);
        }
    }

    private record ScoredSeed(FunctionBucketSeed seed, int score) {
    }
}
