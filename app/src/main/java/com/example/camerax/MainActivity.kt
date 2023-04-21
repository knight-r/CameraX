package com.example.camerax

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Chronometer
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.example.camerax.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {
    private lateinit var mainBinding: ActivityMainBinding
    private var mIsVideoRecording: Boolean = false
    private val REQUEST_CAMERA_PERMISSION: Int = 0
    private var mImageCapture: ImageCapture? = null
    private lateinit var mImageCaptureExecutor: ExecutorService
    private lateinit var mCameraSelector:CameraSelector

    private var mVideoCapture: VideoCapture<Recorder>? = null
    private var mRecording: Recording? = null
    private var quality = Quality.HD
    private val qualitySelector = QualitySelector.from(quality)
    private var recorderBuilder = Recorder.Builder()

    private val recorder = Recorder.Builder().build()
    private var mIsVideoPaused: Boolean = false
    private  lateinit var mChronometer: Chronometer
    private var isFlashOn: Boolean = false
    private lateinit var camera: Camera
    private val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mainBinding.root)

        mImageCaptureExecutor = Executors.newSingleThreadExecutor()
        if(allPermissionsGranted()) {
            mCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            startCamera()
        } else {
            Toast.makeText(this, "Require permissions to access camera", Toast.LENGTH_SHORT).show()
        }

        mChronometer = mainBinding.chronometer
        mChronometer.visibility = View.GONE
        mainBinding.ivPauseResume.visibility = View.GONE

        recorderBuilder.setQualitySelector(qualitySelector)
        mainBinding.ivStartStop.setOnClickListener {
            if (mIsVideoRecording) {
                mIsVideoRecording = false
                mainBinding.ivStartStop.setBackgroundResource(R.drawable.ic_start_video_icon)
                mRecording!!.stop()
            } else {
                mIsVideoRecording = true
                mainBinding.ivStartStop.setBackgroundResource(R.drawable.ic_stop_video_icon)
                startRecordingVideo()
            }
        }
        var timeWhenPaused: Long = 0
        mainBinding.ivPauseResume.setOnClickListener {
            if (mIsVideoPaused) {
                mIsVideoPaused = false
                mainBinding.ivPauseResume.setBackgroundResource(R.drawable.ic_pause_icon)
                mRecording!!.resume()
                mChronometer.base = SystemClock.elapsedRealtime() + timeWhenPaused
                mChronometer.start()

            } else {
                mIsVideoPaused = true
                mainBinding.ivPauseResume.setBackgroundResource(R.drawable.ic_resume_icon)
                timeWhenPaused = mChronometer.base - SystemClock.elapsedRealtime()
                mChronometer.stop()
                mRecording!!.pause()
            }
        }

        mainBinding.ivSwitchCamera.setOnClickListener {

          if ( mCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
               camera.cameraControl.enableTorch(false)
              mCameraSelector =  CameraSelector.DEFAULT_FRONT_CAMERA
              mainBinding.ivFlash.setBackgroundResource(R.drawable.ic_flash_off_icon)
          } else {
              mCameraSelector =  CameraSelector.DEFAULT_BACK_CAMERA
           }
            startCamera()
        }
        mainBinding.ivTakePicture.setOnClickListener {
            takePhoto() // it will also save the photo
        }

        mainBinding.ivFlash.setOnClickListener {
            if(mCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                mainBinding.ivFlash.setBackgroundResource(R.drawable.ic_flash_off_icon)
                Toast.makeText(this, "Flash is not available", Toast.LENGTH_SHORT).show()
            } else {
                if (isFlashOn) {
                    isFlashOn = false
                    mainBinding.ivFlash.setBackgroundResource(R.drawable.ic_flash_off_icon)
                    camera.cameraControl.enableTorch(false)
                } else {
                    mainBinding.ivFlash.setBackgroundResource(R.drawable.ic_flash_icon)
                    if(camera.cameraInfo.hasFlashUnit()){
                        camera.cameraControl.enableTorch(true)
                        isFlashOn = true
                    } else {
                        Toast.makeText(this, "Flash is not available", Toast.LENGTH_SHORT).show()
                    }
                }
            }

        }

    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        mVideoCapture = VideoCapture.withOutput(recorder)
        cameraProviderFuture.addListener({

            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(mainBinding.cameraPreview.surfaceProvider)
                }
            mImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, mCameraSelector,mImageCapture,mVideoCapture,preview)
                //camera.cameraControl.setZoomRatio(6.0f)
            } catch (e: Exception) {
               Log.d("MainActivity", "Use case binding failed")
            }

        }, ContextCompat.getMainExecutor(this))

    }
    private fun takePhoto() {
        mImageCapture?.let{

           val imageFileName = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, imageFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
                }
            }
            val outputFileOptions = ImageCapture.OutputFileOptions
                .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
            ).build()

            it.takePicture(
                outputFileOptions,
                mImageCaptureExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults){
                        startCamera()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Toast.makeText(
                            mainBinding.root.context,
                            "Error occurred in taking photo",
                            Toast.LENGTH_LONG
                        ).show()
                        Log.d("MainActivity", "Error taking photo:$exception")
                    }

                })
            Toast.makeText( this@MainActivity , "The image has been saved successfully", Toast.LENGTH_SHORT).show()

        }
    }

    private fun startRecordingVideo() {
         mVideoCapture!!.let {
             try {
                 if (ActivityCompat.checkSelfPermission(
                         this,
                         Manifest.permission.RECORD_AUDIO
                     ) != PackageManager.PERMISSION_GRANTED
                 ) {
                     allPermissionsGranted()
                 }

                 val contentValues = ContentValues().apply {
                     put(MediaStore.MediaColumns.DISPLAY_NAME, "CameraX-VideoCapture")
                     put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                 }

                 val mediaStoreOutputOptions = MediaStoreOutputOptions
                     .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                     .setContentValues(contentValues)
                     .build()

                 mRecording = mVideoCapture!!.output
                     .prepareRecording(this, mediaStoreOutputOptions)
                     .apply {
                         // Enable Audio for recording
                         if (PermissionChecker.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                             PermissionChecker.PERMISSION_GRANTED ) {
                             withAudioEnabled()
                         }
                     }
                     .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                         when(recordEvent) {
                             is VideoRecordEvent.Start -> {
                                 mainBinding.ivPauseResume.visibility = View.VISIBLE
                                 mChronometer.visibility = View.VISIBLE
                                 mChronometer.base = SystemClock.elapsedRealtime()
                                 mChronometer.start()
                             }
                             is VideoRecordEvent.Pause -> {}
                             is VideoRecordEvent.Finalize -> {
                                 if (!recordEvent.hasError()) {
                                     val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                                     Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                                     mainBinding.ivPauseResume.visibility = View.GONE
                                     mChronometer.stop()
                                     mChronometer.visibility = View.GONE
                                     startCamera()

                                 } else {
                                     mRecording?.close()
                                     mRecording = null
                                     Log.e("MainActivity", "Video capture ends with error: ${recordEvent.error}")
                                 }

                             }
                             is VideoRecordEvent.Resume -> {}
                         }
                     }

             } catch (e: Exception) {
                 e.printStackTrace()
             }
         }

    }

    private fun allPermissionsGranted(): Boolean {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }
        if(ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CAMERA_PERMISSION)
        }
        return ActivityCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

