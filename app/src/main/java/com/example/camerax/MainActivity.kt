package com.example.camerax

import android.Manifest
import android.app.ProgressDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.Chronometer
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.impl.ImageOutputConfig.RotationValue
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.impl.VideoCaptureConfig
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.example.camerax.databinding.ActivityMainBinding
import org.mp4parser.muxer.Movie
import org.mp4parser.muxer.Track
import org.mp4parser.muxer.builder.DefaultMp4Builder
import org.mp4parser.muxer.container.mp4.MovieCreator
import org.mp4parser.muxer.tracks.AppendTrack
import java.io.File
import java.io.FileOutputStream
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

    private val outputFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "merged_video.mp4")
    private lateinit var mVideoFileList: MutableList<File>
    private lateinit var progressDialogue: ProgressDialog
    private var timeWhenPaused: Long  = 0
    private var cameraSwitchCount:Int = 0
    private var mBackgroundThreadHandler: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mainBinding.root)

        mImageCaptureExecutor = Executors.newSingleThreadExecutor()

       // checkPermission(arrayOf(Manifest.permission.CAMERA,Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE) ,0)

        progressDialogue = ProgressDialog(this)
        progressDialogue.setMessage("Merging Videos..")
        if(allPermissionsGranted()) {
            mCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            startCamera()
        } else {
            Toast.makeText(this, "Require permissions to access camera", Toast.LENGTH_SHORT).show()
        }

        mVideoFileList = mutableListOf()
        mChronometer = mainBinding.chronometer
        mChronometer.visibility = View.GONE
        mainBinding.ivPauseResume.visibility = View.GONE

        recorderBuilder.setQualitySelector(qualitySelector)
        mainBinding.ivStartStop.setOnClickListener {
            if (mIsVideoRecording) {
               // mainBinding.progressbar.visibility = View.VISIBLE
                mainBinding.ivTakePicture.visibility = View.VISIBLE
                progressDialogue.show()
                mIsVideoRecording = false
                cameraSwitchCount = 0
                stopRecording()

            } else {
                mainBinding.ivTakePicture.visibility = View.GONE
                mIsVideoRecording = true
                startRecordingVideo()
            }
        }




         timeWhenPaused = 0
        mainBinding.ivPauseResume.setOnClickListener {
            if (mIsVideoPaused) {
                mIsVideoPaused = false
                mRecording!!.resume()

            } else {
                mIsVideoPaused = true
                mRecording!!.pause()
            }
        }

        mainBinding.ivSwitchCamera.setOnClickListener {
            mIsCameraSwitched = true
            if (mIsVideoRecording) {
                ++cameraSwitchCount
                stopRecording()
                switchCamera()
                startRecordingVideo()
            } else {
                switchCamera()
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
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(mainBinding.cameraPreview.surfaceProvider)
            }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        if(mIsCameraSwitched) {
            recorder = Recorder.Builder().build()
        }

        Log.d(TAG, cameraSwitchCount.toString())
        mVideoCapture = VideoCapture.withOutput(recorder)

        cameraProviderFuture.addListener({

            val cameraProvider = cameraProviderFuture.get()

            mImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, mCameraSelector,mImageCapture,mVideoCapture,preview)

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



                mVideoCapture!!.targetRotation = Surface.ROTATION_180

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
                            is VideoRecordEvent.Pause -> {
                                mIsVideoPaused = true
                                mainBinding.ivPauseResume.setBackgroundResource(R.drawable.ic_resume_icon)
                                timeWhenPaused = mChronometer.base - SystemClock.elapsedRealtime()
                                mChronometer.stop()
                            }
                            is VideoRecordEvent.Finalize -> {
                                if (!recordEvent.hasError()) {

                                    val savedUri = recordEvent.outputResults.outputUri

                                    if (savedUri != null) {
                                        val projection = arrayOf(MediaStore.Video.Media.DATA)
                                        val cursor = contentResolver.query(savedUri, projection, null, null, null)
                                        cursor?.use {
                                            if (it.moveToFirst()) {
                                                val filePath = it.getString(it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA))
                                                val videoFile = File(filePath)
                                                if(videoFile.exists()) {
                                                    mVideoFileList.add(videoFile)
                                                    Toast.makeText(this,  videoFile.path.toString(), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }

                                    if(!mIsVideoRecording){
                                        mergeVideos(mVideoFileList)
                                    }

                                } else {
                                    mRecording?.close()
                                    mRecording = null
                                    Log.e("MainActivity", "Video capture ends with error: ${recordEvent.error}")
                                }

                            }
                            is VideoRecordEvent.Resume -> {
                                mIsVideoPaused = false
                                mainBinding.ivPauseResume.setBackgroundResource(R.drawable.ic_pause_icon)
                                mChronometer.base = SystemClock.elapsedRealtime() + timeWhenPaused
                                mChronometer.start()
                            }
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
        if(!mIsVideoRecording) {
            mainBinding.ivPauseResume.visibility = View.GONE
            startCamera()
        }
    }


    private fun mergeVideos(videoFiles: List<File>) {
        try {

            val movieList = mutableListOf<Movie>()
            for (videoFile  in videoFiles) {
                val movie = MovieCreator.build(videoFile.absolutePath)
                movieList.add(movie)
            }
            val videoTracks = mutableListOf<Track>()
            val audioTracks = mutableListOf<Track>()

            for (movie in movieList) {
                for (track in movie.tracks) {
                    if (track.handler == "vide") {
                        videoTracks.add(track)
                    }
                    if (track.handler == "soun") {
                        audioTracks.add(track)
                    }

                }
            }

            val mergedMovie = Movie()
            if (videoTracks.size > 0) {
                mergedMovie.addTrack(AppendTrack(*videoTracks.toTypedArray()))
            }

            if (audioTracks.size > 0) {
                mergedMovie.addTrack(AppendTrack(*audioTracks.toTypedArray()))
            }

            val container = DefaultMp4Builder().build(mergedMovie)
            val fileChannel = FileOutputStream(outputFile).channel
            container.writeContainer(fileChannel)
            fileChannel.close()
            progressDialogue.cancel()

            Log.i(TAG,"Videos merged successfully")
            for(file in videoFiles) {
               file.delete()
            }
        } catch (e : Exception) {
            Log.e(TAG, e.message.toString())
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
        if(ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CAMERA_PERMISSION)
        }

        return ActivityCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkPermission(permissions: Array<String>, requestCode: Int) {
        if (ContextCompat.checkSelfPermission(this@MainActivity, permissions[0]) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this@MainActivity, permissions, requestCode)
        } else {
            Toast.makeText(this@MainActivity, "Permission already granted", Toast.LENGTH_SHORT).show()
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {

            // Checking whether user granted the permission or not.
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                // Showing the toast message
                Toast.makeText(this@MainActivity, "Camera Permission Granted", Toast.LENGTH_SHORT)
                    .show()
            } else {
                Toast.makeText(this@MainActivity, "Camera Permission Denied", Toast.LENGTH_SHORT)
                    .show()
            }
        } else if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this@MainActivity, "Storage Permission Granted", Toast.LENGTH_SHORT)
                    .show()
            } else {
                Toast.makeText(this@MainActivity, "Storage Permission Denied", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.e(TAG, "onPause()")
        stopBackgroundThread()

    }

    private fun startBackgroundThread() {
        mBackgroundThreadHandler = HandlerThread("Camera Background")
        mBackgroundThreadHandler!!.start()
        mBackgroundHandler = Handler(mBackgroundThreadHandler!!.looper)
    }

    private fun stopBackgroundThread() {
        mBackgroundThreadHandler!!.quitSafely()
        try {
            mBackgroundThreadHandler!!.join()
            mBackgroundThreadHandler = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    override fun onStop() {
        super.onStop()
        Log.e(TAG, "onStop()")

    }

    override fun onRestart() {
        super.onRestart()
        Log.e(TAG, "onRestart()")

    }

    override fun onResume() {
        super.onResume()
        Log.e(TAG, "onResume()")
        startBackgroundThread()

        if(mIsVideoRecording) {
            mRecording!!.resume()
            mChronometer.start()
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        if(mIsVideoRecording) {
            mRecording!!.stop()
            mChronometer.stop()
        }
    }
    companion object {
        const val TAG = "MainActivity"
        const val REQUEST_CAMERA_PERMISSION: Int = 0
        const val STORAGE_PERMISSION_CODE: Int = 1
        const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}
