package com.riddleboox.app.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ModelCatalogTest {

    @Test
    fun `reads ids out of an OpenAI-shaped model list`() {
        val json = """{"object":"list","data":[{"id":"gpt-5.6-luna","object":"model"},{"id":"gpt-4o","object":"model"}]}"""

        assertEquals(listOf("gpt-4o", "gpt-5.6-luna"), parseModelIds(json))
    }

    /**
     * OpenRouter says which models can see a page; a diary that sends pages
     * as images must not offer a model that cannot read them.
     */
    @Test
    fun `keeps only models that can see, when the server says who can`() {
        val json = """
            {"data":[
                {"id":"blind/text-only","architecture":{"input_modalities":["text"]}},
                {"id":"sighted/vision","architecture":{"input_modalities":["text","image"]}}
            ]}
        """.trimIndent()

        assertEquals(listOf("sighted/vision"), parseModelIds(json))
    }

    /**
     * OpenAI's list carries no modality info, so ids are kept only when they
     * match a known vision family (`gpt-4o`, `gpt-5`, ...) — a bare `o3-mini`
     * would otherwise sit next to embeddings and TTS ids the picker cannot use.
     */
    @Test
    fun `when the server says nothing about modalities, keeps only known vision families`() {
        val json = """{"data":[{"id":"text-embedding-3-large"},{"id":"gpt-4o-mini"},{"id":"gpt-5.7-luna"}]}"""

        assertEquals(listOf("gpt-4o-mini", "gpt-5.7-luna"), parseModelIds(json))
    }

    /** Some self-hosted proxies write `"architecture": null`; one odd entry must not sink the list. */
    @Test
    fun `a null architecture falls back to the vision-family check rather than failing the parse`() {
        val json = """{"data":[{"id":"gpt-4o","architecture":null},{"id":"whisper-1"}]}"""

        assertEquals(listOf("gpt-4o"), parseModelIds(json))
    }

    @Test
    fun `a list without data is empty, not an error`() {
        assertEquals(emptyList<String>(), parseModelIds("""{"object":"list"}"""))
    }

    @Test
    fun `asks v1 models with the key as a bearer token`() {
        val body = """{"data":[{"id":"gpt-5.6-luna"}]}"""
        FakeChatServer(httpOk(body)).use { server ->
            val ids = fetchModelIds(server.baseUrl, "sk-test-key")

            assertEquals(listOf("gpt-5.6-luna"), ids)
            val request = server.takeRequest()
            assertEquals("/v1/models", request.path)
            assertEquals("Bearer sk-test-key", request.authorization)
        }
    }

    /** The same normalisation the chat client gets: a trailing slash is not a different server. */
    @Test
    fun `a trailing slash on the base url does not double up`() {
        FakeChatServer(httpOk("""{"data":[]}""")).use { server ->
            fetchModelIds(server.baseUrl + "/", "sk")

            assertEquals("/v1/models", server.takeRequest().path)
        }
    }

    @Test
    fun `a non-2xx answer is an error, not an empty list`() {
        val response = "HTTP/1.1 401 Unauthorized\nContent-Length: 0\nConnection: close\n\n"
        FakeChatServer(response).use { server ->
            val error = assertThrows(IOException::class.java) {
                fetchModelIds(server.baseUrl, "sk-wrong")
            }
            assertTrue(error.message.orEmpty().contains("401"))
        }
    }

    private fun httpOk(body: String): String {
        val bytes = body.toByteArray()
        return "HTTP/1.1 200 OK\nContent-Type: application/json\nContent-Length: ${bytes.size}\nConnection: close\n\n$body"
    }
}
