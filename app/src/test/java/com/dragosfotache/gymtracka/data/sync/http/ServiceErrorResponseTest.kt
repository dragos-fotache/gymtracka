package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.codec.ServiceError
import com.dragosfotache.gymtracka.data.sync.codec.decodeServiceError
import com.dragosfotache.gymtracka.data.sync.codec.parseServiceResponseObject
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * strict service-error reader tables.
 *
 * Independent raw JSON bodies only, never production encoders.
 */
class ServiceErrorResponseTest {

    @Test
    fun `minimal errorCode survives exactly`() {
        val decoded = decodeServiceError("""{"errorCode":"future_code"}""")
        assertEquals("future_code", decoded.errorCode)
    }

    @Test
    fun `message absent null and string are discarded but accepted`() {
        assertEquals("a", decodeServiceError("""{"errorCode":"a"}""").errorCode)
        assertEquals("b", decodeServiceError("""{"errorCode":"b","message":null}""").errorCode)
        assertEquals("c", decodeServiceError("""{"errorCode":"c","message":"safe text"}""").errorCode)
        assertEquals("d", decodeServiceError("""{"errorCode":"d","message":""}""").errorCode)
    }

    @Test
    fun `additive fields including fullResyncRequired are ignored`() {
        val decoded = decodeServiceError(
            """{"errorCode":"cursor_expired","message":"x","fullResyncRequired":true,"extra":123,"nested":{"a":[1,2]}}""",
        )
        assertEquals("cursor_expired", decoded.errorCode)
    }

    @Test
    fun `unknown whitespace unicode long and escaped control codes survive`() {
        assertEquals("   ", decodeServiceError("""{"errorCode":"   "}""").errorCode)
        val unicode = "é_ß_中_🎉"
        assertEquals(unicode, decodeServiceError("""{"errorCode":"$unicode"}""").errorCode)
        val longCode = "x".repeat(2000)
        assertEquals(longCode, decodeServiceError("""{"errorCode":"$longCode"}""").errorCode)
        assertEquals("a\u0001b", decodeServiceError("""{"errorCode":"a\u0001b"}""").errorCode)
        assertEquals("UPPER_Mixed-123.+", decodeServiceError("""{"errorCode":"UPPER_Mixed-123.+"}""").errorCode)
    }

    @Test
    fun `empty code fails but whitespace-only passes`() {
        assertFailsWithoutEcho("""{"errorCode":""}""")
        assertEquals(" ", decodeServiceError("""{"errorCode":" "}""").errorCode)
        assertEquals("\t", decodeServiceError("""{"errorCode":"\t"}""").errorCode)
    }

    @Test
    fun `wrong-type errorCode values fail`() {
        assertFailsWithoutEcho("""{"errorCode":123}""")
        assertFailsWithoutEcho("""{"errorCode":true}""")
        assertFailsWithoutEcho("""{"errorCode":false}""")
        assertFailsWithoutEcho("""{"errorCode":null}""")
        assertFailsWithoutEcho("""{"errorCode":{}}""")
        assertFailsWithoutEcho("""{"errorCode":[]}""")
        assertFailsWithoutEcho("""{"errorCode":1e400}""")
        assertFailsWithoutEcho("""{"errorCode":1E400}""")
        assertFailsWithoutEcho("""{}""")
    }

    @Test
    fun `quoted 1e400 string is a valid unknown code`() {
        assertEquals("1e400", decodeServiceError("""{"errorCode":"1e400"}""").errorCode)
    }

    @Test
    fun `wrong-type message values fail`() {
        assertFailsWithoutEcho("""{"errorCode":"x","message":123}""")
        assertFailsWithoutEcho("""{"errorCode":"x","message":true}""")
        assertFailsWithoutEcho("""{"errorCode":"x","message":{}}""")
        assertFailsWithoutEcho("""{"errorCode":"x","message":[]}""")
        assertFailsWithoutEcho("""{"errorCode":"x","message":1e400}""")
    }

