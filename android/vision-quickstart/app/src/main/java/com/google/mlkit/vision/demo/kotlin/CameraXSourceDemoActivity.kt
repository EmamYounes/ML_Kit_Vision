/*
 * Copyright 2021 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mlkit.vision.demo.kotlin

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.util.Size
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import com.google.android.gms.common.annotation.KeepName
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.camera.CameraSourceConfig
import com.google.mlkit.vision.camera.CameraXSource
import com.google.mlkit.vision.camera.DetectionTaskCallback
import com.google.mlkit.vision.demo.GraphicOverlay
import com.google.mlkit.vision.demo.InferenceInfoGraphic
import com.google.mlkit.vision.demo.R
import com.google.mlkit.vision.demo.kotlin.objectdetector.ObjectGraphic
import com.google.mlkit.vision.demo.preference.PreferenceUtils
import com.google.mlkit.vision.demo.preference.SettingsActivity
import com.google.mlkit.vision.demo.preference.SettingsActivity.LaunchSource
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Objects

/** Live preview demo app for ML Kit APIs using CameraXSource API. */
@KeepName
@RequiresApi(VERSION_CODES.LOLLIPOP)
class CameraXSourceDemoActivity : AppCompatActivity(), CompoundButton.OnCheckedChangeListener {
    private var previewView: PreviewView? = null
    private var graphicOverlay: GraphicOverlay? = null
    private var needUpdateGraphicOverlayImageSourceInfo = false
    private var lensFacing: Int = CameraSourceConfig.CAMERA_FACING_BACK
    private var cameraXSource: CameraXSource? = null
    private var customObjectDetectorOptions: CustomObjectDetectorOptions? = null
    private var targetResolution: Size? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        setContentView(R.layout.activity_vision_cameraxsource_demo)
        previewView = findViewById(R.id.preview_view)
        if (previewView == null) {
            Log.d(TAG, "previewView is null")
        }
        graphicOverlay = findViewById(R.id.graphic_overlay)
        if (graphicOverlay == null) {
            Log.d(TAG, "graphicOverlay is null")
        }
        val facingSwitch = findViewById<ToggleButton>(R.id.facing_switch)
        facingSwitch.setOnCheckedChangeListener(this)
        val settingsButton = findViewById<ImageView>(R.id.settings_button)
        settingsButton.setOnClickListener {
            val intent = Intent(applicationContext, SettingsActivity::class.java)
            intent.putExtra(SettingsActivity.EXTRA_LAUNCH_SOURCE, LaunchSource.CAMERAXSOURCE_DEMO)
            startActivity(intent)
        }
    }

    override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
        lensFacing = if (lensFacing == CameraSourceConfig.CAMERA_FACING_FRONT) {
            CameraSourceConfig.CAMERA_FACING_BACK
        } else {
            CameraSourceConfig.CAMERA_FACING_FRONT
        }
        createThenStartCameraXSource()
    }

    public override fun onResume() {
        super.onResume()
        if (cameraXSource != null &&
            PreferenceUtils.getCustomObjectDetectorOptionsForLivePreview(this, localModel)
                .equals(customObjectDetectorOptions) &&
            PreferenceUtils.getCameraXTargetResolution(
                applicationContext,
                lensFacing
            ) != null &&
            (Objects.requireNonNull(
                PreferenceUtils.getCameraXTargetResolution(applicationContext, lensFacing)
            ) == targetResolution)
        ) {
            cameraXSource!!.start()
        } else {
            createThenStartCameraXSource()
        }
    }

    override fun onPause() {
        super.onPause()
        if (cameraXSource != null) {
            cameraXSource!!.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (cameraXSource != null) {
            cameraXSource!!.stop()
        }
    }

    private fun createThenStartCameraXSource() {
        if (cameraXSource != null) {
            cameraXSource!!.close()
        }
        customObjectDetectorOptions =
            PreferenceUtils.getCustomObjectDetectorOptionsForLivePreview(
                getApplicationContext(),
                localModel
            )
        val objectDetector: ObjectDetector =
            ObjectDetection.getClient(customObjectDetectorOptions!!)
        var detectionTaskCallback: DetectionTaskCallback<List<DetectedObject>> =
            DetectionTaskCallback<List<DetectedObject>> { detectionTask ->
                detectionTask
                    .addOnSuccessListener { results -> onDetectionTaskSuccess(results) }
                    .addOnFailureListener { e -> onDetectionTaskFailure(e) }
            }
        val builder: CameraSourceConfig.Builder =
            CameraSourceConfig.Builder(
                applicationContext,
                objectDetector!!,
                detectionTaskCallback
            )
                .setFacing(lensFacing)
        targetResolution =
            PreferenceUtils.getCameraXTargetResolution(getApplicationContext(), lensFacing)
        if (targetResolution != null) {
            builder.setRequestedPreviewSize(targetResolution!!.width, targetResolution!!.height)
        }
        cameraXSource = CameraXSource(builder.build(), previewView!!)
        needUpdateGraphicOverlayImageSourceInfo = true
        cameraXSource!!.start()
    }

    private var isDetectedObjectImageActivityOpen = false // Add this field


    private fun onDetectionTaskSuccess(results: List<DetectedObject>) {
        graphicOverlay!!.clear()
        if (needUpdateGraphicOverlayImageSourceInfo) {
            val size: Size = cameraXSource!!.previewSize!!
            if (size != null) {
                Log.d(TAG, "preview width: " + size.width)
                Log.d(TAG, "preview height: " + size.height)
                val isImageFlipped =
                    cameraXSource!!.cameraFacing == CameraSourceConfig.CAMERA_FACING_FRONT
                if (isPortraitMode) {
                    // Swap width and height sizes when in portrait, since it will be rotated by
                    // 90 degrees. The camera preview and the image being processed have the same size.
                    graphicOverlay!!.setImageSourceInfo(size.height, size.width, isImageFlipped)
                } else {
                    graphicOverlay!!.setImageSourceInfo(size.width, size.height, isImageFlipped)
                }
                needUpdateGraphicOverlayImageSourceInfo = false
            } else {
                Log.d(TAG, "previewsize is null")
            }
        }
        val targetBorder = findViewById<FrameLayout>(R.id.target_border)
        val targetBorderLocation = IntArray(2)
        targetBorder.getLocationOnScreen(targetBorderLocation)
        val targetBorderRect = Rect(
            targetBorderLocation[0],
            targetBorderLocation[1],
            targetBorderLocation[0] + targetBorder.width,
            targetBorderLocation[1] + targetBorder.height
        )
        Log.v(TAG, "Number of object been detected: " + results.size)
        for (`object` in results) {
            graphicOverlay!!.add(ObjectGraphic(graphicOverlay!!, `object`))
            val boundingBox = `object`.boundingBox
            val screenArea = previewView!!.width * previewView!!.height.toFloat()

            val objectArea = boundingBox.width() * boundingBox.height()
            (objectArea / screenArea)
            val objectScreenRect = Rect(
                translateX(boundingBox.left, targetBorder!!.width),
                translateY(boundingBox.top, targetBorder.height),
                translateX(boundingBox.right, targetBorder.width),
                translateY(boundingBox.bottom, targetBorder.height)
            )

            val width = boundingBox.width()
            val height = boundingBox.height()
            val aspectRatio = width.toFloat() / height
            val rectangleThreshold = 0.8 // You can adjust this threshold as needed
            val rect = RectF(`object`.boundingBox)
            Log.d("sssssssssssssssssss", rect.toString())

            if (!isDetectedObjectImageActivityOpen && areRectangles90PercentSimilar(
                    objectScreenRect,
                    targetBorderRect
                ) && `object`.labels.isNotEmpty() && (`object`.labels[0].text.contains(
                    "Driving License", true
                ) || `object`.labels[0].text.contains(
                    "Vehicle License", true
                )
                        )
            ) {
                // Check if the object is stable, preventing multiple captures of the same object
                if (isObjectStable(boundingBox)
                ) {
                    graphicOverlay!!.add(ObjectGraphic(graphicOverlay!!, `object`))

                    // Capture and send the image as a URI to DetectedObjectImageActivity
                    val detectedImageUri = previewView?.bitmap?.let { bitmapToUri(it) }
                    val intent = Intent(this, DetectedObjectImageActivity::class.java)
                    intent.putExtra("detectedImageUri", detectedImageUri.toString())
                    startActivity(intent)

                    // Reset the flag to capture a new object
                    isDetectedObjectImageActivityOpen = true
                }
            }
        }
        graphicOverlay!!.add(InferenceInfoGraphic(graphicOverlay!!))
        graphicOverlay!!.postInvalidate()
    }

    private fun cropBitmapRegion(originalBmp: Bitmap, rect: Rect): Bitmap {
        val rectanglePositionX = rect.left
        val rectanglePositionY = rect.top
        val rectangleWidth = rect.width()
        val rectangleHeight = rect.height()

        // Ensure the cropping coordinates and dimensions are within bounds
        val cropX = Integer.max(0, rectanglePositionX)
        val cropY = Integer.max(0, rectanglePositionY)
        val cropWidth = Integer.min(originalBmp.width - cropX, rectangleWidth)
        val cropHeight = Integer.min(originalBmp.height - cropY, rectangleHeight)

        return Bitmap.createBitmap(originalBmp, 0, cropY, originalBmp.width, cropHeight)
    }


    private fun isRectWithinTargetBorder(rect: Rect, targetBorderRect: Rect): Boolean {
        val rectF = RectF(rect)
        val targetRectF = RectF(targetBorderRect)

        if (rectF.intersect(targetRectF)) {
            val intersectionArea = (rectF.width() + 75f) * (rectF.height() + 75f)
            val rectArea = rectF.width() * rectF.height()

            val overlapPercentage = intersectionArea / rectArea

            return overlapPercentage >= 0.80 && rect.left.minus(targetBorderRect.left) < 30 && rect.left.minus(
                targetBorderRect.left
            ) > 0
        }

        return false
    }


    private fun areRectangles90PercentSimilar(rect1: Rect, rect2: Rect): Boolean {
        // Calculate the area of each rectangle
        val areaRect1 = rect1.width() * rect1.height()
        val areaRect2 = rect2.width() * rect2.height()

        // Calculate the overlap area by finding the intersection of the two rectangles
        val overlapRect = Rect()
        overlapRect.setIntersect(rect1, rect2)

        // Calculate the area of the overlap region
        val areaOverlap = overlapRect.width() * overlapRect.height()

        // Calculate the minimum area between the two rectangles
        val minArea = areaRect1.coerceAtMost(areaRect2)

        // Check if the area of overlap is at least 90% of the smaller rectangle's area
        return areaOverlap == minArea
    }


    // Calculate the translated X-coordinate
    private fun translateX(x: Int, previewViewWidth: Int): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        return x / previewViewWidth * screenWidth
    }

    // Calculate the translated Y-coordinate
    private fun translateY(y: Int, previewViewHeight: Int): Int {
        val screenHeight = resources.displayMetrics.heightPixels
        return y / previewViewHeight * screenHeight
    }


    private var previousBoundingBox: Rect? = null

    private fun isObjectStable(currentBoundingBox: Rect): Boolean {
        val threshold = 20 // Adjust this threshold as needed

        val prevBoundingBox = previousBoundingBox
        previousBoundingBox = currentBoundingBox

        return if (prevBoundingBox != null) {
            // Calculate the absolute difference between current and previous positions
            val deltaX = kotlin.math.abs(currentBoundingBox.left - prevBoundingBox.left)
            val deltaY = kotlin.math.abs(currentBoundingBox.top - prevBoundingBox.top)

            // Check if the object has moved beyond the threshold
            deltaX <= threshold && deltaY <= threshold
        } else {
            // If no previous bounding box is available, consider it stable
            true
        }
    }

    private fun bitmapToUri(bitmap: Bitmap): Uri? {
        val context = applicationContext
        val imagesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val timestamp = System.currentTimeMillis()
        val imageFilename = "DetectedObjectImage_$timestamp.jpg"
        val imageFile = File(imagesDir, imageFilename)
        try {
            val outputStream = FileOutputStream(imageFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            outputStream.flush()
            outputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        // Return the Uri of the saved image file
        return Uri.fromFile(imageFile)
    }

    private fun onDetectionTaskFailure(e: Exception) {
        graphicOverlay!!.clear()
        graphicOverlay!!.postInvalidate()
        val error = "Failed to process. Error: " + e.localizedMessage
        Toast.makeText(
            graphicOverlay!!.getContext(),
            """
   $error
   Cause: ${e.cause}
      """.trimIndent(),
            Toast.LENGTH_SHORT
        )
            .show()
        Log.d(TAG, error)
    }

    private val isPortraitMode: Boolean
        get() =
            (applicationContext.resources.configuration.orientation !==
                    Configuration.ORIENTATION_LANDSCAPE)

    companion object {
        private const val TAG = "CameraXSourcePreview"
        private val localModel: LocalModel =
            LocalModel.Builder().setAssetFilePath("custom_models/license_custom_model.tflite")
                .build()
    }
}
