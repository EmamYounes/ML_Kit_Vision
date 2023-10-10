package com.google.mlkit.vision.demo.java;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;

import com.google.android.gms.common.annotation.KeepName;
import com.google.mlkit.common.model.LocalModel;
import com.google.mlkit.vision.camera.CameraSourceConfig;
import com.google.mlkit.vision.camera.CameraXSource;
import com.google.mlkit.vision.camera.DetectionTaskCallback;
import com.google.mlkit.vision.demo.GraphicOverlay;
import com.google.mlkit.vision.demo.InferenceInfoGraphic;
import com.google.mlkit.vision.demo.R;
import com.google.mlkit.vision.demo.java.objectdetector.ObjectGraphic;
import com.google.mlkit.vision.demo.kotlin.DetectedObjectImageActivity;
import com.google.mlkit.vision.demo.preference.PreferenceUtils;
import com.google.mlkit.vision.demo.preference.SettingsActivity;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Objects;

@KeepName
@RequiresApi(VERSION_CODES.LOLLIPOP)
public final class CameraXSourceDemoActivity extends AppCompatActivity
        implements CompoundButton.OnCheckedChangeListener {
    private static final String TAG = "CameraXSourceDemo";

    private static final LocalModel localModel =
            new LocalModel.Builder().setAssetFilePath("custom_models/object_labeler.tflite").build();

    private PreviewView previewView;
    private GraphicOverlay graphicOverlay;

    private boolean needUpdateGraphicOverlayImageSourceInfo;

    private int lensFacing = CameraSourceConfig.CAMERA_FACING_BACK;
    private DetectionTaskCallback<List<DetectedObject>> detectionTaskCallback;
    private CameraXSource cameraXSource;
    private CustomObjectDetectorOptions customObjectDetectorOptions;
    private Size targetResolution;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");

        setContentView(R.layout.activity_vision_cameraxsource_demo);
        previewView = findViewById(R.id.preview_view);
        if (previewView == null) {
            Log.d(TAG, "previewView is null");
        }
        graphicOverlay = findViewById(R.id.graphic_overlay);
        if (graphicOverlay == null) {
            Log.d(TAG, "graphicOverlay is null");
        }

        ToggleButton facingSwitch = findViewById(R.id.facing_switch);
        facingSwitch.setOnCheckedChangeListener(this);

        ImageView settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(
                v -> {
                    Intent intent = new Intent(getApplicationContext(), SettingsActivity.class);
                    intent.putExtra(
                            SettingsActivity.EXTRA_LAUNCH_SOURCE,
                            SettingsActivity.LaunchSource.CAMERAXSOURCE_DEMO);
                    startActivity(intent);
                });
        detectionTaskCallback =
                detectionTask ->
                        detectionTask
                                .addOnSuccessListener(this::onDetectionTaskSuccess)
                                .addOnFailureListener(this::onDetectionTaskFailure);
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        lensFacing =
                lensFacing == CameraSourceConfig.CAMERA_FACING_FRONT
                        ? CameraSourceConfig.CAMERA_FACING_BACK
                        : CameraSourceConfig.CAMERA_FACING_FRONT;

        createThenStartCameraXSource();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (cameraXSource != null
                && PreferenceUtils.getCustomObjectDetectorOptionsForLivePreview(this, localModel)
                .equals(customObjectDetectorOptions)
                && PreferenceUtils.getCameraXTargetResolution(getApplicationContext(), lensFacing) != null
                && Objects.requireNonNull(
                        PreferenceUtils.getCameraXTargetResolution(getApplicationContext(), lensFacing))
                .equals(targetResolution)) {
            cameraXSource.start();
        } else {
            createThenStartCameraXSource();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cameraXSource != null) {
            cameraXSource.stop();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (cameraXSource != null) {
            cameraXSource.close();
        }
    }

    private void createThenStartCameraXSource() {
        if (cameraXSource != null) {
            cameraXSource.close();
        }
        customObjectDetectorOptions =
                PreferenceUtils.getCustomObjectDetectorOptionsForLivePreview(
                        getApplicationContext(), localModel);
        ObjectDetector objectDetector = ObjectDetection.getClient(customObjectDetectorOptions);
        CameraSourceConfig.Builder builder =
                new CameraSourceConfig.Builder(
                        getApplicationContext(), objectDetector, detectionTaskCallback)
                        .setFacing(lensFacing);
        targetResolution =
                PreferenceUtils.getCameraXTargetResolution(getApplicationContext(), lensFacing);
        if (targetResolution != null) {
            builder.setRequestedPreviewSize(targetResolution.getWidth(), targetResolution.getHeight());
        }
        cameraXSource = new CameraXSource(builder.build(), previewView);
        needUpdateGraphicOverlayImageSourceInfo = true;
        cameraXSource.start();
    }

    private boolean isDetectedObjectImageActivityOpen = false; // Add this field

    private void onDetectionTaskSuccess(List<DetectedObject> results) {
        graphicOverlay.clear();
        if (needUpdateGraphicOverlayImageSourceInfo) {
            Size size = cameraXSource.getPreviewSize();
            if (size != null) {
                Log.d(TAG, "preview width: " + size.getWidth());
                Log.d(TAG, "preview height: " + size.getHeight());
                boolean isImageFlipped = cameraXSource.getCameraFacing() == CameraSourceConfig.CAMERA_FACING_FRONT;
                if (isPortraitMode()) {
                    graphicOverlay.setImageSourceInfo(size.getHeight(), size.getWidth(), isImageFlipped);
                } else {
                    graphicOverlay.setImageSourceInfo(size.getWidth(), size.getHeight(), isImageFlipped);
                }
                needUpdateGraphicOverlayImageSourceInfo = false;
            } else {
                Log.d(TAG, "previewsize is null");
            }
        }

        Log.v(TAG, "Number of object been detected: " + results.size());

        for (DetectedObject object : results) {
            graphicOverlay.add(new ObjectGraphic(graphicOverlay, object));

            if (!isDetectedObjectImageActivityOpen) {
                // Get the detected object image
                Bitmap detectedObjectBitmap = getDetectedObjectImage(object, getCameraPreviewFrame());

                // Log information about the bitmap
                Log.d(TAG, "Detected object bitmap dimensions: " + detectedObjectBitmap.getWidth() + " x " + detectedObjectBitmap.getHeight());
                Log.d(TAG, "Detected object bitmap byte size: " + detectedObjectBitmap.getByteCount());

                // Create an Intent to navigate to the DetectedObjectImageActivity
                Intent intent = new Intent(this, DetectedObjectImageActivity.class);

                // Pass the detected object image as an extra
                intent.putExtra("detectedObjectBitmap", bitmapToUri(detectedObjectBitmap).toString());

                // Start the new activity
                startActivity(intent);
                isDetectedObjectImageActivityOpen = true; // Set the flag to true
            }

        }

        graphicOverlay.add(new InferenceInfoGraphic(graphicOverlay));
        graphicOverlay.postInvalidate();
    }

    private Uri bitmapToUri(Bitmap bitmap) {
        // Convert Bitmap to Uri
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bytes);
        String path = MediaStore.Images.Media.insertImage(
                getContentResolver(),
                bitmap,
                "DetectedObjectImage",
                null);
        return Uri.parse(path);
    }

    private Bitmap getDetectedObjectImage(DetectedObject detectedObject, Bitmap fullImage) {
        // Retrieve the bounding box of the detected object
        Rect boundingBox = detectedObject.getBoundingBox();

        // Crop the detected object from the full image using the bounding box
        Bitmap detectedObjectBitmap = Bitmap.createBitmap(
                fullImage, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height()
        );

        return detectedObjectBitmap;
    }

    private Bitmap getCameraPreviewFrame() {
        // Initialize a Bitmap object to store the camera preview frame.
        Bitmap cameraPreviewFrame = null;

        // Capture the camera preview frame here.
        // This code will vary depending on the camera library or framework you are using.

        // Below is a generic example for capturing a camera preview frame using CameraX.

        // Assuming you have a CameraX use case (Preview) set up.
        PreviewView previewView = findViewById(R.id.preview_view); // Replace with your PreviewView ID.

        // Ensure that the PreviewView is ready.
        if (previewView != null && previewView.getWidth() > 0 && previewView.getHeight() > 0) {
            // Create a Bitmap with the same dimensions as the PreviewView.
            cameraPreviewFrame = Bitmap.createBitmap(previewView.getWidth(), previewView.getHeight(), Bitmap.Config.ARGB_8888);

            // Create a Canvas to draw the camera preview frame onto the Bitmap.
            Canvas canvas = new Canvas(cameraPreviewFrame);

            // Capture the camera preview frame by drawing the PreviewView onto the Canvas.
            previewView.draw(canvas);
        }

        return previewView.getBitmap();
    }

    private void onDetectionTaskFailure(Exception e) {
        graphicOverlay.clear();
        graphicOverlay.postInvalidate();
        String error = "Failed to process. Error: " + e.getLocalizedMessage();
        Toast.makeText(
                        graphicOverlay.getContext(), error + "\nCause: " + e.getCause(), Toast.LENGTH_SHORT)
                .show();
        Log.d(TAG, error);
    }

    private boolean isPortraitMode() {
        return getApplicationContext().getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_LANDSCAPE;
    }
}
