package com.donotmiss.backend.retrieval;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Bm25QueryCompactor {
    private static final int MAX_TERMS = 24;
    private static final int MAX_QUERY_LENGTH = 240;
    private static final int FALLBACK_MAX_TERMS = 8;
    private static final int FALLBACK_MAX_QUERY_LENGTH = 96;
    private static final int MAX_CJK_CHUNK_LENGTH = 12;
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "[\\p{IsHan}]+|[a-zA-Z][a-zA-Z0-9+#._-]*|\\d+"
    );
    private static final Set<String> GENERIC_TERMS = Set.of(
            "zero", "basic", "intermediate", "advanced", "unknown",
            "skill", "money", "both",
            "我想", "想要", "希望", "活动", "机会", "推荐", "适合", "参与"
    );

    private Bm25QueryCompactor() {
    }

    static String compact(String query) {
        return compact(query, MAX_TERMS, MAX_QUERY_LENGTH);
    }

    static String fallback(String query) {
        return compact(query, FALLBACK_MAX_TERMS, FALLBACK_MAX_QUERY_LENGTH);
    }

    private static String compact(String query, int maxTerms, int maxLength) {
        if (query == null || query.isBlank()) {
            return "";
        }

        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find() && terms.size() < maxTerms) {
            String token = matcher.group().trim();
            if (token.isBlank() || GENERIC_TERMS.contains(token)) {
                continue;
            }
            if (containsCjk(token) && token.length() > MAX_CJK_CHUNK_LENGTH) {
                addCjkChunks(terms, token, maxTerms);
            } else {
                terms.add(token);
            }
        }

        StringBuilder compacted = new StringBuilder();
        for (String term : terms) {
            int additionalLength = term.length() + (compacted.isEmpty() ? 0 : 1);
            if (compacted.length() + additionalLength > maxLength) {
                break;
            }
            if (!compacted.isEmpty()) {
                compacted.append(' ');
            }
            compacted.append(term);
        }
        return compacted.toString();
    }

    private static void addCjkChunks(LinkedHashSet<String> terms, String token, int maxTerms) {
        for (int start = 0; start < token.length() && terms.size() < maxTerms; start += MAX_CJK_CHUNK_LENGTH) {
            int end = Math.min(start + MAX_CJK_CHUNK_LENGTH, token.length());
            String chunk = token.substring(start, end);
            if (chunk.length() >= 2 && !GENERIC_TERMS.contains(chunk)) {
                terms.add(chunk);
            }
        }
    }

    private static boolean containsCjk(String value) {
        return value.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
