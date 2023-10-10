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
import android.net.Uri
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.widget.CompoundButton
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
import java.io.ByteArrayOutputStream
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
        Log.v(TAG, "Number of object been detected: " + results.size)
        for (`object` in results) {
            graphicOverlay!!.add(ObjectGraphic(graphicOverlay!!, `object`))
            val detectedObject = `object`
            if (!isDetectedObjectImageActivityOpen && detectedObject.labels.isNotEmpty() &&
                (detectedObject.labels[0].text.contains(
                    "card",
                    true
                ) || detectedObject.labels[0].text.contains(
                    "license",
                    true
                )) && detectedObject.labels[0].confidence > .75
            ) {

                // Create an Intent to navigate to the DetectedObjectImageActivity

                // Create an Intent to navigate to the DetectedObjectImageActivity

                // Get the detected object image
                val detectedObjectBitmap: Bitmap? =
                    getCameraPreviewFrame()?.let { getDetectedObjectImage(`object`, it) }

//                detectedObject.boundingBox.
                val intent = Intent(this, DetectedObjectImageActivity::class.java)

                // Pass the detected object image as an extra

                // Pass the detected object image as an extra
                intent.putExtra(
                    "detectedObjectBitmap",
                    detectedObjectBitmap?.let { bitmapToUri(it).toString() }
                )

                // Start the new activity

                // Start the new activity
                startActivity(intent)

                Toast.makeText(this, detectedObject.labels[0].text, Toast.LENGTH_LONG).show()
                isDetectedObjectImageActivityOpen = true

            }
        }
        graphicOverlay!!.add(InferenceInfoGraphic(graphicOverlay!!))
        graphicOverlay!!.postInvalidate()
    }

    private fun getDetectedObjectImage(
        detectedObject: DetectedObject,
        fullImage: Bitmap
    ): Bitmap {
        // Retrieve the bounding box of the detected object
        val boundingBox = detectedObject.boundingBox

        // Crop the detected object from the full image using the bounding box
        return Bitmap.createBitmap(
            fullImage, 0, boundingBox.top, fullImage.width, boundingBox.height()*2
        )
    }

    private fun bitmapToUri(bitmap: Bitmap): Uri? {
        // Convert Bitmap to Uri
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bytes)
        val path = MediaStore.Images.Media.insertImage(
            contentResolver,
            bitmap,
            "DetectedObjectImage",
            null
        )
        return Uri.parse(path)
    }

    private fun getCameraPreviewFrame(): Bitmap? {
        return previewView?.bitmap
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
            LocalModel.Builder().setAssetFilePath("custom_models/object_labeler.tflite").build()
    }
}
