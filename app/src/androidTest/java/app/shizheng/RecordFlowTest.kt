package app.shizheng

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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
            clickDescription("新增记录")
            ui.onNode(hasSetTextAction() and hasText("名称 *")).performTextInput("设备测试·图书馆志愿服务")
            click("类别 *")
            click("志愿服务")
            click("选择日期")
            val device = UiDevice.getInstance(instrument)
            val accept = device.wait(Until.findObject(By.res("android", "button1")), 5_000)
            assertNotNull("Date picker positive button", accept)
            accept!!.click()
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
            waitFor("需要配置 AI")
            click("去配置")
            waitFor("测试连接")
            ui.onNodeWithText("生成记录草稿").assertDoesNotExist()
            clickDescription("返回")
            waitFor("需要配置 AI")
            click("暂不使用")
            click("AI 设置")
            waitFor("测试连接")
            clickDescription("返回")
            waitFor("数据概况")
            assertTrue(state.data.value.records.any { it.title == "设备测试·图书馆志愿服务" })
        } finally {
            config.write(previous)
        }
    }

    @Test fun confirmsMockAiSendThenExplicitlySavesEditedOrdinaryRecord() {
        val config = AiConfigStore(app)
        val previous = config.read()
        config.write(AiConfig(true, "https://mock.invalid/v1", "device-fixture-token", "test-model"))
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val input = "2026年11月24日参加校内光学竞赛。"
        val answer = """{"title":"校内光学竞赛","category":"竞赛","start":"2026-11-24"}"""
        val reply = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject { put("message", buildJsonObject { put("content", answer) }) })
            })
        }.toString()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val result = requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (result == 1) 401 else 200).message("Mock response").body(reply.toResponseBody()).build()
        }.build()
        try {
            val state = render(AiClient(transport))
            val before = state.data.value.records.size
            click("我的")
            click("AI 记录")
            ui.onNode(hasSetTextAction() and hasText("经历文字")).performTextInput(input)
            click("生成记录草稿")
            waitFor("目标服务")
            assertEquals(0, requests.get())
            assertEquals(before, state.data.value.records.size)
            click("确认发送并生成")
            waitFor("服务鉴权失败，请检查 API Key 与模型权限。")
            ui.onNodeWithText("请核对识别结果").assertDoesNotExist()
            assertEquals(before, state.data.value.records.size)
            click("生成记录草稿")
            click("确认发送并生成")
            waitFor("请核对识别结果")
            assertEquals(2, requests.get())
            assertEquals(before, state.data.value.records.size)
            ui.onNode(hasSetTextAction() and hasText("名称 *")).performTextReplacement("设备测试·已核对的竞赛")
            click("保存记录")
            waitFor("设备测试·已核对的竞赛")
            val record = state.data.value.records.single { it.title == "设备测试·已核对的竞赛" }
            assertTrue(state.data.value.filesFor(record.id).isEmpty())
            assertEquals(before + 1, state.data.value.records.size)
            val encrypted = app.getSharedPreferences("ai_config_encrypted", 0).all.toString()
            assertFalse(encrypted.contains("device-fixture-token"))
            assertEquals("test-model", config.read().model)
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
