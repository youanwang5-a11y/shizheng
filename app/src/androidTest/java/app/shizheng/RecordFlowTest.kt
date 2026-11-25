package app.shizheng

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.shizheng.ai.AiClient
import app.shizheng.ai.AiConfig
import app.shizheng.ai.AiConfigStore
import app.shizheng.data.Category
import app.shizheng.data.Level
import app.shizheng.data.RecordDraft
import app.shizheng.data.RecordFilter
import app.shizheng.data.Snapshot
import app.shizheng.data.defaultTerms
import app.shizheng.data.newId
import app.shizheng.ui.RecordExportScreen
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

    @Test fun welcomeSetsDefaultTermAndStaysDismissedAfterNewAppState() {
        val preferences = app.getSharedPreferences("welcome", Application.MODE_PRIVATE)
        val previous = preferences.all["completed"] as? Boolean
        lateinit var state: AppState
        assertTrue(preferences.edit().putBoolean("completed", false).commit())
        val shown = androidx.compose.runtime.mutableStateOf<AppState?>(null)
        ui.setContent { shown.value?.let { ShizhengTheme { ShizhengApp(it) } } }
        try {
            ui.runOnUiThread { state = AppState(app); shown.value = state }
            waitFor("欢迎使用拾证")
            val previousTerm = state.data.value.terms.firstOrNull { it.current }
            try {
                click("默认学期")
                click("大三下")
                click("开始使用")
                ui.waitUntil(12_000) { !state.showWelcome }
                ui.runOnUiThread { shown.value = AppState(app) }
                ui.waitUntil(12_000) { shown.value?.ready == true }
                ui.onNodeWithText("欢迎使用拾证").assertDoesNotExist()
                clickDescription("新增记录")
                val reopened = requireNotNull(shown.value)
                assertEquals("大三下", reopened.data.value.termName(reopened.draft?.termId))
            } finally {
                kotlinx.coroutines.runBlocking {
                    if (previousTerm == null) state.repository.clearCurrentTerm()
                    else state.repository.saveTerm(previousTerm)
                }
            }
        } finally {
            val edit = preferences.edit()
            if (previous == null) edit.remove("completed") else edit.putBoolean("completed", previous)
            assertTrue(edit.commit())
        }
    }

    @Test fun exportSelectsBothTermsOfAcademicYearAndAllowsCustomScope() {
        val terms = defaultTerms
        val records = listOf(terms[0].id, terms[1].id, terms[2].id, null).mapIndexed { index, id ->
            RecordDraft(title = "学年测试 $index", category = Category.OTHER.name, termId = id).toRecord(terms)
        }
        var exported: Set<String> = emptySet()
        ui.setContent {
            ShizhengTheme { RecordExportScreen(Snapshot(terms = terms, records = records)) { exported = it } }
        }
        ui.onNodeWithText("导出 0 条记录").assertIsNotEnabled()
        click("快速选择学年")
        click("大一")
        ui.onNodeWithText("大一上").assertIsOn()
        ui.onNodeWithText("大一下").assertIsOn()
        ui.onNodeWithText("大二上").assertIsOff()
        click("导出 2 条记录")
        assertEquals(records.take(2).map { it.id }.toSet(), exported)
        click("大一下")
        click("大二上")
        click("导出 2 条记录")
        assertEquals(setOf(records[0].id, records[2].id), exported)
    }

    @Test fun multiSelectLimitsSelectAllAndDeletionToVisibleRecords() {
        val state = render()
        val drafts = listOf("批量验证甲", "批量验证乙", "保留验证丙").map {
            RecordDraft(title = it, category = Category.OTHER.name)
        }
        try {
            kotlinx.coroutines.runBlocking { drafts.forEach { state.repository.save(it) } }
            ui.runOnIdle { state.changeFilter(RecordFilter(query = "批量验证")) }
            click("批量验证甲")
            waitFor("记录详情")
            clickDescription("返回")
            ui.onNodeWithText("批量验证甲").performTouchInput { longClick() }
            waitFor("已选 1 条")
            ui.onNodeWithText("批量验证甲").assertIsOn()
            ui.onNodeWithText("批量验证乙").assertIsOff()
            clickDescription("退出多选")
            click("多选")
            ui.onNodeWithText("导出").assertIsNotEnabled()
            click("批量验证甲")
            waitFor("已选 1 条")
            click("全选")
            waitFor("已选 2 条")
            ui.onNodeWithText("保留验证丙").assertDoesNotExist()
            ui.onNodeWithText("导出").assertIsEnabled()
            click("取消全选")
            waitFor("已选 0 条")
            click("全选")
            ui.runOnIdle { state.changeFilter(RecordFilter(query = "批量验证甲")) }
            waitFor("已选 0 条")
            ui.runOnIdle { state.changeFilter(RecordFilter(query = "批量验证")) }
            click("全选")
            click("删除")
            waitFor("删除选中的 2 条记录？")
            click("取消")
            assertTrue(drafts.all { draft -> state.data.value.records.any { it.id == draft.id } })
            click("删除")
            click("确认删除")
            waitFor("已删除 2 条记录")
            ui.onNodeWithText("多选").assertExists()
            assertEquals(listOf(drafts.last().id), state.data.value.records.map { it.id }.filter { id -> drafts.any { it.id == id } })
        } finally {
            val remaining = state.data.value.records.map { it.id }.filter { id -> drafts.any { it.id == id } }.toSet()
            kotlinx.coroutines.runBlocking { state.repository.deleteRecords(remaining) }
        }
    }

    @Test fun combinesAcademicYearsTermsCategoriesAndLevelsBeforeSelectingAll() {
        val state = render()
        val terms = state.data.value.terms.associateBy { it.name }
        val base = RecordDraft(category = Category.VOLUNTEER.name, level = Level.SCHOOL)
        val drafts = listOf(
            base.copy(title = "组合候选甲", termId = terms.getValue("大一上").id),
            base.copy(title = "组合候选乙", termId = terms.getValue("大一下").id, category = Category.COMPETITION.name),
            base.copy(title = "组合候选丙", termId = terms.getValue("大二上").id, level = Level.FACULTY),
            base.copy(title = "组合候选丁", termId = terms.getValue("大二下").id),
            base.copy(title = "组合候选戊", termId = terms.getValue("大一上").id, category = Category.RESEARCH.name)
        ).map { it.copy(id = newId()) }
        try {
            kotlinx.coroutines.runBlocking { drafts.forEach { state.repository.save(it) } }
            ui.runOnIdle { state.changeFilter(RecordFilter(query = "组合候选")) }
            click("筛选")
            for (label in listOf("大一学年", "大二学年", "大二下", "竞赛", "志愿服务", "校级", "院级")) {
                ui.onNodeWithText(label).performScrollTo().performClick()
            }
            click("应用筛选")
            waitFor("3 条记录")
            assertEquals(drafts.take(3).map { it.id }.toSet(), state.filter.apply(state.data.value).map { it.id }.toSet())
            click("多选")
            click("全选")
            waitFor("已选 3 条")
            assertEquals(drafts.take(3).map { it.id }.toSet(), state.selectedRecordIds)
            ui.onNodeWithText("导出").assertIsEnabled()
            clickDescription("退出多选")
            click("筛选")
            click("清除筛选")
            waitFor("5 条记录")
            assertEquals("组合候选", state.filter.query)
        } finally {
            val ids = drafts.map { it.id }.toSet()
            val remaining = state.data.value.records.map { it.id }.filter { it in ids }.toSet()
            kotlinx.coroutines.runBlocking { state.repository.deleteRecords(remaining) }
        }
    }

    @Test fun storesUnprovenRecordAndKeepsAiEntryInNewRecord() {
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
            clickDescription("新增记录")
            waitFor("AI 已关闭。")
            click("打开 AI 设置")
            waitFor("测试连接")
            ui.onNodeWithText("识别并填入").assertDoesNotExist()
            clickDescription("返回")
            waitFor("AI 已关闭。")
            clickDescription("返回")
            click("我的")
            ui.onNodeWithText("AI 记录").assertDoesNotExist()
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
        ui.waitUntil(12_000) { state.ready && !state.busy }
        if (state.showWelcome) {
            click("暂不设置")
            ui.waitUntil(12_000) { !state.showWelcome }
        }
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