    @Test
    fun `unquoted overflow number never satisfies a string code on decode`() {
        // decodeServiceError rejects wrong code types even though the JVM tree alone
        // can hold 1e400 as a finite Number; Android overflow fallback must also reject.
        assertFailsWithoutEcho("""{"errorCode":1e400}""")
        assertFailsWithoutEcho("""{"errorCode":1e400,"message":"x"}""")
        assertEquals("1e400", parseServiceResponseObject("""{"errorCode":"1e400"}""").getString("errorCode"))
        // JVM raw tree accepts the same numeric shape as Number (finite BigDecimal path).
        assertTrue(parseServiceResponseObject("""{"errorCode":1e400}""").get("errorCode") is Number)
    }

    @Test
    fun `strict scalar fidelity across types`() {
        val obj = parseServiceResponseObject("""{"s":"x","n":12,"b":true,"z":null,"o":{},"a":[]}""")
        assertTrue(obj.get("s") is String)
        assertTrue(obj.get("n") is Number)
        assertEquals(true, obj.get("b"))
        assertTrue(obj.isNull("z"))
        assertTrue(obj.get("o") is org.json.JSONObject)
        assertTrue(obj.get("a") is org.json.JSONArray)
        assertFailsWithoutEcho("""{"errorCode":12}""")
        assertFailsWithoutEcho("""{"errorCode":true}""")
    }

    @Test
    fun `huge finite numeric extension may remain Number while numeric code cannot become String`() {
        val obj = parseServiceResponseObject(
            """{"errorCode":"x","bigInt":123456789012345678901234567890,"bigDec":1e308,"small":0.1}""",
        )
        assertTrue(obj.get("bigInt") is Number)
        assertTrue(obj.get("bigDec") is Number)
        assertTrue(obj.get("small") is Number)
        assertFailsWithoutEcho("""{"errorCode":1e308}""")
        assertFailsWithoutEcho("""{"errorCode":123456789012345678901234567890}""")
        assertFailsWithoutEcho("""{"errorCode":"x","message":1e400}""")
        assertFailsWithoutEcho("""{"errorCode":"x","message":123}""")
        assertEquals("123", decodeServiceError("""{"errorCode":"123"}""").errorCode)
        assertEquals("1e308", decodeServiceError("""{"errorCode":"1e308"}""").errorCode)
    }

    @Test
    fun `non-JSON and unquoted scalars fail`() {
        assertFailsWithoutEcho("not json")
        assertFailsWithoutEcho("123")
        assertFailsWithoutEcho("true")
        assertFailsWithoutEcho("null")
        assertFailsWithoutEcho("{unquoted:1}")
        assertFailsWithoutEcho("""{"errorCode":undefined}""")
    }

    @Test
    fun `literal unescaped string controls fail`() {
        assertFailsWithoutEcho("{\"errorCode\":\"a\nb\"}")
        assertFailsWithoutEcho("{\"errorCode\":\"a\tb\"}")
        assertEquals("a\nb", decodeServiceError("""{"errorCode":"a\nb"}""").errorCode)
    }

    @Test
    fun `duplicate decoded keys fail at every nesting level including ignored extensions`() {
        assertFailsWithoutEcho("""{"errorCode":"a","errorCode":"b"}""")
        assertFailsWithoutEcho("""{"errorCode":"a","ignored":1,"ignored":2}""")
        assertFailsWithoutEcho("""{"errorCode":"a","nested":{"x":1,"x":2}}""")
        assertFailsWithoutEcho("""{"errorCode":"a","arr":[{"k":1,"k":2}]}""")
        assertFailsWithoutEcho("""{"errorCode":"a","\u0065rrorCode":"b"}""")
    }

