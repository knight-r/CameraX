package com.example.camerax

import android.Manifest
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
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
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.TranscoderOptions
import com.otaliastudios.transcoder.common.TrackStatus
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import com.otaliastudios.transcoder.strategy.TrackStrategy
import com.otaliastudios.transcoder.validator.DefaultValidator
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
import java.util.concurrent.Future
import kotlin.math.abs


class MainActivity : AppCompatActivity(), View.OnClickListener {
    private lateinit var mainBinding: ActivityMainBinding
    private var mImageCapture: ImageCapture? = null
    private lateinit var mImageCaptureExecutor: ExecutorService
    private lateinit var mCameraSelector:CameraSelector

    private var mVideoCapture: VideoCapture<Recorder>? = null
    private var mRecording: Recording? = null
    private var quality = Quality.HD
    private val qualitySelector = QualitySelector.from(quality)
    private var recorderBuilder = Recorder.Builder()
    private var recorder = Recorder.Builder().build()
    private  lateinit var mChronometer: Chronometer
    private var isFlashOn: Boolean = false
    private lateinit var camera: Camera
    private var mIsCameraSwitched: Boolean = false
    private lateinit var mVideoFileList: MutableList<File>
    private lateinit var progressDialogue: ProgressDialog
    private var mBackgroundThreadHandler: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null
    private var mTranscodeFuture: Future<Void>? = null
    private var timeWhenPaused: Long  = 0
    private val mTranscodeVideoStrategy: TrackStrategy? = null
    private val mTranscodeAudioStrategy: TrackStrategy? = null
    private var outputFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Merged_VIDEO.mp4")
    private lateinit var mScreenSize: Size
    private var mIsVideoCaptureOn: Boolean = false
    private var mIsFlashOn: Boolean = false
    private var mIsVideoPaused: Boolean = false
    private var mIsVideoRecording: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mainBinding.root)
        progressDialogue = ProgressDialog(this)
        checkPermissions()
        mChronometer = mainBinding.chronometer
        timeWhenPaused = 0
        mImageCaptureExecutor = Executors.newSingleThreadExecutor()

        mVideoFileList = mutableListOf()
        mainBinding.chronometer.visibility = View.GONE
        mainBinding.tvPause.visibility= View.GONE
        mainBinding.rlPauseResumeStop.visibility = View.GONE

        initOnClickListeners()
    }

    /**
     *  all button clickListeners are implemented here
     */
    private fun initOnClickListeners() {
        mainBinding.tvVideo.setOnClickListener(this)
        mainBinding.tvCamera.setOnClickListener(this)
        mainBinding.ibCapturePhoto.setOnClickListener(this)
        mainBinding.tv1xZoom.setOnClickListener(this)
        mainBinding.tv2xZoom.setOnClickListener(this)
        mainBinding.ivFlash.setOnClickListener(this)
        mainBinding.ibPauseResume.setOnClickListener(this)
        mainBinding.ibStop.setOnClickListener(this)
        mainBinding.ivPhotoGallery.setOnClickListener(this)
        mainBinding.ivSwitchCamera.setOnClickListener(this)
    }

    /**
     *  onClick functionalities are implemented here
     */
    override fun onClick(view: View?) {
        when(view?.id ) {
            mainBinding.tvVideo.id -> {
                mIsVideoCaptureOn = true
                mainBinding.ibCapturePhoto.setImageResource(R.drawable.ic_capture_video)

                mainBinding.apply {
                    rlTools.visibility = View.GONE
                    tvVideo.setBackgroundResource(R.drawable.selected_background)
                    tvVideo.setTextColor(Color.BLACK)
                    tvCamera.setBackgroundResource(R.drawable.non_selected_background)
                    tvCamera.setTextColor(Color.WHITE)

                }
            }

            mainBinding.tvCamera.id -> {
                mIsVideoCaptureOn = false

                if (mIsVideoRecording) {
                    stopRecording()
                }
                mIsVideoRecording = false
                mainBinding.apply {
                    ibPauseResume.setImageResource(R.drawable.ic_pause)
                    ivPhotoGallery.setImageResource(R.drawable.ic_photo_gallery)
                    rlPauseResumeStop.visibility = View.GONE
                    tvPause.visibility = View.GONE
                    ibCapturePhoto.visibility = View.VISIBLE
                    ibCapturePhoto.setImageResource(R.drawable.ic_capture_photo)
                    chronometer.visibility = View.GONE
                    rlTools.visibility = View.VISIBLE
                    tvCamera.setBackgroundResource(R.drawable.selected_background)
                    tvCamera.setTextColor(Color.BLACK)
                    tvVideo.setBackgroundResource(R.drawable.non_selected_background)
                    tvVideo.setTextColor(Color.WHITE)

                }

            }
            mainBinding.ibCapturePhoto.id -> {
                if(mIsVideoCaptureOn) {
                    mainBinding.apply {
                        chronometer.visibility = View.VISIBLE
                        ibCapturePhoto.visibility = View.GONE
                        rlPauseResumeStop.visibility = View.VISIBLE
                        ivPhotoGallery.setImageResource(R.drawable.ic_camera)
                    }
                    mIsVideoRecording = true
                    startRecordingVideo()

                } else {
                    takePhoto()

                }
            }
            mainBinding.tv1xZoom.id  -> {
                mainBinding.apply {
                    tv1xZoom.setBackgroundResource(R.drawable.selected_background)
                    tv1xZoom.setTextColor(Color.BLACK)
                    tv2xZoom.setBackgroundResource(R.drawable.non_selected_background)
                    tv2xZoom.setTextColor(Color.WHITE)
                }
            }
            mainBinding.tv2xZoom.id -> {
                mainBinding.apply {
                    tv2xZoom.setBackgroundResource(R.drawable.selected_background)
                    tv2xZoom.setTextColor(Color.BLACK)
                    tv1xZoom.setBackgroundResource(R.drawable.non_selected_background)
                    tv1xZoom.setTextColor(Color.WHITE)
                }
            }

            mainBinding.ivFlash.id -> {
                if(camera.cameraInfo.hasFlashUnit()) {
                    if (mIsFlashOn) {
                        mIsFlashOn = false
                        mainBinding.ivFlash.setBackgroundResource(R.drawable.ic_flash)
                        camera.cameraControl.enableTorch(mIsFlashOn)
                    } else {
                        mIsFlashOn = true
                        mainBinding.ivFlash.setBackgroundResource(R.drawable.ic_flash_on_2)
                        camera.cameraControl.enableTorch(mIsFlashOn)
                    }
                } else {
                    isFlashOn = false
                    mainBinding.ivFlash.setBackgroundResource(R.drawable.ic_flash)
                    Toast.makeText(this, "Flash is not available", Toast.LENGTH_SHORT).show()
                }

            }
            mainBinding.ibPauseResume.id -> {
                if (mIsVideoPaused) {
                    mIsVideoPaused = false
                    mainBinding.ibPauseResume.setImageResource(R.drawable.ic_pause)
                    mainBinding.chronometer.visibility = View.VISIBLE
                    mainBinding.tvPause.visibility = View.GONE
                    mRecording!!.resume()

                } else {
                    mIsVideoPaused = true
                    mainBinding.ibPauseResume.setImageResource(R.drawable.ic_play)
                    mainBinding.chronometer.visibility = View.GONE
                    mainBinding.tvPause.visibility = View.VISIBLE
                    mRecording!!.pause()
                }
            }

            mainBinding.ibStop.id -> {
                mainBinding.apply {
                    rlPauseResumeStop.visibility = View.GONE
                    ibCapturePhoto.visibility = View.VISIBLE
                    chronometer.visibility = View.GONE
                    ivPhotoGallery.setImageResource(R.drawable.ic_photo_gallery)
                    tvPause.visibility = View.GONE
                    ivPhotoGallery.setImageResource(R.drawable.ic_photo_gallery)
                }
               mIsVideoRecording = false
               stopRecording()

            }
            mainBinding.ivPhotoGallery.id -> {
                if( mIsVideoRecording) {
                    takePhoto()
                } else {
                    // show photo gallery
                }
            }

            mainBinding.ivSwitchCamera.id -> {
                mIsCameraSwitched = true
                if (mIsVideoRecording) {
                    stopRecording()
                    switchCamera()
                    startRecordingVideo()

                } else {
                    switchCamera()
                }


            }

        }

    }

    /**
     * method to switch between front and back camera
     */
    private fun switchCamera() {
        if ( mCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            camera.cameraControl.enableTorch(false)
            mIsFlashOn = false
            mCameraSelector =  CameraSelector.DEFAULT_FRONT_CAMERA
            mainBinding.ivFlash.setBackgroundResource(R.drawable.ic_flash)
        } else {
            mCameraSelector =  CameraSelector.DEFAULT_BACK_CAMERA
        }
        startCamera()
    }

    /**
     * method to start camera preview
     */
    private fun startCamera() {
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(mainBinding.cameraPreview.surfaceProvider)
            }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        recorder = Recorder.Builder().build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO
                ),
                REQUEST_CAMERA_PERMISSION
            )
            return
        }

        mVideoCapture = VideoCapture.withOutput(recorder)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            mImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, mCameraSelector,mImageCapture,mVideoCapture,preview)
