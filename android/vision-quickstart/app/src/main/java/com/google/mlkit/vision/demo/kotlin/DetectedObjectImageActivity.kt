package com.google.mlkit.vision.demo.kotlin

import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.demo.R
import com.squareup.picasso.Picasso


class DetectedObjectImageActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detected_object_image)

        // Retrieve the detected object image from the intent
        val detectedObjectUri = intent.getStringExtra("detectedImageUri")

        Log.d("detectedImageUri", detectedObjectUri.toString())
        // Display the detected object image in an ImageView
        val imageView = findViewById<ImageView>(R.id.btn)
        val desiredWidth = imageView.width
        val desiredHeight = imageView.height

        Picasso.get().load(detectedObjectUri).into(imageView);

    }
}
