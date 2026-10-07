package com.resonolabs.feature.t3;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown-ish cleanup for agent messages on a 480px screen. Not a renderer: it keeps the
 * reading structure (headings, bullets, code, quotes, paragraphs) and strips the syntax noise.
 * Code fences become monospace blocks; tables become "cell · cell" lines.
 */
final class T3Markdown {
    enum Kind { TEXT, HEADING, BULLET, CODE, QUOTE, GAP }

    static final class Block {
        final Kind kind;
        final String text;
        /** Nesting level for bullets (0 = top). */
        final int level;

        Block(Kind kind, String text, int level) {
            this.kind = kind;
            this.text = text;
            this.level = level;
        }

        @Override public String toString() {
            return kind + (level > 0 ? "/" + level : "") + ":" + text;
        }
    }

    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(```|~~~)");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+•]\\s+(.*)$");
    private static final Pattern TASK = Pattern.compile("^\\[([ xX])]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>\\s?(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern TABLE_SEPARATOR =
            Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)(\\s+\"[^\"]*\")?\\)");
    private static final Pattern AUTOLINK = Pattern.compile("<(https?://[^>\\s]+)>");
    private static final Pattern BOLD_STARS = Pattern.compile("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*");
    private static final Pattern BOLD_UNDERSCORES = Pattern.compile("(?<![\\w])__(?=\\S)(.+?)(?<=\\S)__(?![\\w])");
    private static final Pattern ITALIC_STAR = Pattern.compile("(?<![\\w*])\\*(?=[^\\s*])(.+?)(?<=[^\\s*])\\*(?![\\w*])");
    private static final Pattern ITALIC_UNDERSCORE = Pattern.compile("(?<![\\w])_(?=[^\\s_])(.+?)(?<=[^\\s_])_(?![\\w])");
    private static final Pattern STRIKE = Pattern.compile("~~(?=\\S)(.+?)(?<=\\S)~~");
    private static final Pattern CODE_SPAN = Pattern.compile("`+([^`]+?)`+");
    private static final Pattern BREAK_TAG = Pattern.compile("(?i)<br\\s*/?>");

    private T3Markdown() {}

    static List<Block> parse(String raw) {
        List<Block> blocks = new ArrayList<>();
        if (raw == null) return blocks;
        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder code = null;
        for (String source : lines) {
            String line = stripTrailing(source);
            if (FENCE.matcher(line).find()) {
                if (code == null) {
                    code = new StringBuilder();
                } else {
                    addCode(blocks, code);
                    code = null;
                }
                continue;
            }
            if (code != null) {
                if (code.length() > 0) code.append('\n');
                code.append(line.replace("\t", "  "));
                continue;
            }
            if (line.isBlank()) { gap(blocks); continue; }
            if (RULE.matcher(line).matches()) { gap(blocks); continue; }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                String text = inline(heading.group(1));
                if (!text.isEmpty()) blocks.add(new Block(Kind.HEADING, text, 0));
                continue;
            }
            if (isTableRow(line)) {
                if (TABLE_SEPARATOR.matcher(line).matches()) continue;
                String row = tableRow(line);
                if (!row.isEmpty()) blocks.add(new Block(Kind.TEXT, row, 0));
                continue;
            }
            Matcher bullet = BULLET.matcher(line);
            if (bullet.matches() && !RULE.matcher(line).matches()) {
                int indent = bullet.group(1).replace("\t", "    ").length();
                String text = bullet.group(2);
                Matcher task = TASK.matcher(text);
                if (task.matches()) text = ("x".equalsIgnoreCase(task.group(1)) ? "✓ " : "☐ ") + task.group(2);
                blocks.add(new Block(Kind.BULLET, inline(text), Math.min(3, indent / 2)));
                continue;
            }
            Matcher quote = QUOTE.matcher(line);
            if (quote.matches()) {
                String text = inline(quote.group(1));
                if (!text.isEmpty()) blocks.add(new Block(Kind.QUOTE, text, 0));
                continue;
            }
            String text = inline(line.trim());
            if (!text.isEmpty()) blocks.add(new Block(Kind.TEXT, text, 0));
        }
        if (code != null) addCode(blocks, code); // Unclosed fence while streaming.
        while (!blocks.isEmpty() && blocks.get(blocks.size() - 1).kind == Kind.GAP) {
            blocks.remove(blocks.size() - 1);
        }
        return blocks;
    }

    /** One-line plain preview (for list rows and voice notes). */
    static String plain(String raw, int maxChars) {
        StringBuilder out = new StringBuilder();
        for (Block block : parse(raw)) {
            if (block.kind == Kind.GAP) continue;
            if (out.length() > 0) out.append(' ');
            out.append(block.kind == Kind.CODE ? block.text.replace('\n', ' ').trim() : block.text);
            if (out.length() >= maxChars) break;
        }
        String value = out.toString().replaceAll("\\s+", " ").trim();
        return value.length() <= maxChars ? value : value.substring(0, Math.max(0, maxChars - 1)).trim() + "…";
    }

    /** Inline syntax removal: links keep their label, emphasis and code spans keep their text. */
    static String inline(String value) {
        if (value == null || value.isEmpty()) return "";
        String text = BREAK_TAG.matcher(value).replaceAll(" ");
        text = images(text);
        text = LINK.matcher(text).replaceAll("$1");
        text = AUTOLINK.matcher(text).replaceAll("$1");
        text = CODE_SPAN.matcher(text).replaceAll("$1");
        text = BOLD_STARS.matcher(text).replaceAll("$1");
        text = BOLD_UNDERSCORES.matcher(text).replaceAll("$1");
        text = ITALIC_STAR.matcher(text).replaceAll("$1");
        text = ITALIC_UNDERSCORE.matcher(text).replaceAll("$1");
        text = STRIKE.matcher(text).replaceAll("$1");
        return text.trim();
    }

    private static String images(String text) {
        Matcher image = IMAGE.matcher(text);
        if (!image.find()) return text;
        StringBuilder out = new StringBuilder();
        int last = 0;
        do {
            out.append(text, last, image.start());
            String alt = image.group(1).trim();
            out.append(alt.isEmpty() ? "[image]" : "[image: " + alt + "]");
            last = image.end();
        } while (image.find());
        return out.append(text.substring(last)).toString();
    }

    private static boolean isTableRow(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("|")) return false;
        return trimmed.indexOf('|', 1) > 0;
    }

    private static String tableRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        StringBuilder row = new StringBuilder();
        for (String cell : trimmed.split("\\|", -1)) {
            String text = inline(cell.trim());
            if (text.isEmpty()) continue;
            if (row.length() > 0) row.append("  ·  ");
            row.append(text);
        }
        return row.toString();
    }

    private static void addCode(List<Block> blocks, StringBuilder code) {
        String text = code.toString();
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') end--;
        text = text.substring(0, end);
        if (!text.isBlank()) blocks.add(new Block(Kind.CODE, text, 0));
    }

    private static void gap(List<Block> blocks) {
        if (blocks.isEmpty() || blocks.get(blocks.size() - 1).kind == Kind.GAP) return;
        blocks.add(new Block(Kind.GAP, "", 0));
    }

    private static String stripTrailing(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
}
