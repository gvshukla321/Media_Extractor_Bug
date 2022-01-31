package com.comfyapptech.vcut_testing

import android.Manifest
import android.animation.TimeAnimator
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaCodecList.REGULAR_CODECS
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract.Attendees.query
import android.provider.CalendarContract.EventDays.query
import android.provider.MediaStore
import android.system.Os.close
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContentResolverCompat.query
import com.comfyapptech.vcut_testing.ui.theme.VCut_testingTheme
import java.io.FileDescriptor
import java.lang.Exception
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.time.minutes

class MainActivity : ComponentActivity() {

    private var mIntent: Intent? = null
    private lateinit var mMediaCodec : MediaCodec
    private var mMediaExtractor : MediaExtractor = MediaExtractor()
    private var mVideoUri: Uri? = null
    private var mTimeAnimator: TimeAnimator = TimeAnimator()
    private lateinit var mTextureView: TextureView
    private lateinit var mTrackFormat: MediaFormat
    private lateinit var mSurface: Surface
    private var inputBufferIndex: Int = 0
    private var outputBufferindex: Int = 0
    private var outputBufferInfo: MediaCodec.BufferInfo = MediaCodec.BufferInfo()
    private var mHeight: Int = 0
    private var mWidth: Int = 0
    private var mResultHeight: Int = 0
    private var mResultWidth: Int = 0

    var videoPickerIntent = Intent().apply{
        action = Intent.ACTION_GET_CONTENT
        type = "*/*"
        var mimeTypes = arrayOf("image/*", "video/*")
        putExtra(Intent.EXTRA_MIME_TYPES,mimeTypes)
    }

    private val startForResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult -> if (result.resultCode == Activity.RESULT_OK) {
        mIntent = result?.data
        mVideoUri = mIntent?.data
        //print("video uri is "+mVideoUri)
        startDecodingCode()
    }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VCut_testingTheme {
                // A surface container using the 'background' color from the theme
                Surface(color = MaterialTheme.colors.background) {
                    val context = LocalContext.current
                    val mTextureViewValue = remember {
                        TextureView(context)
                    }
                    mTextureView = mTextureViewValue
                    Column() {
                        Box(
                            Modifier
                                .size(400.dp, 300.dp)
                                .background(Color.Red)
                        ){
                            AndroidView(
                                modifier = Modifier.fillMaxSize(), // Occupy the max size in the Compose UI tree
                                factory = {
                                    mTextureViewValue
                                },
                                update = {
                                }
                            )
                        }
                        Button(
                            onClick = {
                                startDecodingCode()
                                //startForResult.launch(videoPickerIntent)
                            },
                            // Uses ButtonDefaults.ContentPadding by default
                            contentPadding = PaddingValues(
                                start = 20.dp,
                                top = 12.dp,
                                end = 20.dp,
                                bottom = 12.dp
                            )
                        ) {
                            // Inner content including an icon and a text label
                            Icon(
                                Icons.Filled.Favorite,
                                contentDescription = "Favorite",
                                modifier = Modifier.size(ButtonDefaults.IconSize)
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("Add a video file")
                        }


                    }
                }
            }
        }
        val requestPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted: Boolean ->
                if (isGranted) {

                } else {
                    // Explain to the user that the feature is unavailable because the
                    // features requires a permission that the user has denied. At the
                    // same time, respect the user's decision. Don't link to system
                    // settings in an effort to convince the user to change their
                    // decision.
                }

            }
        requestPermissionLauncher.launch(
            Manifest.permission.READ_EXTERNAL_STORAGE)
        startDecodingCode()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun startDecodingCode(){

        // Need the READ_EXTERNAL_STORAGE permission if accessing video files that your
// app didn't create.

        // Container for information about each video.
        data class Video(val uri: Uri,
                         val name: String,
                         val duration: Int,
                         val size: Int
        )
        val videoList = mutableListOf<Video>()

        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL
                )
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )

// Show only videos that are at least 5 minutes in duration.
        val selection = "${MediaStore.Video.Media.DURATION} >= ?"
        val selectionArgs = arrayOf(
            TimeUnit.MILLISECONDS.convert(2, TimeUnit.MINUTES).toString()
        )

