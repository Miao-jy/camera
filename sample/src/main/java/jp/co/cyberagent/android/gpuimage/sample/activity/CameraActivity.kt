/*
 * Copyright (C) 2018 CyberAgent, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jp.co.cyberagent.android.gpuimage.sample.activity

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import jp.co.cyberagent.android.gpuimage.GPUImageView
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools.FilterAdjuster
import jp.co.cyberagent.android.gpuimage.sample.R
import jp.co.cyberagent.android.gpuimage.sample.utils.Camera2ControllerImpl
import jp.co.cyberagent.android.gpuimage.sample.utils.CameraController
import jp.co.cyberagent.android.gpuimage.sample.utils.doOnLayout
import jp.co.cyberagent.android.gpuimage.sample.widget.FastBlur
import jp.co.cyberagent.android.gpuimage.sample.widget.FlipCardAnimation
import jp.co.cyberagent.android.gpuimage.sample.widget.FocusSunView
import jp.co.cyberagent.android.gpuimage.util.Rotation

class CameraActivity : AppCompatActivity() {

    private val gpuImageView: GPUImageView by lazy { findViewById<GPUImageView>(R.id.surfaceView) }
    private val seekBar: SeekBar by lazy { findViewById<SeekBar>(R.id.seekBar) }
    private val coverView: ImageView by lazy { findViewById<ImageView>(R.id.img_cover) }
    private val cameraController: CameraController by lazy {
        Camera2ControllerImpl(this)
    }
    private var filterAdjuster: FilterAdjuster? = null
    private var aspectRadioIndex = 0

    private val scaleGestureDetector: ScaleGestureDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.OnScaleGestureListener {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                cameraController.changeZoom(detector.scaleFactor)
                return true
            }

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // onScaleEnd
            }
        })
    }
    
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        seekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                filterAdjuster?.adjust(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        findViewById<View>(R.id.button_choose_filter).setOnClickListener {
            GPUImageFilterTools.showDialog(this) { filter -> switchFilterTo(filter) }
        }
        findViewById<View>(R.id.button_capture).setOnClickListener {
            saveSnapshot()
        }
        findViewById<View>(R.id.img_switch_camera).run {
            setOnClickListener {
                setImgBlurCoverBackGround()
                cameraController.switchCamera()
            }
        }
        findViewById<View>(R.id.camera_flash).setOnClickListener {
            if (cameraController.isFrontCamera()) {
                Toast.makeText(this, "前置摄像头闪光灯不能点击", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (flashIsOpen) {
                cameraController.closeFlash()
            } else {
                cameraController.openFlash()
            }
            flashIsOpen = !flashIsOpen
        }
        findViewById<View>(R.id.switch_scale).run {
            setOnClickListener {
                val radioList = listOf(
                    CameraController.AspectRadio.FULL,
                    CameraController.AspectRadio.THREE_TO_FOUR,
                    CameraController.AspectRadio.NINE_TO_SIXTEEN,
                    CameraController.AspectRadio.ONE_TO_ONE
                )
                aspectRadioIndex = if (aspectRadioIndex < radioList.size) aspectRadioIndex + 1 else 0
                cameraController.changeAspectRadio(radioList[aspectRadioIndex])
                gpuImageView.setRatio(radioList[aspectRadioIndex].value)
            }
        }
        findViewById<FocusSunView>(R.id.focus_sun_view).run {
            gpuImageView.setOnTouchListener { v, motionEvent ->
                if (motionEvent.pointerCount >= 2) {
                    this.visibility = View.INVISIBLE
                    return@setOnTouchListener scaleGestureDetector.onTouchEvent(motionEvent)
                }

                when (motionEvent.action) {
                    MotionEvent.ACTION_DOWN -> {
                        this.visibility = View.VISIBLE
                        this.x = motionEvent.rawX - (this.width / 2f)
                        this.y = motionEvent.rawY - (this.height / 2f)
                        this.startCountdown()
                    }

                    MotionEvent.ACTION_UP -> {
                        val motionEventX = motionEvent.x
                        val motionEventY = motionEvent.y
                        cameraController.changeFocus(motionEventX, motionEventY)
                        v.performClick()
                    }
                }
                return@setOnTouchListener true
            }
            this.setOnExposureChangeListener(object : FocusSunView.OnExposureChangeListener {
                override fun onExposureChangeListener(exposure: Float) {
                    cameraController.changeExposure(exposure / 2)
                }
            })
        }
        gpuImageView.setRatio(cameraController.aspectRatio)
        updateGPUImageRotate()
        gpuImageView.setRenderMode(GPUImageView.RENDERMODE_CONTINUOUSLY)
        initCameraControllerObserve()
    }

    private var flashIsOpen: Boolean = false

    override fun onResume() {
        super.onResume()
        gpuImageView.doOnLayout {
            cameraController.onResume(it.width, it.height)
        }
    }

    override fun onPause() {
        cameraController.onPause()
        super.onPause()
    }

    private fun initCameraControllerObserve() {
        cameraController.onPreviewFrame = { data, width, height ->
            gpuImageView.updatePreviewFrame(data, width, height)
        }
        cameraController.onCameraStateChange = { cameraState ->
            when (cameraState) {
                CameraController.CameraState.CAMERA_DEVICE_OPENED -> {
                }

                CameraController.CameraState.CAMERA_CAPTURE_SESSION_CONFIGURED -> {
                    gpuImageView.gpuImage.deleteImage()
                    coverView.postDelayed({
                        gpuImageView.visibility = View.VISIBLE
                        coverView.setImageBitmap(null)
                    }, 500L)
                    gpuImageView.setRatio(cameraController.aspectRatio)
                    updateGPUImageRotate()
                    if (flashIsOpen) {
                        cameraController.openFlash()
                    }
                }

                CameraController.CameraState.CAMERA_CAPTURE_SESSION_CONFIGURE_FAILED -> {
                }

                CameraController.CameraState.CAMERA_DEVICE_DISCONNECTED -> {
                }

                CameraController.CameraState.CAMERA_DEVICE_ERROR -> {
                }
            }
        }
    }

    private fun setImgBlurCoverBackGround() {
        val blurBitmap = kotlin.runCatching {
            val bitmap = gpuImageView.capture()
            FastBlur.blur(bitmap, 90, true)
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()

        coverView.layoutParams.height = gpuImageView.height
        coverView.setImageBitmap(blurBitmap)
        gpuImageView.visibility = View.INVISIBLE
    }

    private fun roteYAnimation() {
        val animation = FlipCardAnimation(0f, 180f, gpuImageView.width / 2f, gpuImageView.height / 2f).apply {
            interpolator = AccelerateDecelerateInterpolator()
            duration = 1000L
            fillAfter = false
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {
//                            setImgBlurCoverBackGround()
                    val bp = Bitmap.createBitmap(gpuImageView.width, gpuImageView.height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.RED)
                    }
                    val bitmap = gpuImageView.capture()
                    coverView.layoutParams.height = gpuImageView.height
                    val blur = FastBlur.blur(bitmap, 90, true)
                    coverView.setImageBitmap(blur)
                    gpuImageView.visibility = View.INVISIBLE
                    cameraController.switchCamera()
                    gpuImageView.gpuImage.deleteImage()
                    updateGPUImageRotate()
                }

                override fun onAnimationEnd(animation: Animation?) {
                    gpuImageView.visibility = View.VISIBLE
                    coverView.postDelayed({ coverView.setImageBitmap(null) }, 300L)
                }
                override fun onAnimationRepeat(animation: Animation?) {
                    TODO("Not yet implemented")
                }
            })
        }
        coverView.startAnimation(animation)
    }

    private fun updateGPUImageRotate() {
        val rotation = getRotation(cameraController.getCameraOrientation())
        var flipHorizontal = false
        var flipVertical = false
        if (cameraController.isFrontCamera()) { // 前置摄像头需要镜像
            if (rotation == Rotation.NORMAL || rotation == Rotation.ROTATION_180) {
                flipHorizontal = true
            } else {
                flipVertical = true
            }
        }
        gpuImageView.gpuImage.setRotation(rotation, flipHorizontal, flipVertical)
    }

    private fun saveSnapshot() {
        val folderName = "GPUImage"
        val fileName = System.currentTimeMillis().toString() + ".jpg"
        gpuImageView.saveToPictures(folderName, fileName) {
            Toast.makeText(this, "$folderName/$fileName saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getRotation(orientation: Int): Rotation {
        return when (orientation) {
            90 -> Rotation.ROTATION_90
            180 -> Rotation.ROTATION_180
            270 -> Rotation.ROTATION_270
            else -> Rotation.NORMAL
        }
    }

    private fun switchFilterTo(filter: GPUImageFilter) {
        if (gpuImageView.filter == null || gpuImageView.filter!!.javaClass != filter.javaClass) {
            gpuImageView.filter = filter
            filterAdjuster = FilterAdjuster(filter)
            filterAdjuster?.adjust(seekBar.progress)
        }
    }
}
