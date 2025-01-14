package com.willowtree.vocable

import android.app.ActivityManager
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.google.ar.core.ArCoreApk
import com.willowtree.vocable.customviews.PointerListener
import com.willowtree.vocable.customviews.PointerView
import com.willowtree.vocable.databinding.ActivityMainBinding
import com.willowtree.vocable.facetracking.FaceTrackFragment
import com.willowtree.vocable.facetracking.FaceTrackingViewModel
import com.willowtree.vocable.settings.SettingsViewModel
import com.willowtree.vocable.utils.VocableSharedPreferences
import com.willowtree.vocable.utils.VocableTextToSpeech
import io.github.inflationx.viewpump.ViewPumpContextWrapper
import org.koin.android.ext.android.inject
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private val minOpenGlVersion = 3.0
    private val displayMetrics = DisplayMetrics()
    private var currentView: View? = null
    private lateinit var viewModel: FaceTrackingViewModel
    private var paused = false
    private lateinit var binding: ActivityMainBinding
    private val sharedPrefs: VocableSharedPreferences by inject()
    private val allViews = mutableListOf<View>()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shouldForceDisableHeadTracking = !BuildConfig.USE_HEAD_TRACKING
        val isNotSupportedDevice = !checkIsSupportedDeviceOrFinish()

        if (shouldForceDisableHeadTracking || isNotSupportedDevice) {
            return
        }

        if (supportFragmentManager.findFragmentById(R.id.face_fragment) == null && BuildConfig.USE_HEAD_TRACKING) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.face_fragment, FaceTrackFragment())
                .commitAllowingStateLoss()
        } else {
            window
                .decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                .or(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                .or(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                .or(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
                .or(View.SYSTEM_UI_FLAG_FULLSCREEN)
                .or(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }

        windowManager.defaultDisplay.getMetrics(displayMetrics)
        viewModel = ViewModelProviders.of(this).get(FaceTrackingViewModel::class.java)
        subscribeToViewModel()

        val displayListener = object : DisplayManager.DisplayListener {

            private var orientation = windowManager.defaultDisplay.rotation

            override fun onDisplayChanged(displayId: Int) {
                val newOrientation = windowManager.defaultDisplay.rotation
                // Only reset FaceTrackFragment if device is rotated 180 degrees
                when (orientation) {
                    Surface.ROTATION_0 -> {
                        if (newOrientation == Surface.ROTATION_180) {
                            resetFaceTrackFragment("${Surface.ROTATION_180}")
                        }
                    }
                    Surface.ROTATION_90 -> {
                        if (newOrientation == Surface.ROTATION_270) {
                            resetFaceTrackFragment("${Surface.ROTATION_270}")
                        }
                    }
                    Surface.ROTATION_180 -> {
                        if (newOrientation == Surface.ROTATION_0) {
                            resetFaceTrackFragment("${Surface.ROTATION_0}")
                        }
                    }
                    Surface.ROTATION_270 -> {
                        if (newOrientation == Surface.ROTATION_90) {
                            resetFaceTrackFragment("${Surface.ROTATION_90}")
                        }
                    }
                }
                orientation = newOrientation
            }

            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()
        VocableTextToSpeech.initialize(this)

        binding.mainNavHostFragment.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            allViews.clear()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ViewPumpContextWrapper.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        if (!BuildConfig.USE_HEAD_TRACKING) {
            binding.pointerView.isVisible = false
        } else {
            binding.pointerView.isVisible = sharedPrefs.getHeadTrackingEnabled()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        VocableTextToSpeech.shutdown()
    }

    private fun getErrorView(): View = binding.errorView.root

    private fun getPointerView(): PointerView = binding.pointerView

    fun getAllViews(): List<View> {
        if (allViews.isEmpty()) {
            getAllChildViews(binding.parentLayout)
            getAllFragmentViews()
        }
        return allViews
    }

    fun resetAllViews() {
        allViews.clear()
    }

    fun subscribeToViewModel() {
        viewModel.showError.observe(this, Observer {
            if (!sharedPrefs.getHeadTrackingEnabled()) {
                getPointerView().isVisible = false
                getErrorView().isVisible = false
                return@Observer
            }
            it?.let {
                if (it) {
                    (currentView as? PointerListener)?.onPointerExit()
                }
                getErrorView().isVisible = it
                getPointerView().isVisible = !it
            }
        })
        viewModel.pointerLocation.observe(this) {
            it?.let {
                updatePointer(it.x, it.y)
            }
        }
        
        settingsViewModel.headTrackingEnabled.observe(this) {
            it?.let {
                val faceFragment = supportFragmentManager.findFragmentById(R.id.face_fragment)
                if (faceFragment is FaceTrackFragment) {
                    faceFragment.enableFaceTracking(it)
                }
            }
        }
    }

    private fun getAllChildViews(viewGroup: ViewGroup) {
        viewGroup.children.forEach {
            if (it is PointerListener) {
                allViews.add(it)
            } else if (it is ViewGroup) {
                getAllChildViews(it)
            }
        }
    }

    private fun getAllFragmentViews() {
        supportFragmentManager.fragments.forEach {
            if (it is BaseFragment<*>) {
                allViews.addAll(it.getAllViews())
            }
        }
    }

    /**
     * If the device rotates 180 degrees (portrait to portrait/landscape to landscape), the
     * activity won't be destroyed and recreated. This means that the FaceTrackFragment will not
     * reset its camera positioning. The only way to reset it currently is to create a new
     * instance of the fragment and add it to the activity.
     * @param tag The tag to use for the FaceTrackFragment, should be unique to the orientation
     */
    private fun resetFaceTrackFragment(tag: String) {
        if (!supportFragmentManager.isDestroyed && supportFragmentManager.findFragmentByTag(tag) == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.face_fragment, FaceTrackFragment(), tag)
                .commitAllowingStateLoss()
        }
    }

    private fun updatePointer(x: Float, y: Float) {
        var newX = x
        var newY = y
        if (x < 0) {
            newX = 0f
        } else if (x > displayMetrics.widthPixels) {
            newX = displayMetrics.widthPixels.toFloat()
        }

        if (y < 0) {
            newY = 0f
        } else if (y > displayMetrics.heightPixels) {
            newY = displayMetrics.heightPixels.toFloat()
        }
        getPointerView().updatePointerPosition(newX, newY)
        getPointerView().bringToFront()

        if (currentView == null) {
            findIntersectingView()
        } else {
            if (!viewIntersects(currentView!!, getPointerView())) {
                (currentView as? PointerListener)?.onPointerExit()
                findIntersectingView()
            }
        }
    }

    private fun findIntersectingView() {
        currentView = null
        if (!paused) {
            getAllViews().forEach {
                if (viewIntersects(it, getPointerView())) {
                    if (it.isEnabled && it.isVisible) {
                        currentView = it
                        (currentView as PointerListener).onPointerEnter()
                        return
                    }
                }
            }
        }
    }

    private fun viewIntersects(view1: View, view2: View): Boolean {
        val coords = IntArray(2)
        view1.getLocationOnScreen(coords)
        val rect = Rect(
            coords[0],
            coords[1],
            coords[0] + view1.measuredWidth,
            coords[1] + view1.measuredHeight
        )

        val view2Coords = IntArray(2)
        view2.getLocationOnScreen(view2Coords)
        val view2Rect = Rect(
            view2Coords[0],
            view2Coords[1],
            view2Coords[0] + view2.measuredWidth,
            view2Coords[1] + view2.measuredHeight
        )
        return rect.contains(view2Rect.centerX(), view2Rect.centerY())
    }

    /**
     * Returns false and displays an error message if Sceneform can not run, true if Sceneform can run
     * on this device.
     *
     *
     * Sceneform requires Android N on the device as well as OpenGL 3.0 capabilities.
     *
     *
     * Finishes the activity if Sceneform can not run
     */
    private fun checkIsSupportedDeviceOrFinish(): Boolean {
        if (ArCoreApk.getInstance().checkAvailability(this) === ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
            Timber.e("TAG", "Augmented Faces requires ARCore.")
            Toast.makeText(this, "Augmented Faces requires ARCore", Toast.LENGTH_LONG).show()
            finish()
            return false
        }
        val openGlVersionString =
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .deviceConfigurationInfo
                .glEsVersion
        if (java.lang.Double.parseDouble(openGlVersionString) < minOpenGlVersion) {
            Timber.e("TAG", "Sceneform requires OpenGL ES 3.0 later")
            Toast.makeText(this, "Sceneform requires OpenGL ES 3.0 or later", Toast.LENGTH_LONG)
                .show()
            finish()
            return false
        }
        return true
    }
}
