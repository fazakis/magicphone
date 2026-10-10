// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.app.UiAutomation
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.magicphone.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import java.util.Base64

/** Dedicated emulator only. Two synthetic apps; all model-equivalent actions use Gateway. */
@RunWith(AndroidJUnit4::class)
class WindowInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val r get() = context.runtime
    private val pkg = "dev.magicphone.fixture"
    private val foreign = "dev.magicphone.windowfixture"
    private lateinit var device: UiDevice
    private lateinit var settings: Settings
    private lateinit var archive: Archive
    private var selected: String? = null
    private var splitTask: String? = null
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime()+15000
        while (!check() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(40)
        Assert.assertTrue(message,check())
    }
    private fun inspect(): Screen { var s: Screen?=null; main { s=r.phone!!.inspect(pkg) }; return s!! }
    private suspend fun observe(): Screen = JsonCodec.decodeFromString(Screen.serializer(),r.gateway.run(Action(Op.OBSERVE,pkg)).content)
    private fun cover(left:Int,top:Int,width:Int=400,height:Int=350) {
        device.executeShellCommand("am start -W -n $foreign/.WindowActivity --ei left $left --ei top $top --ei width $width --ei height $height")
        Assert.assertTrue(device.wait(Until.hasObject(By.pkg(foreign)),10000)); SystemClock.sleep(300)

    }
    @Before fun setup() {
        Assert.assertTrue(android.os.Build.MODEL.contains("sdk"))
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        device=UiDevice.getInstance(instrumentation)
        // Fixture discovery uses the emulator shell: this helper intentionally has no launcher
        // entry, so target-app package visibility is not a reliable installation check.
        Assume.assumeTrue("Install the separate windowfixture APK to run window interaction tests",
            device.executeShellCommand("pm path $foreign").trim().startsWith("package:"))
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        settings=r.settings.value;archive=r.archive.value;selected=r.current.value
        val component="${context.packageName}/${PhoneService::class.java.name}"
        val enabled=device.executeShellCommand("settings get secure enabled_accessibility_services").trim().split(":")
        Assert.assertTrue(component in enabled)
        if(r.phone==null) {
            device.executeShellCommand("settings put secure enabled_accessibility_services ${enabled.filter { it!=component }.joinToString(":").ifEmpty { "null" }}")
            SystemClock.sleep(400)
            device.executeShellCommand("settings put secure enabled_accessibility_services ${enabled.joinToString(":")}")
        }
        await("Accessibility connected") { r.connected.value }
        main { r.newConversation();r.saveSettings(settings.copy(onboarded=true,policy=PolicyConfig(apps=mapOf(pkg to AppRule(true,true)))));r.gateway.start() }
        device.executeShellCommand("am force-stop $foreign")
        device.executeShellCommand("am start -W --activity-clear-task -n $pkg/.FixtureActivity")
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Counter: 0")),10000))
        // Automatic action approval for the fixture only; foreign app remains explicitly denied.
        main { r.saveSettings(r.settings.value.copy(policy=PolicyConfig(allowAllApps=true,apps=mapOf(foreign to AppRule(deny=true))))) }
    }
    @After fun restore() {
        if (!::settings.isInitialized) return
        splitTask?.let { device.executeShellCommand("dumpsys activity service com.android.systemui/.SystemUIService WMShell splitscreen exitSplitScreen $it") }
        device.executeShellCommand("am force-stop $foreign")
        main { r.stop();r.agent.clearView();r.saveSettings(settings);r.saveArchive(archive);r.current.value=selected }
        runBlocking { r.flushHistory() }
        device.pressBack()
    }
    @Test fun unfocusedVisibleAppCanBeReadFocusedAndTypedWithoutForeignContent() = runBlocking {
        cover(device.displayWidth/2,device.displayHeight*2/3)
        val s=observe()
        Assert.assertFalse(s.focused);Assert.assertTrue(s.readable)
        Assert.assertTrue(s.visibleRegions.isNotEmpty());Assert.assertFalse(s.nodes.any { it.label.contains("FOREIGN") })
        val editor=s.nodes.first { it.editable }
        try { r.gateway.run(Action(Op.TEXT,pkg,s.id,editor.ref,"too early"));Assert.fail("Must focus first") }
        catch(e:SafeFailure) { Assert.assertEquals("window_not_focused",e.code) }
        val add=s.nodes.first { it.label.startsWith("Add one") }
        Assert.assertEquals("dispatched",r.gateway.run(Action(Op.TAP,pkg,s.id,add.ref)).status)
        // A foreign Activity dialog can retain focus after an outside tap. Use the
        // documented OPEN recovery, never issue a second blind tap into its background.
        if (!inspect().focused) r.gateway.run(Action(Op.OPEN,pkg))
        await("Target focused after tap or explicit bring-forward") { inspect().focused }
        var fresh=observe()
        if(fresh.nodes.any { it.label=="Counter: 0" }) {
            r.gateway.run(Action(Op.TAP,pkg,fresh.id,fresh.nodes.first { it.label.startsWith("Add one") }.ref));fresh=observe()
        }
        Assert.assertTrue(fresh.nodes.any { it.label=="Counter: 1" })
        r.gateway.run(Action(Op.TEXT,pkg,fresh.id,fresh.nodes.first { it.editable }.ref,"Visible window works"))
        Assert.assertTrue(observe().nodes.any { it.label=="Visible window works" })
        println("Window test: background observation, physical focus tap, counter and text verified")
    }
    @Test fun overlappingForeignPixelsAreMaskedAndCoveredTargetsRejected() = runBlocking {
        val before=observe();val button=before.nodes.first { it.label.startsWith("Add one") }
        var x=(button.bounds.left+button.bounds.right)/2;var y=(button.bounds.top+button.bounds.bottom)/2
        cover(x-100,y-80,400,400)
        val s=observe();Assert.assertTrue(s.readable)
        val masked=inspect().protectedRects.maxBy { (it.right-it.left)*(it.bottom-it.top) }
        x=(masked.left+masked.right)/2;y=(masked.top+masked.bottom)/2
        println("Mask geometry=$masked; target=${button.bounds}; diagnostics=${r.phone!!.inspectionDiagnostics}")
        Assert.assertFalse(s.nodes.any { it.label.startsWith("Add one") || it.label.contains("FOREIGN") })
        Assert.assertTrue(s.protectedRects.isEmpty()) // Observation does not export internal control rectangles.
        try { r.gateway.run(Action(Op.TAP,pkg,s.id,x=x,y=y));Assert.fail("Covered tap") }
        catch(e:SafeFailure) { Assert.assertEquals("protected_control",e.code) }
        val image=r.gateway.run(Action(Op.SCREENSHOT,pkg,s.id)).image!!
        val bytes=Base64.getDecoder().decode(image.substringAfter(','));val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        val pixel=bitmap.getPixel(x-s.captureBounds.left,y-s.captureBounds.top)
        Assert.assertTrue("Foreign pixels blacked out",android.graphics.Color.red(pixel)<25 && android.graphics.Color.green(pixel)<25 && android.graphics.Color.blue(pixel)<25)
        bitmap.recycle()
        try { r.gateway.run(Action(Op.OBSERVE,foreign));Assert.fail("Foreign app is denied") }
        catch(e:SafeFailure) { Assert.assertEquals("app_not_allowed",e.code) }
        println("Window test: overlapping app masked, covered tap and denied app blocked")
    }
    @Test fun movingOverlayInvalidatesOldTargetButNewVisibleTargetWorks() = runBlocking {
        cover(device.displayWidth/2,device.displayHeight*2/3)
        val old=observe();val add=old.nodes.first { it.label.startsWith("Add one") }
        cover(device.displayWidth/2,device.displayHeight/2)
        // Includes the API30 stale window-list regression: refreshed root geometry
        // must invalidate the old snapshot even before cached window bounds catch up.
        await("Moved window geometry reported") { inspect().visibleRegions != old.visibleRegions }
        try { r.gateway.run(Action(Op.TAP,pkg,old.id,add.ref));Assert.fail("Old geometry") }
        catch(e:SafeFailure) { Assert.assertEquals("stale_target",e.code) }
        val fresh=observe()
        Assert.assertEquals("dispatched",r.gateway.run(Action(Op.TAP,pkg,fresh.id,fresh.nodes.first { it.label.startsWith("Add one") }.ref)).status)
        if (!inspect().focused) r.gateway.run(Action(Op.OPEN,pkg))
        await("Fresh target can be brought forward") { inspect().focused }
    }
    @Test fun splitScreenFocusTapStaysInTheChosenPane() = runBlocking {
        Assume.assumeTrue("WMShell split setup requires API31+", android.os.Build.VERSION.SDK_INT >= 31)
        val tasks=device.executeShellCommand("dumpsys activity activities")
        val task=Regex("Task\\{[^\\n]* #([0-9]+)[^\\n]*dev\\.magicphone\\.fixture").find(tasks)?.groupValues?.get(1)
            ?: throw AssertionError("Fixture task missing")
        splitTask=task
        device.executeShellCommand("am start -W -n $foreign/.PaneActivity")
        device.executeShellCommand("dumpsys activity service com.android.systemui/.SystemUIService WMShell splitscreen moveToSideStage $task 0")
        await("Split pane appears") { inspect().let { it.readable &&
            ((it.windowBounds.right-it.windowBounds.left)<it.width*0.9 || (it.windowBounds.bottom-it.windowBounds.top)<it.height*0.9) } }
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Focus other pane")),10000))
        device.findObject(By.text("Focus other pane")).click()
        await("Other pane has input focus") { !inspect().focused }
        val before=observe()
        Assert.assertEquals("captured",r.gateway.run(Action(Op.SCREENSHOT,pkg,before.id)).status)
        Assert.assertFalse(before.nodes.any { it.label.contains("other pane") })
        val add=before.nodes.first { it.label.startsWith("Add one") }
        r.gateway.run(Action(Op.TAP,pkg,before.id,add.ref))
        await("Physical tap focuses split target") { inspect().focused }
        var after=observe()
        if(after.nodes.any { it.label=="Counter: 0" }) {
            r.gateway.run(Action(Op.TAP,pkg,after.id,after.nodes.first { it.label.startsWith("Add one") }.ref));after=observe()
        }
        Assert.assertTrue(after.nodes.any { it.label=="Counter: 1" })
        val other=device.findObject(By.text("Focus other pane")).visibleCenter
        try { r.gateway.run(Action(Op.TAP,pkg,after.id,x=other.x,y=other.y));Assert.fail("Another pane cannot be targeted through this app") }
        catch(e:SafeFailure) { Assert.assertEquals("protected_control",e.code) }
        val directory=java.io.File(context.getExternalFilesDir(null),"windows-029").apply { mkdirs() }
        device.takeScreenshot(java.io.File(directory,"split-screen-verified.png"))
        println("Window test: actual split panes, cropped capture, focus transfer and counter verified")
    }

}
