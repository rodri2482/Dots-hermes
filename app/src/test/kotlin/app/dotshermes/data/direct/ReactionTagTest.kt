package app.dotshermes.data.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReactionTagTest {
    private fun run(vararg parts: String): Pair<String, String?> {
        val tag = ReactionTag()
        val text = parts.joinToString("") { tag.feed(it) } + tag.flush()
        return text to tag.reaction
    }

    @Test fun `tag split across chunks is hidden and its emoji kept`() =
        assertEquals("Haha, good one!" to "😂", run("[[rea", "ct:😂", "]] Haha", ", good one!"))

    @Test fun `none means no reaction`() = assertEquals("Sure." to null, run("[[react:none]]Sure."))

    @Test fun `unknown emoji is ignored but still hidden`() = assertEquals("Hi" to null, run("[[react:🦄]] Hi"))

    @Test fun `a reply without a tag passes through untouched`() {
        val (text, reaction) = run("[Note] ", "this is plain")
        assertEquals("[Note] this is plain", text)
        assertNull(reaction)
    }

    @Test fun `a reply that only looks like the start of a tag is not lost`() = assertEquals("[[re" to null, run("[[re"))
}
