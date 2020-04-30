package com.willowtree.vocable.tests

import android.app.Activity
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule


abstract class BaseTest<T : Activity> {

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Rule
    lateinit var activityRule: ActivityTestRule<T>

    @Before
    fun setup() {
        println("setup")
        activityRule = getActivityTestRule()

        if (shouldAutoLaunchActivity()) {
            activityRule.launchActivity(Intent())
        }
    }

    protected fun launchActivity() {
        runBlocking {
            activityRule.launchActivity(Intent())
        }
    }

    abstract fun getActivityTestRule(): ActivityTestRule<T>
    protected open fun shouldAutoLaunchActivity(): Boolean = true
}