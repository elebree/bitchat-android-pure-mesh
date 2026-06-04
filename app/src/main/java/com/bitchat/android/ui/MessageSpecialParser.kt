package com.bitchat.android.ui

import android.util.Patterns

/**
 * Utilities for parsing special tokens in chat messages.
 */
object MessageSpecialParser {
    data class UrlMatch(val start: Int, val endExclusive: Int, val url: String)

    /**
     * Detect URLs in text. Supports http(s) schemes, www.* domains, and bare domains with TLDs.
     * Trailing punctuation like '.', ',', ')', '!' is trimmed from the match.
     */
    fun findUrls(text: String): List<UrlMatch> {
        if (text.isEmpty()) return emptyList()
        val results = mutableListOf<UrlMatch>()

        // 1) Use Android's WEB_URL for robust detection of http(s) and www.*
        val webUrl = Patterns.WEB_URL
        val matcher = webUrl.matcher(text)
        while (matcher.find()) {
            var start = matcher.start()
            var endExclusive = matcher.end()
            var token = text.substring(start, endExclusive)

            // Trim trailing punctuation
            while (token.isNotEmpty() && token.last() in setOf('.', ',', ';', ':', '!', '?', '\'', '"')) {
                endExclusive -= 1
                token = text.substring(start, endExclusive)
            }
            results.add(UrlMatch(start, endExclusive, token))
        }

        // 2) Bare-domain fallback for things WEB_URL can miss (e.g., google.com)
        val bare = Regex("(?<!@)(?<![A-Za-z0-9_-])([A-Za-z0-9-]+\\.[A-Za-z]{2,}(?:/[A-Za-z0-9@:%._+~#=/?&!$'()*,-]*)?)")
        for (m in bare.findAll(text)) {
            val start = m.range.first
            var endExclusive = m.range.last + 1
            var token = text.substring(start, endExclusive)

            // Exclude if overlaps any already-found match
            val overlapsExisting = results.any { start < it.endExclusive && endExclusive > it.start }
            if (overlapsExisting) continue

            // Skip emails
            if (token.contains('@')) continue

            // Trim trailing punctuation
            while (token.isNotEmpty() && token.last() in setOf('.', ',', ';', ':', '!', '?', '\'', '"')) {
                endExclusive -= 1
                token = text.substring(start, endExclusive)
            }

            // Require at least one dot
            if (!token.contains('.')) continue

            results.add(UrlMatch(start, endExclusive, token))
        }

        // Sort by start index
        results.sortBy { it.start }
        return results
    }
}


