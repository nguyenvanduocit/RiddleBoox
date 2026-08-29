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

    /** OpenAI's list carries no modality info; silence must not empty the list. */
    @Test
    fun `keeps every model when the server says nothing about modalities`() {
        val json = """{"data":[{"id":"b-model"},{"id":"a-model"}]}"""

        assertEquals(listOf("a-model", "b-model"), parseModelIds(json))
    }

    /** Some self-hosted proxies write `"architecture": null`; one odd entry must not sink the list. */
    @Test
    fun `a null architecture keeps the model rather than failing the parse`() {
        val json = """{"data":[{"id":"proxy/model","architecture":null},{"id":"other/model"}]}"""

        assertEquals(listOf("other/model", "proxy/model"), parseModelIds(json))
    }

    @Test
    fun `a list without data is empty, not an error`() {
        assertEquals(emptyList<String>(), parseModelIds("""{"object":"list"}"""))
    }

    @Test
    fun `asks v1 models with the key as a bearer token`() {
        val body = """{"data":[{"id":"openai/gpt-5.6-luna"}]}"""
        FakeChatServer(httpOk(body)).use { server ->
            val ids = fetchModelIds(server.baseUrl, "sk-test-key")

            assertEquals(listOf("openai/gpt-5.6-luna"), ids)
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
