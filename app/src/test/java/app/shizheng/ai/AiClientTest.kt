package app.shizheng.ai

import app.shizheng.data.Category
import app.shizheng.data.UserError
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class AiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: OkHttpClient
    private val settings get() = AiConfig(true, server.url("/v1").newBuilder().host("127.0.0.1").build().toString(), "fixture-secret", "mock-model")

    @Before fun init() {
        val ca = HeldCertificate.Builder().commonName("Shizheng test authority").rsa2048().certificateAuthority(0).build()
        val cert = HeldCertificate.Builder().commonName("localhost").rsa2048()
            .addSubjectAlternativeName("127.0.0.1").signedBy(ca).build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert, ca.certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build()
        server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        transport = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .followRedirects(false).retryOnConnectionFailure(false).build()
    }

    @After fun close() {
        transport.dispatcher.cancelAll()
        transport.connectionPool.evictAll()
        server.shutdown()
    }

    @Test fun mapsExplicitFactsAndSendsOnlyConfirmedTextAndInstructions() = runBlocking {
        server.enqueue(answer("""{"title":"图书馆志愿活动","category":"志愿服务","start":"2026-11-24","result":"服务8小时","organization":"图书馆","level":"国家级"}"""))
        val source = "2026年11月24日，参加图书馆志愿活动，组织单位图书馆，服务8小时。"
        val draft = AiClient(transport).generate(settings, source)
        assertEquals("图书馆志愿活动", draft.title)
        assertEquals(Category.VOLUNTEER, draft.category)
        assertEquals("2026-11-24", draft.start)
        assertEquals("服务8小时", draft.result)
        assertEquals("图书馆", draft.organization)
        assertNull(draft.level)
        assertTrue(draft.files.isEmpty())
        val request = server.takeRequest(2, TimeUnit.SECONDS) ?: error("Missing request")
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer fixture-secret", request.getHeader("Authorization"))
        val payload = request.body.readUtf8()
        assertFalse(payload.contains("fixture-secret"))
        val json = Json.parseToJsonElement(payload).jsonObject
        assertEquals("mock-model", json.getValue("model").jsonPrimitive.content)
        val messages = json.getValue("messages").jsonArray
        assertEquals(DRAFT_INSTRUCTIONS, messages[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(source, messages[1].jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test fun doesNotInventDatesAwardsOrOrganizations() = runBlocking {
        server.enqueue(answer("""{"title":"图书馆活动","start":"2026-11-24","organization":"校团委","result":"一等奖"}"""))
        val draft = AiClient(transport).generate(settings, "11月24日参加图书馆活动。")
        assertEquals("图书馆活动", draft.title)
        assertEquals("", draft.start)
        assertEquals("", draft.organization)
        assertEquals("", draft.result)
        assertNull(draft.category)
        assertThrows(UserError::class.java) { draft.toRecord(emptyList()) }
        Unit
    }

    @Test fun testsConnectionWithFixedMessageAndReportsAuthenticationOrMalformedResponse() = runBlocking {
        val client = AiClient(transport)
        server.enqueue(answer("OK"))
        client.test(settings)
        val request = server.takeRequest(2, TimeUnit.SECONDS) ?: error("Missing request")
        assertTrue(request.body.readUtf8().contains(TEST_MESSAGE))
        server.enqueue(MockResponse().setResponseCode(401).setBody("fixture-secret"))
        val auth = runCatching { client.test(settings) }.exceptionOrNull()
        assertTrue(auth is UserError && auth.message.orEmpty().contains("鉴权"))
        assertFalse(auth.toString().contains("fixture-secret"))
        server.enqueue(answer("invalid"))
        val malformed = runCatching { client.generate(settings, "活动") }.exceptionOrNull()
        assertTrue(malformed is UserError && malformed.message.orEmpty().contains("格式"))
    }

    @Test fun cancelsAndTimesOutWithoutDraft() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = AiClient(transport)
        var delivered = false
        val job = launch {
            client.generate(settings, "活动")
            delivered = true
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        job.cancelAndJoin()
        assertFalse(delivered)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val fast = AiClient(transport.newBuilder().callTimeout(250, TimeUnit.MILLISECONDS).build())
        val timeout = runCatching { fast.generate(settings, "活动") }.exceptionOrNull()
        assertTrue(timeout is UserError && timeout.message.orEmpty().contains("超时"))
    }

    @Test fun rejectsInsecureOrEmbeddedCredentials() {
        assertThrows(UserError::class.java) { settings.copy(address = "http://localhost/v1").requireComplete() }
        assertThrows(UserError::class.java) { settings.copy(address = "https://user:pass@example.org/v1").requireComplete() }
        assertThrows(UserError::class.java) { settings.copy(address = "https://example.org/v1?secret=123").requireComplete() }
        assertEquals("/v1/chat/completions", settings.copy(address = "https://example.org").requireComplete().encodedPath)
        assertFalse(settings.toString().contains("fixture-secret"))
    }

    private fun answer(content: String): MockResponse = MockResponse().setBody(buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject { put("message", buildJsonObject { put("content", content) }) })
        })
    }.toString())
}
