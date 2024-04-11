package jp.co.cyberagent.android.gpuimage.sample.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Build
import android.util.Size
import android.view.Surface
import jp.co.cyberagent.android.gpuimage.sample.utils.CameraController.Companion.PREVIEW_HEIGHT
import jp.co.cyberagent.android.gpuimage.sample.utils.CameraController.Companion.PREVIEW_WIDTH
import jp.co.cyberagent.android.gpuimage.sample.utils.CameraController.Companion.ROTATION_0
import jp.co.cyberagent.android.gpuimage.sample.utils.CameraController.Companion.ROTATION_180
import jp.co.cyberagent.android.gpuimage.sample.utils.CameraController.Companion.ROTATION_270
import jp.co.cyberagent.android.gpuimage.sample.utils.CameraController.Companion.ROTATION_360
import jp.co.cyberagent.android.gpuimage.sample.utils.CameraController.Companion.ROTATION_90
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.roundToInt

private const val METERING_WEIGHT: Int = 1000
private const val AF_AREA_SCALE: Int = 5
private const val AE_AREA_SCALE: Int = 4

internal class Camera2ControllerImpl(private val activity: Activity) : CameraController {

    private inner class CameraDeviceCallback : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraInstance = camera
            startCaptureSession()
            onCameraStateChange?.invoke(CameraController.CameraState.CAMERA_DEVICE_OPENED)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraInstance = null
            onCameraStateChange?.invoke(CameraController.CameraState.CAMERA_DEVICE_DISCONNECTED)
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraInstance = null
            onCameraStateChange?.invoke(CameraController.CameraState.CAMERA_DEVICE_ERROR)
        }
    }

    private inner class CaptureStateCallback : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession) {
            onCameraStateChange?.invoke(CameraController.CameraState.CAMERA_CAPTURE_SESSION_CONFIGURE_FAILED)
        }

        override fun onConfigured(session: CameraCaptureSession) {
            if (cameraInstance == null || previewBuilder == null) {
                onCameraStateChange?.invoke(CameraController.CameraState.CAMERA_CAPTURE_SESSION_CONFIGURE_FAILED)
                return
            }
            captureSession = session
            setRepeatingRequestInSession(previewBuilder!!)
            onCameraStateChange?.invoke(CameraController.CameraState.CAMERA_CAPTURE_SESSION_CONFIGURED)
        }
    }

    private var cameraInstance: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var minZoom: Float? = null
    private var maxZoom: Float? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private val mPreviewRect: Rect get() = Rect(0, 0, preViewWidth, preViewHeight)
    private val mTransformer: CoordinateTransformer
        get() = CoordinateTransformer(
            cameraCharacteristics,
            RectF(mPreviewRect)
        )
    private val cameraManager: CameraManager by lazy {
        activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val requestManager: RequestManager by lazy {
        RequestManager()
    }

    override var preViewWidth: Int = 0
    override var preViewHeight: Int = 0
    override var cameraFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    override var aspectRatio: Float = CameraController.AspectRadio.FULL.value
    override var curZoomRadio: Float = 1f
    override var onPreviewFrame: ((data: ByteArray, width: Int, height: Int) -> Unit)? = null
    override var onCameraStateChange: ((cameraState: CameraController.CameraState) -> Unit)? = null

    private val previewBuilder: CaptureRequest.Builder?
        get() {
            val builder = kotlin.runCatching {
                cameraInstance?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            }.onFailure {
                onCameraStateChange?.invoke(CameraController.CameraState.CAMERA_DEVICE_ERROR)
            }.getOrNull() ?: return null

            imageReader?.surface?.let { builder.addTarget(it) }
            return requestManager.getPreviewBuilder(builder)
        }
    private var lastBuilder: CaptureRequest.Builder? = null

    @SuppressLint("MissingPermission")
    private fun setUpCamera() {
        val cameraId = getCameraId(cameraFacing) ?: return
        cameraManager.getCameraCharacteristics(cameraId).apply {
            cameraCharacteristics = this
            requestManager.setCharacteristics(this)
            updateZoomCharacteristics(this)
        }
        try {
            cameraManager.openCamera(cameraId, CameraDeviceCallback(), null)
        } catch (e: CameraAccessException) {
        }
    }

    private fun releaseCamera() {
        imageReader?.close()
        cameraInstance?.close()
        captureSession?.close()
        imageReader = null
        cameraInstance = null
        captureSession = null
    }

    private fun getCameraId(facing: Int): String? {
        return cameraManager.cameraIdList.find { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    private fun updateZoomCharacteristics(characteristics: CameraCharacteristics) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val zoomRatioRange =
                characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zoomRatioRange != null) {
                minZoom = zoomRatioRange.lower
                maxZoom = zoomRatioRange.upper
            }
        } else {
            minZoom = 1.0f
            maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
        }
    }

    private fun startCaptureSession() {
        val size = chooseOptimalSize()
        imageReader =
            ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader?.acquireNextImage() ?: return@setOnImageAvailableListener
                    onPreviewFrame?.invoke(image.generateNV21Data(), image.width, image.height)
                    image.close()
                }, null)
            }

        try {
            cameraInstance?.createCaptureSession(
                listOf(imageReader!!.surface),
                CaptureStateCallback(),
                null
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun chooseOptimalSize(): Size {
        if (preViewWidth == 0 || preViewHeight == 0) {
            return Size(0, 0)
        }
        val cameraId = getCameraId(cameraFacing) ?: return Size(0, 0)
        val outputSizes = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)

        val orientation = getCameraOrientation()
        val maxPreviewWidth =
            if (orientation == ROTATION_90 || orientation == ROTATION_270) preViewHeight else preViewWidth
        val maxPreviewHeight =
            if (orientation == ROTATION_90 || orientation == ROTATION_270) preViewWidth else preViewHeight

        return (getSuitableSize(outputSizes ?: emptyArray(), maxPreviewWidth, maxPreviewHeight)
            ?: Size(PREVIEW_WIDTH, PREVIEW_HEIGHT))
    }

    private fun getSuitableSize(
        sizes: Array<Size>,
        width: Int,
        height: Int
    ): Size? {
        var minDelta = Int.MAX_VALUE
        var index = 0
        for (i in sizes.indices) {
            val size = sizes[i]
            // 未明确设置比例时，采用4:3比例值
            val adjustAspectRatio =
                if (aspectRatio <= 0) CameraController.AspectRadio.THREE_TO_FOUR.value else aspectRatio
            // 先判断比例是否相等
            if (size.width * adjustAspectRatio == size.height.toFloat()) {
                val delta = abs(width - size.width)
                if (delta == 0) {
                    return size
                }
                if (minDelta > delta) {
                    minDelta = delta
                    index = i
                }
            }
        }
        return sizes.getOrNull(index)
    }

    private fun setRepeatingRequestInSession(captureRequest: CaptureRequest.Builder) {
        try {
            captureSession?.setRepeatingRequest(captureRequest.build(), null, null)
        } catch (e: Exception) {
        }
        lastBuilder = captureRequest
    }

    private fun sendCaptureRequestInSession(captureRequest: CaptureRequest.Builder) {
        try {
            captureSession?.capture(captureRequest.build(), null, null)
        } catch (e: Exception) {
        }
    }

    private fun getFocusArea(x: Float, y: Float, isFocusArea: Boolean): MeteringRectangle {
        return if (isFocusArea) {
            calcTapAreaForCamera2(x, y, preViewWidth / AF_AREA_SCALE, METERING_WEIGHT)
        } else {
            calcTapAreaForCamera2(x, y, preViewWidth / AE_AREA_SCALE, METERING_WEIGHT)
        }
    }

    private fun calcTapAreaForCamera2(
        x: Float,
        y: Float,
        areaSize: Int,
        weight: Int
    ): MeteringRectangle {
        val left = (x.toInt() - areaSize / 2).coerceIn(
            minimumValue = mPreviewRect.left,
            maximumValue = mPreviewRect.right - areaSize
        )
        val top = (y.toInt() - areaSize / 2).coerceIn(
            minimumValue = mPreviewRect.top,
            maximumValue = mPreviewRect.bottom - areaSize
        )
        val rectAfterTransformer = mTransformer.toCameraSpace(
            RectF(
                left.toFloat(),
                top.toFloat(),
                (left + areaSize).toFloat(),
                (top + areaSize).toFloat()
            )
        )
        return MeteringRectangle(
            Rect(
                rectAfterTransformer.left.roundToInt(),
                rectAfterTransformer.top.roundToInt(),
                rectAfterTransformer.right.roundToInt(),
                rectAfterTransformer.bottom.roundToInt()
            ), weight
        )
    }

    override fun updatePreViewWidthAndHeight(width: Int, height: Int) {
        preViewWidth = width
        preViewHeight = height
    }

    override fun onResume(width: Int, height: Int) {
        preViewWidth = width
        preViewHeight = height
        setUpCamera()
    }

    override fun onPause() {
        releaseCamera()
    }

    override fun switchCamera() {
        cameraFacing = when (cameraFacing) {
            CameraCharacteristics.LENS_FACING_BACK -> CameraCharacteristics.LENS_FACING_FRONT
            CameraCharacteristics.LENS_FACING_FRONT -> CameraCharacteristics.LENS_FACING_BACK
            else -> return
        }
        releaseCamera()
        setUpCamera()
    }

    override fun openFlash() {
        val builder = lastBuilder ?: previewBuilder ?: return
        setRepeatingRequestInSession(
            requestManager.getFlashRequest(builder, FlashRequestValue.FLASH_VALUE_TORCH)
        )
    }

    override fun closeFlash() {
        val builder = lastBuilder ?: previewBuilder ?: return
        setRepeatingRequestInSession(
            requestManager.getFlashRequest(builder, FlashRequestValue.FLASH_VALUE_OFF)
        )
    }

    override fun changeZoom(updateScale: Float) {
        val builder = lastBuilder ?: previewBuilder ?: return
        curZoomRadio =
            (curZoomRadio * updateScale).coerceIn(minimumValue = minZoom, maximumValue = maxZoom)
        setRepeatingRequestInSession(requestManager.getZoomRequest(builder, curZoomRadio))
    }

    override fun changeFocus(x: Float, y: Float) {
        val builder = lastBuilder ?: previewBuilder ?: return
        val focusRectangleArray = arrayOf(getFocusArea(x, y, true))
        val meteringRectangleArray = arrayOf(getFocusArea(x, y, false))
        val focusBuilder = requestManager.getFocusRequest(builder, focusRectangleArray, meteringRectangleArray)
        sendCaptureRequestInSession(focusBuilder)
        focusBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        setRepeatingRequestInSession(focusBuilder)
    }

    override fun changeAspectRadio(aspectRadio: CameraController.AspectRadio) {
        this.aspectRatio = aspectRadio.value
        releaseCamera()
        setUpCamera()
    }

    override fun changeExposure(exposureScale: Float) {
        val builder = lastBuilder ?: previewBuilder ?: return
        setRepeatingRequestInSession(requestManager.getExposureRequest(builder, exposureScale))
    }

    override fun getCameraOrientation(): Int {
        val degrees = when (activity.windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> ROTATION_0
            Surface.ROTATION_90 -> ROTATION_90
            Surface.ROTATION_180 -> ROTATION_180
            Surface.ROTATION_270 -> ROTATION_180
            else -> ROTATION_0
        }
        val cameraId = getCameraId(cameraFacing) ?: return ROTATION_0
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val orientation =
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: return ROTATION_0
        return if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (orientation + degrees) % ROTATION_360
        } else { // back-facing
            (orientation - degrees) % ROTATION_360
        }
    }
}