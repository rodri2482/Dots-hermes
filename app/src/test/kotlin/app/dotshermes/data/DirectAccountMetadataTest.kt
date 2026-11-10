package app.dotshermes.data

import app.dotshermes.data.direct.ApiProvider
import org.junit.Assert.*
import org.junit.Test

class DirectAccountMetadataTest {
    @Test fun `direct accounts cannot receive gateway notifications`() {
        val gateway = AgentLink("gateway", "wss://gateway.invalid/dots/ws", "fixture", "remote")
        val direct = AgentLink("direct", "", "", provider = ApiProvider.OpenAI, model = "chosen")
        assertEquals(gateway, routePush(listOf(direct, gateway), "remote"))
        assertNull(routePush(listOf(direct), null))
        assertNull(routePush(listOf(direct), "unknown"))
    }
    @Test fun `legacy and direct metadata round trip without storing provider credentials`() {
        val links = listOf(AgentLink("gateway", "wss://gateway.invalid", "fixture"),
            AgentLink("direct", "", "", provider = ApiProvider.Anthropic, model = "chosen"))
        assertEquals(links, AgentLinkCodec.decode(AgentLinkCodec.encode(links)))
        val legacy = "[{\"id\":\"main\",\"url\":\"wss://legacy.invalid\",\"token\":\"fixture\"}]"
        assertNull(AgentLinkCodec.decode(legacy).single().provider)
        assertThrows(IllegalArgumentException::class.java) {
            AgentLink("direct", "", "secret-must-not-enter-metadata", provider = ApiProvider.OpenAI, model = "chosen")
        }
    }
}
