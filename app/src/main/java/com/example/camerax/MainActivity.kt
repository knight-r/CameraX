package com.example.camerax

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
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
import androidx.core.net.toFile
import com.example.camerax.databinding.ActivityMainBinding
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.source.ConcatenatingMediaSource
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {
    private lateinit var mainBinding: ActivityMainBinding
    private var mIsVideoRecording: Boolean = false

    private var mImageCapture: ImageCapture? = null
    private lateinit var mImageCaptureExecutor: ExecutorService
    private lateinit var mCameraSelector:CameraSelector

    private var mVideoCapture: VideoCapture<Recorder>? = null
    private var mRecording: Recording? = null
    private var quality = Quality.HD
    private val qualitySelector = QualitySelector.from(quality)
    private var recorderBuilder = Recorder.Builder()

    private var recorder = Recorder.Builder().build()
    private var mIsVideoPaused: Boolean = false
    private  lateinit var mChronometer: Chronometer
    private var isFlashOn: Boolean = false
    private lateinit var camera: Camera
    private var mIsCameraSwitched: Boolean = false
    private lateinit var videoFile1: File
    private lateinit var videoFile2: File
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
        videoFile1 = File("")
        videoFile2 = File("")
        recorderBuilder.setQualitySelector(qualitySelector)
        mainBinding.ivStartStop.setOnClickListener {
            if (mIsVideoRecording) {
                mIsVideoRecording = false
               stopRecording()
            } else {
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
            mIsCameraSwitched = true
            if (mIsVideoRecording) {
                stopRecording()
                startRecordingVideo()
            } else {
                switchCamera()
                startCamera()
            }
        }
        mainBinding.ivTakePicture.setOnClickListener {
            takePhoto() // it will also save the photo
        }

        mainBinding.ivFlash.setOnClickListener {
            onFlashButtonClicked()
        }

    }

    private fun switchCamera() {
        if ( mCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            camera.cameraControl.enableTorch(false)
            mCameraSelector =  CameraSelector.DEFAULT_FRONT_CAMERA
            mainBinding.ivFlash.setBackgroundResource(R.drawable.ic_flash_off_icon)
        } else {
            mCameraSelector =  CameraSelector.DEFAULT_BACK_CAMERA
        }
        startCamera()

    }

    private fun onFlashButtonClicked() {
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

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        if(mIsCameraSwitched) {
            recorder = Recorder.Builder().build()
        }
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

            Toast.makeText( this@MainActivity , "The image has been saved to Gallery", Toast.LENGTH_SHORT).show()

        }
    }

    private fun startRecordingVideo() {
        mIsVideoRecording = true
        mainBinding.ivStartStop.setBackgroundResource(R.drawable.ic_stop_video_icon)
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
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "VIDEO_${System.currentTimeMillis()}")
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
                                    val msg = "Video saved: ${recordEvent.outputResults.outputUri}"
                                    Toast.makeText(this,  recordEvent.outputResults.outputUri.path, Toast.LENGTH_SHORT).show()

//                                    if (mIsCameraSwitched) {
//                                        videoFile2 = File(recordEvent.outputResults.outputUri.path!!)
//                                        mergeVideos(videoFile1, videoFile2, videoFile1.path)
//                                       // Toast.makeText(this,  "Camera switched", Toast.LENGTH_SHORT).show()
//
//                                    } else {
//                                        videoFile1 = File(recordEvent.outputResults.outputUri.path!!)
//                                        mainBinding.ivPauseResume.visibility = View.GONE
//                                        mChronometer.stop()
//                                        mChronometer.visibility = View.GONE
//                                    }
                                    //startCamera()

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

    private fun stopRecording() {
        mainBinding.ivStartStop.setBackgroundResource(R.drawable.ic_start_video_icon)
        mRecording!!.stop()
        mChronometer.stop()
        mChronometer.visibility = View.GONE
        if(mIsCameraSwitched) {
            switchCamera()
        }
        if(!mIsVideoRecording) {
            mainBinding.ivPauseResume.visibility = View.GONE
        }


    }

    private fun mergeVideos(videoFile1: File, videoFile2: File, outputFilePath: String) {
        Toast.makeText(this,  "Camera switched", Toast.LENGTH_SHORT).show()

//        val mediaMuxer = MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
//        val videoTrackIndex = -1
//        var audioTrackIndex = -1
//
//        // Add the first video file to the media muxer
//        val extractor1 = MediaExtractor()
//        extractor1.setDataSource(videoFile1.path)
//        val videoFormat1 = extractor1.getTrackFormat(0)
//        val audioFormat1 = extractor1.getTrackFormat(1)
//        val videoTrackIndex1 = mediaMuxer.addTrack(videoFormat1)
//        val audioTrackIndex1 = mediaMuxer.addTrack(audioFormat1)
//        extractor1.selectTrack(0)
//
//        // Add the second video file to the media muxer
//        val extractor2 = MediaExtractor()
//        extractor2.setDataSource(videoFile2.path)
//        val videoFormat2 = extractor2.getTrackFormat(0)
//        val audioFormat2 = extractor2.getTrackFormat(1)
//        val videoTrackIndex2 = mediaMuxer.addTrack(videoFormat2)
//        val audioTrackIndex2 = mediaMuxer.addTrack(audioFormat2)
//        extractor2.selectTrack(0)
//
//        mediaMuxer.start()
//
//        // Write the data from the first video file to the output file
//        val buffer1 = ByteBuffer.allocate(1024 * 1024)
//        var sampleSize1 = extractor1.readSampleData(buffer1, 0)
//        while (sampleSize1 >= 0) {
//            val mflag = extractor1.sampleFlags
//            val bufferInfo = MediaCodec.BufferInfo()
//            bufferInfo.offset = 0
//            bufferInfo.size = sampleSize1
//            bufferInfo.presentationTimeUs = extractor1.sampleTime
//            bufferInfo.flags = mflag
//            mediaMuxer.writeSampleData(videoTrackIndex1, buffer1, bufferInfo)
//            extractor1.advance()
//            sampleSize1 = extractor1.readSampleData(buffer1, 0)
//        }
//        // Write the data from the second video file to the output file
//        val buffer2 = ByteBuffer.allocate(1024 * 1024)
//        var sampleSize2 = extractor2.readSampleData(buffer2, 0)
//        while (sampleSize2 >= 0) {
//            val flag = extractor2.sampleFlags
//            val bufferInfo = MediaCodec.BufferInfo()
//            bufferInfo.offset = 0
//            bufferInfo.size = sampleSize2
//            bufferInfo.presentationTimeUs = extractor2.sampleTime
//            bufferInfo.flags = flag
//            mediaMuxer.writeSampleData(videoTrackIndex2, buffer2, bufferInfo)
//            extractor2.advance()
//            sampleSize2 = extractor2.readSampleData(buffer2, 0)
//        }
//
//        // Release the extractors
//        extractor1.release()
//        extractor2.release()
//
//        // Stop and release the media muxer
//        mediaMuxer.stop()
//        mediaMuxer.release()
    }
    private fun buildMediaSource(file: File): MediaSource {
        val dataSourceFactory = DefaultDataSourceFactory(this, Util.getUserAgent(this, "CameraX"))
        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))
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
    companion object {
        const val TAG = "MainActivity"
        const val REQUEST_CAMERA_PERMISSION: Int = 0
        const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}
