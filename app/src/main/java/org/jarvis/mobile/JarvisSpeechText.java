package org.jarvis.mobile;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JarvisSpeechText {
    private static final Pattern LINK = Pattern.compile("!??\\[([^]]+)]\\([^)]*\\)");
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s*");
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*(?:[-+*]|\\d+[.)])\\s+");
    private static final Pattern QUOTE_MARKER = Pattern.compile("(?m)^\\s*>\\s?");
    private static final Pattern MARKDOWN_RULE = Pattern.compile("(?m)^\\s*[-*_]{3,}\\s*$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private JarvisSpeechText() {
    }

    public static String clean(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        Matcher links = LINK.matcher(text);
        String result = links.replaceAll("$1");
        result = HEADING.matcher(result).replaceAll("");
        result = LIST_MARKER.matcher(result).replaceAll("");
        result = QUOTE_MARKER.matcher(result).replaceAll("");
        result = MARKDOWN_RULE.matcher(result).replaceAll("");
        result = result.replaceAll("```[\\w-]*", "");
        result = result.replaceAll("[*_~`]+", "");
        result = result.replaceAll("\\\\([\\\\`*_{}\\[\\]()#+.!>-])", "$1");
        return WHITESPACE.matcher(result).replaceAll(" ").trim();
    }
}