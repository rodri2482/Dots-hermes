package app.dotshermes.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBubbleWidthTest {
    @Test fun normalPhoneKeepsReferenceCap() {
        assertEquals(296.dp, chatBubbleMaxWidth(360.dp))
    }

    @Test fun narrowWindowLeavesMarginsAndOppositeGutter() {
        assertEquals(260.dp, chatBubbleMaxWidth(320.dp))
        assertEquals(180.dp, chatBubbleMaxWidth(240.dp))
    }

    @Test fun splitWindowUsesItsOwnWidthRatherThanDisplayWidth() {
        assertEquals(220.dp, chatBubbleMaxWidth(280.dp))
        assertTrue(chatBubbleMaxWidth(280.dp) < chatBubbleMaxWidth(800.dp))
    }

    @Test fun expandedWindowKeepsReadableLines() {
        assertEquals(576.dp, chatBubbleMaxWidth(800.dp))
        assertEquals(720.dp, chatBubbleMaxWidth(1400.dp))
    }

    @Test fun extremelySmallConstraintNeverProducesNegativeWidth() {
        assertEquals(0.dp, chatBubbleMaxWidth(40.dp))
    }
}
