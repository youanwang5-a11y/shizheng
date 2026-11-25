package app.shizheng

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.shizheng.ai.AiClient
import app.shizheng.ai.AiConfig
import app.shizheng.ai.AiConfigStore
import app.shizheng.ui.AppState
import app.shizheng.ui.ShizhengApp
import app.shizheng.ui.ShizhengTheme
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordFlowTest {
    @get:Rule val ui = createComposeRule()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as Application

    @Test fun storesUnprovenRecordThenFindsItAndUsesIndependentAiEntries() {
        val config = AiConfigStore(app)
        val previous = config.read()
        config.write(AiConfig())
        try {
            val state = render()
            click("我的")
            click("学期管理")
            click("当前学期")
            click("大二上")
            clickDescription("返回")
            click("记录")
            clickDescription("新增记录")
            ui.onNode(hasSetTextAction() and hasText("名称 *")).performTextInput("设备测试·图书馆志愿服务")
            click("类别 *")
            click("志愿服务")
            click("保存记录")
            waitFor("添加证明")
            assertTrue(ui.onAllNodesWithText("待补证明").fetchSemanticsNodes().isNotEmpty())
            clickDescription("返回")
            ui.onAllNodesWithText("待补证明").onFirst().performClick()
            waitFor("设备测试·图书馆志愿服务")
            ui.onNode(hasSetTextAction()).performTextInput("没有匹配的关键字")
            waitFor("没有符合条件的记录")
            click("清空")
            click("我的")
            click("AI 记录")
            waitFor("AI 已关闭。")
            click("打开 AI 设置")
            waitFor("测试连接")
            ui.onNodeWithText("识别并填入").assertDoesNotExist()
            clickDescription("返回")
            waitFor("AI 已关闭。")
            clickDescription("返回")
            click("AI 设置")
            waitFor("测试连接")
            clickDescription("返回")
            waitFor("数据概况")
            val data = state.data.value
            val saved = data.records.last { it.title == "设备测试·图书馆志愿服务" }
            assertEquals("", saved.start)
            assertEquals("大二上", data.termName(saved.termId))
        } finally {
            config.write(previous)
        }
    }

    @Test fun fillsNewRecordWithBuiltInAiAfterConfirmationAndKeepsManualFields() {
        val config = AiConfigStore(app)
        val previous = config.read()
        config.write(AiConfig.builtInDefault())
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val input = "2026年11月24日参加校内光学竞赛。"
        val answer = """{"title":"校内光学竞赛","category":"竞赛","start":"2026-11-24"}"""
        val reply = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject { put("message", buildJsonObject { put("content", answer) }) })
            })
        }.toString()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.toString() == "https://api.deepseek.com/chat/completions")
            check(request.header("Authorization") == "Bearer device-fixture-token")
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val body = buffer.readUtf8()
            check(body.contains("deepseek-flash") && body.contains(input))
            val result = requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (result == 1) 401 else 200).message("Mock response").body(reply.toResponseBody()).build()
        }.build()
        try {
            val state = render(AiClient(transport, builtInKey = "device-fixture-token"))
            val before = state.data.value.records.size
            clickDescription("新增记录")
            ui.onNode(hasSetTextAction() and hasText("名称 *")).performScrollTo()
                .performTextInput("设备测试·已核对的竞赛")
            ui.onNode(hasSetTextAction() and hasText("描述你的经历")).performScrollTo().performTextInput(input)
            ui.onNodeWithText("识别并填入").performScrollTo().performClick()
            waitFor("目标服务")
            assertEquals(0, requests.get())
            assertEquals(before, state.data.value.records.size)
            click("确认发送并填写")
            waitFor("内置 DeepSeek 鉴权失败，请联系分发者。")
            ui.onNodeWithText("请核对识别结果").assertDoesNotExist()
            assertEquals(before, state.data.value.records.size)
            ui.onNodeWithText("识别并填入").performScrollTo().performClick()
            click("确认发送并填写")
            waitFor("请核对识别结果")
            assertEquals(2, requests.get())
            assertEquals(before, state.data.value.records.size)
            assertEquals("设备测试·已核对的竞赛", state.draft?.title)
            click("保存记录")
            waitFor("设备测试·已核对的竞赛")
            val record = state.data.value.records.last { it.title == "设备测试·已核对的竞赛" }
            assertTrue(state.data.value.filesFor(record.id).isEmpty())
            assertEquals("2026-11-24", record.start)
            assertEquals(before + 1, state.data.value.records.size)
            val encrypted = app.getSharedPreferences("ai_config_encrypted", 0).all.toString()
            assertFalse(encrypted.contains("device-fixture-token"))
            assertTrue(config.read().useBuiltIn)
            assertTrue(config.read().key.isEmpty())
        } finally {
            config.write(previous)
        }
    }

    private fun render(client: AiClient = AiClient()): AppState {
        lateinit var state: AppState
        ui.runOnUiThread { state = AppState(app, client) }
        ui.setContent { ShizhengTheme { ShizhengApp(state) } }
        ui.waitUntil(12_000) { ui.onAllNodesWithContentDescription("新增记录").fetchSemanticsNodes().isNotEmpty() }
        return state
    }

    private fun waitFor(text: String) = ui.waitUntil(12_000) {
        ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun click(text: String) {
        waitFor(text)
        ui.onAllNodesWithText(text).onFirst().performClick()
    }

    private fun clickDescription(description: String) = ui.onNodeWithContentDescription(description).performClick()
}