// Display videos in alphabetical order based on their display name.
        val sortOrder = "${MediaStore.Video.Media.DISPLAY_NAME} ASC"

        val query = contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )
        query?.use { cursor ->
            // Cache column indices.
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                // Get values of columns for a given video.
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val duration = cursor.getInt(durationColumn)
                val size = cursor.getInt(sizeColumn)

                val contentUri: Uri =
                    ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )


               // if(mVideoUri == null)
                    mVideoUri = contentUri

                // Stores column values and the contentUri in a local object
                // that represents the media file.
                videoList += Video(contentUri, name, duration, size)
            }
        }

        try{
            //mMediaExtractor.setDataSource(parcelFd!!.fileDescriptor,parcelFd!!.startOffset,parcelFd!!.length)
            mMediaExtractor.setDataSource(this,mVideoUri!!,null)
            var nTracks = mMediaExtractor.trackCount

            for(i in 0 until nTracks){
                mMediaExtractor.unselectTrack(i)
            }

            for(i in 0 until nTracks){
                val trackFormat: MediaFormat = mMediaExtractor.getTrackFormat(i)
                val mimeType : String? = trackFormat.getString(MediaFormat.KEY_MIME)
                if(mimeType?.contains("video/") == true){
                    mTrackFormat = trackFormat
                    mMediaExtractor.selectTrack(i)
                    /*var mMediaCodecList: MediaCodecList = MediaCodecList(REGULAR_CODECS)
                    var mCodecName: String = mMediaCodecList.findDecoderForFormat(mTrackFormat)
                    mMediaCodec = MediaCodec.createByCodecName(mCodecName)*/
                    mMediaCodec = MediaCodec.createDecoderByType(mimeType)
                    while(mTextureView.surfaceTexture == null){

                    }
                    mSurface = Surface(mTextureView.surfaceTexture)
                    mMediaCodec.configure(trackFormat,mSurface,null,0)
                    mMediaCodec.start()
                    break
                }
            }

            mTimeAnimator.setTimeListener(TimeAnimator.TimeListener(
                fun(animation: TimeAnimator, totalTime: Long, deltaTime: Long){
                    var eos: Boolean = ((mMediaExtractor.sampleFlags and  MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    // if end of source file not reached
                    if(!eos){
                        var result: Boolean = writeSample()
                        if(result) mMediaExtractor.advance()
                    }

                    outputBufferInfo = MediaCodec.BufferInfo()
                    peekSample()

                    if(outputBufferindex < 0) outputBufferInfo = MediaCodec.BufferInfo()

                    if(outputBufferInfo.size <= 0 && eos){
                        mTimeAnimator.end()
                        mMediaCodec.stop()
                        mMediaCodec.release()
                        mMediaExtractor.release()
                    }
                 //   else if(outputBufferInfo.presentationTimeUs / 1000 < totalTime){
                        popSample(true)
                // }
                }
            ))
            mTimeAnimator.start()
        }
        catch(e: Exception){
            e.printStackTrace()
        }

    }

    private fun writeSample(): Boolean{
        inputBufferIndex = mMediaCodec.dequeueInputBuffer(0)
        println("The write input index is " + inputBufferIndex)
        return if(inputBufferIndex == -1){
            false
        }else{

            var flag: Int = 0
            var mBuffer: ByteBuffer? = mMediaCodec.getInputBuffer(inputBufferIndex)
            var size: Int = mMediaExtractor.readSampleData(mBuffer!!,0)
            if(size <= 0){
                flag = mMediaExtractor.sampleFlags or MediaCodec.BUFFER_FLAG_END_OF_STREAM
            }
            mMediaCodec.queueInputBuffer(inputBufferIndex,0,size,mMediaExtractor.sampleTime,flag)
            true
        }
    }

    private fun peekSample(){
        outputBufferindex = mMediaCodec.dequeueOutputBuffer(outputBufferInfo,0)
        println("The peek output index is " + outputBufferindex)
    }


    private fun popSample(render: Boolean){
        if(outputBufferindex >= 0){
            try{
                println("The release output index is " + outputBufferindex)
                mMediaCodec.releaseOutputBuffer(outputBufferindex,render)
            }
            catch(e: Exception){
                println("Exception is " + e)
            }
        }
    }

}


@Composable
fun Greeting(name: String) {
    Text(text = "Hello $name!")
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    VCut_testingTheme {
        Greeting("Android")
    }
}