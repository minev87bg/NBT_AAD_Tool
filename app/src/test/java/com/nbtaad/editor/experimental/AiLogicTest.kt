package com.nbtaad.editor.experimental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLogicTest {
    private fun topic(body: String = "Body text with a [link](https://x.test/a.zip)") = Topic(
        242, false, "F11 6WA cluster", "vldbg", true, false, false, 3, "2026-11-16T10:00:00Z", "2026-11-18T09:00:00Z",
        listOf("bug"), body, "https://github.com/Sq00ky/Supra-iDrive6-Android-Auto/issues/242",
    )

    private fun comment(i: Int, body: String) = Comment(242, "user$i", "2026-11-1${i % 9}T10:00:00Z", body, "https://x.test/c$i")

    @Test
    fun thePageTextHasTheDetailsAndKeepsLinks() {
        val text = AiLogic.pageText(topic("<!-- hidden -->\nHello\n\n\n\n[zip](https://x.test/a.zip)"), listOf(comment(1, "first comment")))
        assertTrue(text.contains("Issue #242: F11 6WA cluster"))
        assertTrue(text.contains("State: open. Opened by vldbg on 2026-11-16"))
        assertTrue(text.contains("Labels: bug"))
        assertTrue(text.contains("[zip](https://x.test/a.zip)"))
        assertFalse(text.contains("hidden"))
        assertTrue(text.contains("[comment by user1, 2026-11-11]\nfirst comment"))
        assertTrue(AiLogic.userMessage(topic(), emptyList()).startsWith("<page>\n"))
        assertTrue(AiLogic.userMessage(topic(), emptyList()).endsWith("\n</page>"))
    }

    @Test
    fun aLongPageKeepsTheFirstAndTheLatestComments() {
        val comments = (1..40).map { comment(it, "comment number $it " + "x".repeat(800)) }
        val text = AiLogic.pageText(topic(), comments, maxTotal = 9_000)
        assertTrue(text.length <= 9_500)
        assertTrue(text.contains("comment number 1 "))
        assertTrue(text.contains("comment number 40 "))
        assertFalse(text.contains("comment number 20 "))
        assertTrue(text.contains("comments left out"))
        assertTrue(text.contains("Comments (40 in all)"))
    }

    @Test
    fun aHugePostIsCut() {
        val text = AiLogic.pageText(topic("y".repeat(50_000)), emptyList())
        assertTrue(text.length < 13_000)
        assertTrue(text.contains("the rest of this post was left out"))
    }

    @Test
    fun theRequestIsValidJsonWithTheModelAndThePage() {
        val json = AiLogic.requestJson(topic("quote \" and backslash \\ and\nnewline\ttab"), emptyList())
        val root = Json.parse(json).asObj()!!
        assertEquals("claude-haiku-5-5", root.str("model"))
        assertEquals(AiLogic.MAX_TOKENS, root.int("max_tokens"))
        assertEquals(AiLogic.SYSTEM_PROMPT, root.str("system"))
        val msg = root["messages"].asList()!![0].asObj()!!
        assertEquals("user", msg.str("role"))
        assertTrue(msg.str("content").contains("quote \" and backslash \\ and\nnewline\ttab"))
        assertEquals("\"a\\u0001b\"", AiLogic.jsonString("a\u0001b"))
    }

    @Test
    fun theAnswerIsReadAndErrorsAreTranslated() {
        val ok = "{\"id\":\"msg_1\",\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":\"- one\\n- two\"}],\"stop_reason\":\"end_turn\"}"
        assertEquals("- one\n- two", AiLogic.answerText(ok))
        assertEquals("end_turn", AiLogic.stopReason(ok))
        assertNull(AiLogic.answerText("{\"content\":[]}"))
        assertNull(AiLogic.answerText("not json"))

        val bad = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"
        assertTrue(AiLogic.errorText(401, bad).contains("refused the key"))
        val credit = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Your credit balance is too low to access the Anthropic API.\"}}"
        assertTrue(AiLogic.errorText(400, credit).contains("no credit left"))
        assertTrue(AiLogic.errorText(429, "{}").contains("Too many requests"))
        assertTrue(AiLogic.errorText(529, "").contains("busy"))
        assertTrue(AiLogic.errorText(404, "{\"error\":{\"type\":\"not_found_error\",\"message\":\"model\"}}").contains("claude-haiku-5-5"))
        assertEquals("Anthropic answered 418: I am a teapot", AiLogic.errorText(418, "{\"error\":{\"type\":\"x\",\"message\":\"I am a teapot\"}}"))
        assertEquals("Anthropic answered 418.", AiLogic.errorText(418, "<html>"))
    }

    @Test
    fun aKeyIsCheckedForTypingMistakesOnly() {
        assertTrue(AiLogic.looksLikeKey("  sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789  "))
        assertFalse(AiLogic.looksLikeKey("sk-ant-short"))
        assertFalse(AiLogic.looksLikeKey("abcdefghijklmnopqrstuvwxyz0123456789012345"))
        assertFalse(AiLogic.looksLikeKey("sk-ant-api03-abcdefghij klmnopqrstuvwxyz0123456789"))
        assertNotNull(AiLogic.SYSTEM_PROMPT)
    }

    @Test
    fun theRequestTurnsThinkingOffAndCanBeSentWithoutIt() {
        val json = AiLogic.requestJson(topic("hello"), emptyList())
        assertEquals("disabled", Json.parse(json).asObj()!!["thinking"].asObj()!!.str("type"))
        val without = json.replace(AiLogic.THINKING_OFF, "")
        val root = Json.parse(without).asObj()!!
        assertNull(root["thinking"])
        assertEquals("claude-haiku-5-5", root.str("model"))
    }

    @Test
    fun anAnswerWithOnlyThinkingHasNoTextAndTheLogSaysSo() {
        val body = """{"content":[{"type":"thinking","thinking":"hm"}],"stop_reason":"max_tokens"}"""
        assertNull(AiLogic.answerText(body))
        assertEquals("max_tokens", AiLogic.stopReason(body))
        assertEquals("thinking", AiLogic.blockTypes(body))
        assertEquals("thinking, text", AiLogic.blockTypes("""{"content":[{"type":"thinking"},{"type":"text","text":"x"}]}"""))
        assertEquals("unreadable", AiLogic.blockTypes("not json"))
    }

    @Test
    fun onlyABadRequestAboutThinkingIsRetriedWithoutIt() {
        assertTrue(AiLogic.rejectsThinking(400, """{"error":{"type":"invalid_request_error","message":"thinking: not supported"}}"""))
        assertFalse(AiLogic.rejectsThinking(400, """{"error":{"message":"max_tokens too large"}}"""))
        assertFalse(AiLogic.rejectsThinking(401, """thinking"""))
    }
}
