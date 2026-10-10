// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.magicphone.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Deterministic model + real PDF renderer, Accessibility, screenshots and Android TTS. */
@RunWith(AndroidJUnit4::class)
class GuidedExplanationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val r get() = context.runtime
    private val guide get() = r.phone!!.explanation
    private val pkg = "dev.magicphone.fixture"
    private lateinit var device: UiDevice
    private lateinit var savedSettings: Settings
    private lateinit var savedArchive: Archive
    private var selected: String? = null
    private val dir get() = File(context.getExternalFilesDir(null), "explanation-evidence").apply { mkdirs() }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, timeout: Long = 20000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(40)
        Assert.assertTrue("$label; state=${r.agent.state.value}, error=${r.agent.error.value}, armed=${r.phone?.explanation?.armed}, refreshing=${r.phone?.explanation?.refreshing}, section=${r.phone?.explanation?.section}, regions=${r.phone?.explanation?.plan?.sections?.map { it.regions.size }}", condition())
    }
    private fun text(id: Int) = context.getString(id)
    private fun click(id: Int) {
        val view = device.wait(Until.findObject(By.desc(text(id)).pkg(context.packageName)), 5000)
        Assert.assertNotNull("Missing ${text(id)}", view)
        view.click()
    }
    private fun capture(name: String) {
        // UiDevice.takeScreenshot may wait for idle long enough for a short spoken
        // section to finish. Capture the actual frame without changing service flags.
        val bitmap = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun table() {
        device.executeShellCommand("am start -W --activity-clear-task -n $pkg/.FixtureActivity --ez explanationTable true")
        Assert.assertTrue(device.wait(Until.hasObject(By.desc("PDF comparison table")), 10000))
        device.waitForIdle()
        SystemClock.sleep(350) // Drain delayed launch/layout events before direct Gateway cases.
    }
    @Before fun setup() {
        Assert.assertTrue(android.os.Build.MODEL.contains("sdk"))
        Configurator.getInstance().setUiAutomationFlags(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        device = UiDevice.getInstance(instrumentation)
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        savedSettings = r.settings.value; savedArchive = r.archive.value; selected = r.current.value
        val component = "${context.packageName}/${PhoneService::class.java.name}"
        val enabled = device.executeShellCommand("settings get secure enabled_accessibility_services").trim()
        Assert.assertTrue(component in enabled.split(':'))
        if (r.phone == null) {
            device.executeShellCommand("settings put secure enabled_accessibility_services null")
            SystemClock.sleep(300)
            device.executeShellCommand("settings put secure enabled_accessibility_services $enabled")
        }
        await("Service connected") { r.phone != null }
        main {
            r.newConversation(); r.configurePractice()
            r.saveSettings(r.settings.value.copy(onboarded = true, policy = PolicyConfig(apps = mapOf(pkg to AppRule(true, true)))))
        }
        table()
    }
    @After fun restore() {
        device.executeShellCommand("wm size reset")
        main { r.stop(); r.agent.clearView(); r.saveSettings(savedSettings); r.saveArchive(savedArchive); r.current.value = selected }
        runBlocking { r.flushHistory() }
    }
    private fun start() {
        main { r.phone!!.quickPrompt.show() }
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(context.packageName)), 5000)
        field.text = "Explain the comparison table aloud and highlight each row."
        val toggle = device.findObject(By.desc(text(R.string.explain_aloud)))
        Assert.assertNotNull(toggle)
        if (!toggle.isChecked) toggle.click()
        capture("prompt")
        device.findObject(By.text(text(R.string.send))).click()
        await("Real TTS started with visible highlights", 30000) { guide.plan != null && guide.drawing != null && r.speech.started.value }
        await("Answer saved") { r.agent.state.value == RunState.COMPLETED }
    }
    @Test fun popupCapturesPdfSpeaksAndHighlightsWithoutTouchInterception() {
        start()
        Assert.assertEquals(3, guide.plan!!.sections.size)
        Assert.assertTrue("At most one fresh tree round after keyboard dismissal", r.agent.metrics.value.modelCalls in 1..2)
        Assert.assertTrue(r.archive.value.audits.any { it.operation == "SCREENSHOT" && it.status == "captured" })
        Assert.assertTrue(r.archive.value.audits.any { it.operation == "EXPLAIN" && it.status == "explaining" })
        Assert.assertFalse(r.archive.value.audits.filter { a -> savedArchive.audits.none { it.id == a.id } }.any { it.operation in setOf("TAP", "SWIPE", "TEXT") })
        Assert.assertTrue(r.archive.value.conversations.single { it.id == r.current.value }.messages.any { it.role == "assistant" && it.text.contains("eighty-four") })
        Assert.assertNull(r.resultRequest.value)
        val params = guide.drawing!!.layoutParams as WindowManager.LayoutParams
        Assert.assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        val painted = java.util.concurrent.CountDownLatch(1)
        main { guide.drawing!!.postOnAnimation { guide.drawing?.postOnAnimation { painted.countDown() } } }
        Assert.assertTrue(painted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val bitmap = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        val region = guide.highlightBounds.single()
        var green = 0
        for (x in region.left.coerceAtLeast(0) until region.right.coerceAtMost(bitmap.width) step 2) {
            for (y in (region.top - 8).coerceAtLeast(0)..(region.top + 8).coerceAtMost(bitmap.height - 1)) {
                val c = bitmap.getPixel(x, y)
                if (Color.green(c) > 180 && Color.red(c) in 60..150 && Color.blue(c) > 140) green++
            }
        }
        File(dir, "pixel-check.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle(); Assert.assertTrue("Visible green outline aligned to table header: $region; green=$green", green > 20)
        capture("table-speaking")
        val before = r.archive.value.audits.count { it.operation == "SCREENSHOT" }
        val touch = device.findObject(By.text("Touch-through test: 0"))
        Assert.assertNotNull(touch); touch.click()
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Touch-through test: 1")), 5000))
        // Fresh capture goes through the same Gateway, with presentation windows removed.
        await("Changed content captured again") { r.archive.value.audits.count { it.operation == "SCREENSHOT" } > before }
    }
    @Test fun playbackControlsPauseSkipResumeStopAndOpenSameChat() {
        start(); val chat = r.current.value
        click(R.string.pause)
        await("Paused and markers cleared") { guide.paused && guide.drawing == null && r.speech.active.value == null }
        click(R.string.explain_next)
        await("Next section actually starts speaking") { guide.section == 1 && guide.drawing != null && r.speech.started.value }
        capture("accuracy-highlight")
        click(R.string.explain_previous)
        await("Previous section") { guide.section == 0 && r.speech.started.value }
        click(R.string.task_result_open)
        await("Same chat opened") { r.visibleChat.value == chat && !guide.armed }
        Assert.assertEquals(chat, r.current.value)
        table(); start()
        click(R.string.stop)
        await("Stop removes speech and all overlays") { !guide.armed && guide.controls == null && guide.drawing == null && r.speech.active.value == null }
        SystemClock.sleep(700)
        Assert.assertFalse(guide.armed)
    }
    @Test fun scrollingAndResizeReobserveWithoutResubmittingUserPrompt() {
        start()
        val userMessages = r.archive.value.conversations.single { it.id == r.current.value }.messages.count { it.role == "user" }
        val old = guide.sourceSnapshot
        // Scroll above the playback panel so touches go to the document.
        device.swipe(device.displayWidth / 2, device.displayHeight / 2, device.displayWidth / 2, device.displayHeight / 4, 35)
        await("Scroll refresh requested") { guide.refreshing || guide.sourceSnapshot != old }
        await("Scroll reanchored", 30000) { !guide.refreshing && guide.sourceSnapshot != old && r.agent.state.value == RunState.COMPLETED }
        val afterScroll = guide.sourceSnapshot
        device.executeShellCommand("wm size 1600x1800")
        await("Resize reanchored", 30000) { !guide.refreshing && guide.sourceSnapshot != afterScroll && r.agent.state.value == RunState.COMPLETED }
        Assert.assertEquals(userMessages, r.archive.value.conversations.single { it.id == r.current.value }.messages.count { it.role == "user" })
        capture("resized-explanation")
    }
    @Test fun stopDuringRefreshCancelsLaterPlayback() {
        start()
        main { guide.screenChanged(); r.stop() }
        SystemClock.sleep(1300)
        Assert.assertFalse(guide.armed); Assert.assertNull(guide.controls); Assert.assertNull(r.speech.active.value)
    }
    @Test fun clarificationShowsReplyBubbleAndCanResumeExplanation() {
        start()
        main { r.agent.question.value = "Which part should I explain?"; r.agent.state.value = RunState.WAITING_USER }
        await("Question replaces player and stops speech") {
            guide.armed && guide.plan == null && guide.controls == null && guide.drawing == null &&
                r.speech.active.value == null && r.inputRequest.value != null
        }
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Which part should I explain?")), 5000))
        main { r.agent.question.value = ""; r.agent.state.value = RunState.PLANNING; r.gateway.start(); r.phone!!.hideInputBubble() }
        presentFresh { Explanation(listOf(ExplanationSection("Now I will explain the requested detail."))) }
        await("Local opt-in survives clarification") { guide.plan != null && r.speech.started.value }
    }
    @Test fun policyAndMissingOptInCannotStartNarration() {
        main { r.gateway.start() }
        val screen = runBlocking { JsonCodec.decodeFromString(Screen.serializer(), r.gateway.run(Action(Op.OBSERVE, pkg)).content) }
        val action = Action(Op.EXPLAIN, pkg, screen.id, explanation = Explanation(listOf(ExplanationSection("Synthetic text"))))
        runBlocking {
            try { r.gateway.run(action); Assert.fail("Must require Explain aloud") }
            catch (e: SafeFailure) { Assert.assertEquals("explanation_not_enabled", e.code) }
        }
        Assert.assertNull(r.speech.active.value)
        main { guide.arm(pkg) {}; r.saveSettings(r.settings.value.copy(policy = PolicyConfig())) }
        runBlocking {
            try { r.gateway.run(action); Assert.fail("App observation must be allowed") }
            catch (e: SafeFailure) { Assert.assertEquals("app_not_allowed", e.code) }
        }
        Assert.assertNull(r.speech.active.value)
    }

    @Test fun greekSpeechAdvancesAutomaticallyFinishesAndCanReplay() {
        main { guide.arm(pkg) {}; r.gateway.start() }
        val plan = Explanation(listOf(ExplanationSection("Αυτή είναι η πρώτη γραμμή του πίνακα.", listOf(ExplanationRegion(100, 100, 800, 180))),
            ExplanationSection("Η εξήγηση ολοκληρώθηκε.")))
        presentFresh { plan }
        await("Greek TTS starts") { r.speech.started.value }
        await("Speech callbacks advance and finish both sections", 30000) { guide.section == 2 && r.speech.active.value == null }
        Assert.assertNull(guide.drawing)
        Assert.assertTrue(device.wait(Until.hasObject(By.text(text(R.string.explain_finished))), 5000))
        click(R.string.explain_resume)
        await("Replay starts from section one") { guide.section == 0 && r.speech.started.value }
        device.sleep()
        await("Screen off ends playback") { !guide.armed && r.speech.active.value == null }
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
    }

    @Test fun liveModelExplainsPixelOnlyPdfWithActualSpeechAndRegions() {
        Assume.assumeTrue("Account owner opted in", InstrumentationRegistry.getArguments().getString("liveChatGpt") == "true")
        val profile = savedSettings.profiles.firstOrNull { it.id == savedSettings.selected && it.kind == ProviderKind.CHATGPT }
            ?: savedSettings.profiles.single { it.kind == ProviderKind.CHATGPT }
        main {
            r.saveSettings(savedSettings.copy(selected = profile.id, onboarded = true, policy = PolicyConfig(apps = mapOf(pkg to AppRule(observe = true)))))
            r.phone!!.quickPrompt.show()
        }
        device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(context.packageName)), 5000).text =
            "Explain the visible PDF in English with short spoken phrases and precise highlights. First explain 84.2% while highlighting only that number, then 85.5% while highlighting that number, then compare both. Next explain the equation below, pointing out E, m and c squared individually as you describe them. Finally summarize time and samples. These are synthetic practice values. Stay on this screen."
        val toggle = device.findObject(By.desc(text(R.string.explain_aloud)))
        if (!toggle.isChecked) toggle.click()
        device.findObject(By.text(text(R.string.send))).click()
        await("Live model produces explanation and starts speech", 180000) {
            (guide.plan != null && guide.drawing != null && r.speech.started.value) || r.agent.state.value == RunState.FAILED
        }
        Assert.assertNotEquals("Live request error=${r.agent.error.value}", RunState.FAILED, r.agent.state.value)
        Assert.assertTrue("Model supplied visual regions", guide.plan!!.sections.any { it.regions.isNotEmpty() })
        await("Transcript persisted", 30000) { r.agent.state.value == RunState.COMPLETED }
        Assert.assertTrue("Distinct phrases for values and equation terms", guide.plan!!.sections.size >= 6)
        Assert.assertTrue("Model returned focused regions for individual numbers/terms",
            guide.plan!!.sections.flatMap { it.regions }.count { it.right - it.left < 350 } >= 3)
        val calls = r.agent.metrics.value.modelCalls
        Assert.assertTrue(calls > 0)
        Assert.assertTrue(r.archive.value.audits.any { it.operation == "SCREENSHOT" && it.status == "captured" })
        Assert.assertTrue(r.archive.value.audits.any { it.operation == "EXPLAIN" && it.status == "explaining" })
        val painted = java.util.concurrent.CountDownLatch(1)
        main { guide.drawing!!.postOnAnimation { guide.drawing?.postOnAnimation { painted.countDown() } } }
        Assert.assertTrue(painted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        capture("live-pdf-explanation")
        File(dir, "live-fixture-transcript.txt").writeText(guide.plan!!.transcript)
        println("Live fixture explanation verified: modelCalls=$calls; sections=${guide.plan!!.sections.size}; regions=${guide.plan!!.sections.sumOf { it.regions.size }}")
    }

    private fun region(screen: Screen, label: String, style: String): ExplanationRegion {
        val b = screen.nodes.single { it.label == label }.bounds
        val c = screen.captureBounds
        return ExplanationRegion((b.left-c.left)*1000/(c.right-c.left), (b.top-c.top)*1000/(c.bottom-c.top),
            (b.right-c.left)*1000/(c.right-c.left), (b.bottom-c.top)*1000/(c.bottom-c.top), style)
    }
    private fun presentFresh(build: (Screen) -> Explanation) = runBlocking {
        // Android can finish a layout after am start returns. Just as Agent does, observe
        // again on stale context; no external action or speech has been dispatched yet.
        repeat(4) {
            val screen = JsonCodec.decodeFromString(Screen.serializer(), r.gateway.run(Action(Op.OBSERVE, pkg)).content)
            try { r.gateway.run(Action(Op.EXPLAIN, pkg, screen.id, explanation = build(screen))); return@runBlocking }
            catch (e: SafeFailure) { if (e.code != "stale_target") throw e }
        }
        Assert.fail("Fixture never settled for an explanation")
    }
    private fun pdfRegion(screen: Screen, x1: Int, y1: Int, x2: Int, y2: Int): ExplanationRegion {
        val equation = y1 >= 420
        val b = screen.nodes.single { it.label == if (equation) "PDF equation" else "PDF comparison table" }.bounds
        val c = screen.captureBounds
        fun x(value: Int) = (b.left + (b.right-b.left)*value/600-c.left)*1000/(c.right-c.left)
        fun y(value: Int) = (b.top + (b.bottom-b.top)*(value - if (equation) 420 else 0)/(if (equation) 180 else 420)-c.top)*1000/(c.bottom-c.top)
        return ExplanationRegion(x(x1), y(y1), x(x2), y(y2), "highlight")
    }
    @Test fun spokenPhrasesMoveFromIndividualNumbersToEquationTerms() {
        main { guide.arm(pkg) {}; r.gateway.start() }
        presentFresh { screen -> Explanation(listOf(
            ExplanationSection("Method A has an accuracy of eighty-four point two percent.", listOf(pdfRegion(screen, 210, 160, 310, 192))),
            ExplanationSection("Method B has an accuracy of eighty-five point five percent.", listOf(pdfRegion(screen, 398, 160, 500, 192))),
            ExplanationSection("Now look below the table. The letter E represents energy.", listOf(pdfRegion(screen, 22, 460, 53, 505))),
            ExplanationSection("The term c squared means the speed of light squared.", listOf(pdfRegion(screen, 114, 460, 155, 505))),
        )) }
        val visited = mutableListOf<Rect>()
        for (cue in 0..3) {
            await("Spoken cue $cue begins with its own highlight", 30000) {
                guide.section == cue && guide.drawing != null && r.speech.started.value
            }
            visited += guide.highlightBounds.single()
            val painted = java.util.concurrent.CountDownLatch(1)
            main { guide.drawing!!.postOnAnimation { guide.drawing?.postOnAnimation { painted.countDown() } } }
            Assert.assertTrue(painted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val bitmap = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
            val b = visited.last()
            var glyphs = 0
            var mint = 0
            for (x in b.left.coerceAtLeast(0) until b.right.coerceAtMost(bitmap.width))
                for (y in b.top.coerceAtLeast(0) until b.bottom.coerceAtMost(bitmap.height)) {
                    val color = bitmap.getPixel(x, y)
                    if (Color.red(color) < 160 && Color.green(color) < 210 && Color.blue(color) < 180) glyphs++
                    if (Color.green(color) > Color.red(color) + 20 && Color.green(color) > Color.blue(color) + 10) mint++
                }
            bitmap.recycle()
            Assert.assertTrue("Cue $cue visibly highlights its printed value or symbol", glyphs > 30 && mint > 100)
            capture("spoken-cue-$cue")
            if (cue < 3) await("Speech automatically advances past cue $cue", 30000) { guide.section > cue }
        }
        Assert.assertTrue("Separate numeric cells", visited[1].left > visited[0].right)
        Assert.assertTrue("Then equation below the table", visited[2].top > visited[1].bottom)
        Assert.assertTrue("Then the c-squared term", visited[3].left > visited[2].right)
        Assert.assertTrue("Each number/term has a tight region", visited.all { it.right-it.left < device.displayWidth/3 })
        await("Final spoken cue completes") { guide.section == 4 && r.speech.active.value == null }
    }
    @Test fun textAndEquationStayReadableUnderTranslucentHighlightAndUnderline() {
        device.executeShellCommand("am start -W --activity-clear-task -n $pkg/.FixtureActivity --ez explanationText true")
        Assert.assertTrue(device.wait(Until.hasObject(By.text("E = mc²")), 5000))
        device.waitForIdle()
        SystemClock.sleep(350) // Allow delayed Accessibility content events from fixture launch to drain.
        main { guide.arm(pkg) {}; r.gateway.start() }
        presentFresh { screen -> Explanation(listOf(ExplanationSection(
            "The highlighted equation says that energy equals mass times the speed of light squared. The underlined sentence states the same relationship in ordinary words. These visual marks follow this spoken section and leave the original text readable.",
            listOf(region(screen, "E = mc²", "highlight"), region(screen, "Energy equals mass times the speed of light squared.", "underline"))))) }
        await("Equation and text highlighted during real speech") { guide.highlightBounds.size == 2 && guide.drawing != null && r.speech.started.value }
        val painted = java.util.concurrent.CountDownLatch(1)
        main { guide.drawing!!.postOnAnimation { guide.drawing?.postOnAnimation { painted.countDown() } } }
        Assert.assertTrue(painted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val bitmap = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        val b = guide.highlightBounds[0]
        var mint = 0; var darkText = 0
        for (x in b.left.coerceAtLeast(0) until b.right.coerceAtMost(bitmap.width) step 2)
            for (y in b.top.coerceAtLeast(0) until b.bottom.coerceAtMost(bitmap.height) step 2) {
                val c = bitmap.getPixel(x, y)
                if (Color.green(c) > Color.red(c) + 20 && Color.green(c) > Color.blue(c) + 10) mint++
                if (Color.red(c) < 160 && Color.green(c) < 210 && Color.blue(c) < 180) darkText++
            }
        bitmap.recycle()
        capture("equation-and-text")
        Assert.assertTrue("Translucent mint fill visible", mint > 100)
        Assert.assertTrue("Equation glyphs still readable through fill", darkText > 20)
        click(R.string.pause)
        Assert.assertNull(guide.drawing)
    }
    @Test fun highlightsWorkOnOrdinaryAppUiAndYieldDuringGatewayReads() {
        device.executeShellCommand("am start -W --activity-clear-task -n $pkg/.FixtureActivity")
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Counter: 0")), 5000))
        main { guide.arm(pkg) {}; r.gateway.start() }
        presentFresh { screen -> Explanation(listOf(ExplanationSection(
            "The counter in this ordinary app screen currently shows zero. This example uses native Android controls instead of a PDF page. You can still interact with the app underneath the visual pointer.", listOf(region(screen, "Counter: 0", "pointer"))))) }
        await("Native app highlighted") { guide.drawing != null && r.speech.started.value }
        capture("native-screen-pointer")
        runBlocking {
            val observed = JsonCodec.decodeFromString(Screen.serializer(), r.gateway.run(Action(Op.OBSERVE, pkg)).content)
            Assert.assertTrue(observed.nodes.any { it.label == "Counter: 0" })
            Assert.assertFalse(observed.mixed)
            Assert.assertNotNull(r.gateway.run(Action(Op.SCREENSHOT, pkg, observed.id)).image)
        }
        await("Highlights restored after clean capture") { guide.drawing != null && guide.controls != null }
    }
}