//                mScreenSize = QualitySelector.getResolution(camera.cameraInfo, Quality.HIGHEST)!!

            } catch (e: Exception) {
                Log.d("MainActivity", "Use case binding failed")
            }

        }, ContextCompat.getMainExecutor(this))

    }

    /**
     * this method will take the photo and save it to Gallery
     */
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
                        val uri = outputFileResults.savedUri
                    }

                    override fun onError(exception: ImageCaptureException) {

                        Log.d("MainActivity", "Error taking photo:$exception")
                    }

                })

            Toast.makeText( this@MainActivity , "The image has been saved to Gallery", Toast.LENGTH_SHORT).show()
            if(!mIsVideoRecording) {
                startCamera()
            }
        }
    }

    /**
     * this method will start the recording of video
     */
    private fun startRecordingVideo() {

        mVideoCapture!!.let {
            try {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.RECORD_AUDIO
                        ),
                        REQUEST_CAMERA_PERMISSION
                    )
                    return
                }
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${System.currentTimeMillis()}")
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
                                mChronometer.base = SystemClock.elapsedRealtime()
                                mChronometer.start()
                            }
                            is VideoRecordEvent.Pause -> {
                                timeWhenPaused = mChronometer.base - SystemClock.elapsedRealtime()
                                val timeStamp = abs(timeWhenPaused)
                                if (timeWhenPaused >= 10) {
                                    mainBinding.tvPause.text = "Paused: 00:${timeWhenPaused.toString()}"
                                } else {
                                    mainBinding.tvPause.text = "Paused: 00:0${(timeStamp/1000).toInt().toString()}"

                                }
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
                                                    Toast.makeText(this,  "File saved to ${videoFile.absolutePath}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                    if(!mIsVideoRecording){
                                        timeWhenPaused = 0
                                        mergeVideosUsingTranscoder(mVideoFileList)
                                    }
                                } else {
                                    mRecording?.close()
                                    mRecording = null
                                    Log.e("MainActivity", "Video capture ends with error: ${recordEvent.error}")
                                }

                            }
                            is VideoRecordEvent.Resume -> {

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
        mRecording!!.stop()
        if(!mIsVideoRecording) {
            startCamera()
        }
    }

    /**
     * this method used for merging multiple videos using Transcoder library
     * @param videoFilesList
     */
    private fun mergeVideosUsingTranscoder(videoFiles: List<File>) {
        progressDialogue.setMessage("Merging Videos..")
        progressDialogue.show()
        val builder: TranscoderOptions.Builder =
            Transcoder.into(outputFile.absolutePath)
        for(videoFile in videoFiles) {
            builder.addDataSource(videoFile.absolutePath)
        }

        // use DefaultVideoStrategy.exact(2560, 1440).build()  to restore 75% size of the video
        //  use DefaultVideoStrategy.exact(mScreenSize.height, mScreenSize.width).build()  to restore 50% size of the video

        val strategy: DefaultVideoStrategy = DefaultVideoStrategy.exact(2560, 1440).build()

        mTranscodeFuture = builder
            .setAudioTrackStrategy(DefaultAudioStrategy.builder().build())
            .setVideoTrackStrategy(strategy)
            .setVideoRotation(0)
            .setListener(object : TranscoderListener{

                override fun onTranscodeProgress(progress: Double) {}

                override fun onTranscodeCompleted(successCode: Int) {
                    progressDialogue.cancel()
                    Toast.makeText(this@MainActivity, "Video Merged Successfully", Toast.LENGTH_SHORT).show()

                }

                override fun onTranscodeCanceled() {
                    Toast.makeText(this@MainActivity, "Video merge cancelled", Toast.LENGTH_SHORT).show()
                }

                override fun onTranscodeFailed(exception: Throwable) {
                    Toast.makeText(this@MainActivity, exception.message, Toast.LENGTH_SHORT).show()
                }

            })
            .setValidator(object : DefaultValidator() {
                override fun validate(videoStatus: TrackStatus, audioStatus: TrackStatus): Boolean {
                    //  mIsAudioOnly = !videoStatus.isTranscoding
                    return super.validate(videoStatus, audioStatus)
                }

            }).transcode()

    }


    /**
     * this method will check camera and other required permission to run the app
     */
    private  fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            mCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            startCamera()

        } else {
            if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE )) {
                Toast.makeText(this, "app needs permission to be able to save videos", Toast.LENGTH_SHORT)
                    .show()
            }
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA,Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO,Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_CAMERA_PERMISSION
            )
        }

    }

    /**
     * this method receives the status of the permissions granted
     * @param1 requestCode
     * @param2: permissions
     * @param3: grantResults
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            for(results in grantResults) {
                if (results == PackageManager.PERMISSION_DENIED) {
                    // close the app
                    Toast.makeText(
                        this@MainActivity,
                        "Sorry!!!, you can't use this app without granting permission",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }

    }

    override fun onPause() {
        super.onPause()
        Log.e(TAG, "onPause()")
        stopBackgroundThread()

    }

    /**
     * this will start the background thread to run the processes
     */
    private fun startBackgroundThread() {
        mBackgroundThreadHandler = HandlerThread("Camera Background")
        mBackgroundThreadHandler!!.start()
        mBackgroundHandler = Handler(mBackgroundThreadHandler!!.looper)
    }

    /**
     * this will stop the background thread if all the background processes are executed
     */
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
        if(mIsVideoRecording) {
            mIsVideoRecording = false
            stopRecording()
        }
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