    @Test
    fun `wrong top-level type and trailing syntax fail`() {
        assertFailsWithoutEcho("""[]""")
        assertFailsWithoutEcho(""""str"""")
        assertFailsWithoutEcho("""123""")
        assertFailsWithoutEcho("""null""")
        assertFailsWithoutEcho("""{"errorCode":"a"} trailing""")
        assertFailsWithoutEcho("""{"errorCode":"a",}""")
        assertFailsWithoutEcho("""{"errorCode":"a"""")
        assertFailsWithoutEcho("""{"errorCode":"a","message":"x"""")
    }

    @Test
    fun `valid escaped opaque data stays unchanged`() {
        val obj = parseServiceResponseObject(
            """{"cursor":"a+b%c\/d?e=f&g","uni":"é\u00e9","q":"\"quoted\""}""",
        )
        assertEquals("a+b%c/d?e=f&g", obj.getString("cursor"))
        assertEquals("éé", obj.getString("uni"))
        assertEquals("\"quoted\"", obj.getString("q"))
    }

    @Test
    fun `raw parser failures use per-kind fixed sentinels`() {
        // Spec never requires cross-kind equality; each kind has its own fixed message.
        fun decodeMessage(input: String): String? {
            return try {
                decodeServiceError(input)
                fail("expected failure")
                null
            } catch (e: SyncModelException) {
                assertTrue(e.cause == null)
                assertTrue(e.suppressed.isEmpty())
                if (input.isNotEmpty()) assertTrue(!e.message.orEmpty().contains(input))
                e.message
            }
        }
        // Same-kind pairs share the sentinel; differing numeric codes do not leak values.
        assertEquals(decodeMessage("""{"errorCode":123}"""), decodeMessage("""{"errorCode":true}"""))
        assertEquals("Invalid service error", decodeMessage("""{"errorCode":123}"""))
        assertEquals(
            decodeMessage("""{"errorCode":"a","errorCode":"b"}"""),
            decodeMessage("""{"errorCode":"a","ignored":1,"ignored":2}"""),
        )
        assertEquals("Duplicate JSON member name", decodeMessage("""{"errorCode":"a","errorCode":"b"}"""))
        fun treeMessage(input: String): String? {
            return try {
                parseServiceResponseObject(input)
                fail("expected tree failure")
                null
            } catch (e: SyncModelException) {
                assertTrue(e.cause == null)
                assertTrue(e.suppressed.isEmpty())
                e.message
            }
        }
        assertEquals(treeMessage("not json"), treeMessage("{unquoted:1}"))
        assertEquals("Body is not valid JSON", treeMessage("not json"))
        assertEquals(treeMessage("[]"), treeMessage("""123"""))
        assertEquals("Body must be a JSON object", treeMessage("[]"))
    }

    @Test
    fun `failures are fixed-text with null cause and no raw leakage`() {
        val raw = """{"errorCode":123,"message":{}}"""
        try {
            decodeServiceError(raw)
            fail("expected SyncModelException")
        } catch (e: SyncModelException) {
            assertTrue(e.cause == null)
            assertTrue(e.suppressed.isEmpty())
            assertTrue(!e.message.orEmpty().contains("123"))
            assertTrue(!e.message.orEmpty().contains(raw))
        }
        try {
            parseServiceResponseObject("""{"a":1,"a":2}""")
            fail("expected SyncModelException")
        } catch (e: SyncModelException) {
            assertTrue(e.cause == null)
            assertTrue(e.suppressed.isEmpty())
        }
    }

    @Test
    fun `ServiceError display is fixed and redacted`() {
        val secret = "super_secret_code_123"
        val err = ServiceError(secret)
        assertEquals(secret, err.errorCode)
        assertTrue(!err.toString().contains(secret))
        val decoded = decodeServiceError("""{"errorCode":"$secret","message":"hidden body text"}""")
        assertTrue(!decoded.toString().contains(secret))
        assertTrue(!decoded.toString().contains("hidden body text"))
    }

    private fun assertFailsWithoutEcho(jsonText: String) {
        try {
            decodeServiceError(jsonText)
            fail("expected failure for: $jsonText")
        } catch (e: SyncModelException) {
            assertTrue(e.cause == null)
            assertTrue(e.suppressed.isEmpty())
            // contains("") is always true; only check echo for nonempty input.
            if (jsonText.isNotEmpty()) {
                assertTrue(!e.message.orEmpty().contains(jsonText))
            }
        }
    }
}
