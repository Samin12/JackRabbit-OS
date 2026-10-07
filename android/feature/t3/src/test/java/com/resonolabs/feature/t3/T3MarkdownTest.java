package com.resonolabs.feature.t3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class T3MarkdownTest {
    private static String dump(String markdown) {
        StringBuilder out = new StringBuilder();
        for (T3Markdown.Block block : T3Markdown.parse(markdown)) out.append(block).append('\n');
        return out.toString();
    }

    @Test
    public void codeFencesBecomeMonospaceBlocks() {
        List<T3Markdown.Block> blocks = T3Markdown.parse("Run this:\n\n```sh\nnpm test\n\tnpm run lint\n```\nDone.");
        assertEquals(4, blocks.size());
        assertEquals(T3Markdown.Kind.TEXT, blocks.get(0).kind);
        assertEquals(T3Markdown.Kind.GAP, blocks.get(1).kind);
        assertEquals(T3Markdown.Kind.CODE, blocks.get(2).kind);
        assertEquals("npm test\n  npm run lint", blocks.get(2).text);
        assertEquals("Done.", blocks.get(3).text);
    }

    @Test
    public void unclosedFenceWhileStreamingStillShowsCode() {
        List<T3Markdown.Block> blocks = T3Markdown.parse("```ts\nconst a = 1;\nconst b");
        assertEquals(1, blocks.size());
        assertEquals("const a = 1;\nconst b", blocks.get(0).text);
    }

    @Test
    public void structureIsKeptAndSyntaxStripped() {
        assertEquals("HEADING:What changed\nBULLET:withRetry() wraps the handler\nBULLET/1:nested item\n"
                        + "TEXT:1. numbered stays\nQUOTE:quoted\n",
                dump("## What changed ##\n- **`withRetry()`** wraps the handler\n   * nested item\n"
                        + "1. numbered stays\n> quoted"));
    }

    @Test
    public void inlineCleanupKeepsIdentifiers() {
        assertEquals("see the docs and README", T3Markdown.inline("see [the docs](https://x.dev/a) and *README*"));
        assertEquals("snake_case_name and file_name.py stay", T3Markdown.inline("snake_case_name and file_name.py stay"));
        assertEquals("bold, italic, gone", T3Markdown.inline("**bold**, _italic_, ~~gone~~"));
        assertEquals("2 * 3 * 4", T3Markdown.inline("2 * 3 * 4"));
        assertEquals("[image: diagram] here", T3Markdown.inline("![diagram](a.png) here"));
        assertEquals("a b", T3Markdown.inline("a<br/>b"));
        assertEquals("cost $1 and $2", T3Markdown.inline("cost `$1` and `$2`"));
    }

    @Test
    public void tablesBecomeReadableRows() {
        assertEquals("TEXT:Suite  ·  Result\nTEXT:webhooks  ·  14 passed\n",
                dump("| Suite | Result |\n|---|:---:|\n| webhooks | 14 passed |"));
    }

    @Test
    public void rulesAndBlankRunsCollapseToOneGap() {
        assertEquals("TEXT:a\nGAP:\nTEXT:b\n", dump("a\n\n\n---\n\nb\n\n"));
        assertTrue(T3Markdown.parse("").isEmpty());
        assertTrue(T3Markdown.parse(null).isEmpty());
    }

    @Test
    public void taskListsGetMarks() {
        assertEquals("BULLET:✓ shipped\nBULLET:☐ todo\n", dump("- [x] shipped\n- [ ] todo"));
    }

    @Test
    public void plainPreviewIsOneLine() {
        assertEquals("Fixed it run npm test then deploy",
                T3Markdown.plain("## Fixed it\n```\nrun npm test\n```\nthen **deploy**", 80));
        assertEquals("abcdefghi…", T3Markdown.plain("abcdefghijklmnop", 10));
    }
}
