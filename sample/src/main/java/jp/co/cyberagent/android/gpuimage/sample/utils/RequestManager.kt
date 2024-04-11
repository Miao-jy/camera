package jp.co.cyberagent.android.gpuimage.sample.utils

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureRequest.Builder
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build

internal enum class FlashRequestValue {
    FLASH_VALUE_ON,
    FLASH_VALUE_OFF,
    FLASH_VALUE_AUTO,
    FLASH_VALUE_TORCH
}

internal class RequestManager {

    private var characteristics: CameraCharacteristics? = null

    private fun getValidAFMode(targetMode: Int): Int? {
        val allAFMode =
            characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return null
        for (mode in allAFMode) {
            if (mode == targetMode) {
                return targetMode
            }
        }
        return allAFMode.getOrNull(0)
    }

    private fun getValidAntiBandingMode(targetMode: Int): Int? {
        val allABMode =
            characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
                ?: return null
        for (mode in allABMode) {
            if (mode == targetMode) {
                return targetMode
            }
        }
        return allABMode.getOrNull(0)
    }

    private fun isFlashSupport(): Boolean {
        val support = characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
        return support != null && support
    }

    fun setCharacteristics(characteristics: CameraCharacteristics) {
        this.characteristics = characteristics
    }

    fun getPreviewBuilder(builder: Builder): Builder {
        val afMode = getValidAFMode(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        val antiBMode = getValidAntiBandingMode(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antiBMode)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        return builder
    }

    fun getFlashRequest(builder: Builder, value: FlashRequestValue): Builder {
        if (!isFlashSupport()) {
            return builder
        }
        when (value) {
            FlashRequestValue.FLASH_VALUE_ON -> {
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                )
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
            }

            FlashRequestValue.FLASH_VALUE_OFF -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            FlashRequestValue.FLASH_VALUE_AUTO -> {
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                )
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
            }

            FlashRequestValue.FLASH_VALUE_TORCH -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
        }
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        return builder
    }

    fun getZoomRequest(builder: Builder, zoomValue: Float): Builder {
        if (zoomValue <= 0f) {
            return builder
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomValue)
        } else {
            val sensorRect: Rect = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return builder
            var left = sensorRect.width() / 2
            var right = left
            var top = sensorRect.height() / 2
            var bottom = top
            val hwidth: Int = (sensorRect.width() / (2.0 * zoomValue)).toInt()
            val hheight: Int = (sensorRect.height() / (2.0 * zoomValue)).toInt()
            left -= hwidth
            right += hwidth
            top -= hheight
            bottom += hheight
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, right, bottom))
        }
        return builder
    }

    fun getFocusRequest(
        builder: Builder,
        focusRectangleArray: Array<MeteringRectangle>,
        meteringRectangleArray: Array<MeteringRectangle>
    ): Builder {
        return builder.apply {
            set(CaptureRequest.CONTROL_AF_REGIONS, focusRectangleArray)
            set(CaptureRequest.CONTROL_AE_REGIONS, meteringRectangleArray)
            set(CaptureRequest.CONTROL_AF_MODE, getValidAFMode(CaptureRequest.CONTROL_AF_MODE_AUTO))
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        }
    }

    fun getExposureRequest(builder: Builder, scale: Float): Builder {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        val range = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return builder
        val compensation = if (scale < 0) {
            (0 - range.lower) * scale
        } else {
            range.upper * scale
        }.toInt()
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
        return builder
    }

}
