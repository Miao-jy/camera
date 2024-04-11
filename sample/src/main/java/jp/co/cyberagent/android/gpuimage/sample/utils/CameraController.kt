package jp.co.cyberagent.android.gpuimage.sample.utils

import android.hardware.camera2.CameraCharacteristics

internal interface CameraController {

    companion object {
        const val PREVIEW_WIDTH: Int = 480
        const val PREVIEW_HEIGHT: Int = 640
        const val ROTATION_0: Int = 0
        const val ROTATION_90: Int = 90
        const val ROTATION_180: Int = 180
        const val ROTATION_270: Int = 270
        const val ROTATION_360: Int = 360
    }

    enum class CameraState {
        CAMERA_DEVICE_OPENED,
        CAMERA_CAPTURE_SESSION_CONFIGURED,
        CAMERA_CAPTURE_SESSION_CONFIGURE_FAILED,
        CAMERA_DEVICE_DISCONNECTED,
        CAMERA_DEVICE_ERROR
    }

    enum class AspectRadio(val value: Float) {
        FULL(0f),
        THREE_TO_FOUR(3f / 4),
        NINE_TO_SIXTEEN(9f / 16),
        ONE_TO_ONE(1f / 1),
    }

    var preViewWidth: Int
    var preViewHeight: Int
    var cameraFacing: Int
    var aspectRatio: Float
    var curZoomRadio: Float
    var onPreviewFrame: ((data: ByteArray, width: Int, height: Int) -> Unit)?
    var onCameraStateChange: ((cameraState: CameraState) -> Unit)?

    fun updatePreViewWidthAndHeight(width: Int, height: Int)

    fun onResume(width: Int, height: Int)

    fun onPause()

    fun switchCamera()

    fun openFlash()

    fun closeFlash()

    fun changeZoom(updateScale: Float)

    fun changeFocus(x: Float, y: Float)

    fun changeAspectRadio(aspectRadio: AspectRadio)

    fun changeExposure(exposureScale: Float)

    fun getCameraOrientation(): Int

    fun isFrontCamera() = this.cameraFacing == CameraCharacteristics.LENS_FACING_FRONT
}