package com.mayank.twiliopoc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import com.google.android.material.snackbar.Snackbar
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import com.twilio.audioswitch.AudioSwitch
import com.twilio.video.*
import kotlinx.android.synthetic.main.activity_main.*
import tvi.webrtc.Camera1Enumerator
import tvi.webrtc.Camera2Enumerator

class MainActivity : AppCompatActivity(), RemoteParticipant.Listener, LocalParticipant.Listener,
    View.OnClickListener {

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
        private const val MICROPHONE_TRACK_NAME = "microphone"
    }

    private val mTAG = MainActivity::class.simpleName
    private val audioSwitch by lazy {
        AudioSwitch(
            application, loggingEnabled = true,
            preferredDeviceList = listOf(
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Speakerphone::class.java,
                AudioDevice.Earpiece::class.java
            )
        )
    }
    private lateinit var localVideoView: VideoView
    private val token: String =
        "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiIsImN0eSI6InR3aWxpby1mcGE7dj0xIn0.eyJqdGkiOiJTSzYxNDk0ZjEwOWE4YzM3NmVhNDQ5ODIzODhmZmMzN2JlLTE2MTc2MDcwMTUiLCJpc3MiOiJTSzYxNDk0ZjEwOWE4YzM3NmVhNDQ5ODIzODhmZmMzN2JlIiwic3ViIjoiQUNmNThkZDRlYjNjYjgxMDU4OGE2OTAxZjgyMjIwMzQ5NCIsImV4cCI6MTYxNzYxMDYxNSwiZ3JhbnRzIjp7ImlkZW50aXR5IjoiTmFzZWViIFNpbmdoIiwidmlkZW8iOnsicm9vbSI6IkRhaWx5U3RhbmR1cCJ9fX0.Jdl_QWZkzduew_NocIcjANd4FigEE5zfEGmvKLAMVB4"
    private val roomName: String = "DailyStandup"
    private var isCamera2ApiSupported = false
    private val frontCameraId by lazy {
        when {
            !isCamera2ApiSupported -> {
                val camera1Enumerator = Camera1Enumerator()
                val cameraId =
                    camera1Enumerator.deviceNames.find { camera1Enumerator.isFrontFacing(it) }
                requireNotNull(cameraId)
            }
            else -> {
                val camera2Enumerator = Camera2Enumerator(this)
                val cameraId =
                    camera2Enumerator.deviceNames.find { camera2Enumerator.isFrontFacing(it) }
                requireNotNull(cameraId)
            }
        }
    }
    private var isCameraOn = true
    private var isMicOn = true
    private var selfPreview = false
    private var isFrontCamera = true
    private var enableAudio = true
    private var remoteVideoTrack: RemoteVideoTrack? = null
    private var localParticipant: LocalParticipant? = null
    private var localVideoTrack: LocalVideoTrack? = null
    private var localAudioTrack: LocalAudioTrack? = null
    private lateinit var room: Room

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        localVideoView = findViewById<View>(R.id.thumbnail_video_view) as VideoView
        end.setOnClickListener(this)
        camera_off.setOnClickListener(this)
        mic_off.setOnClickListener(this)
        thumbnail_video_view.setOnClickListener(this)
        addVideoIfPermissionGranted()
    }

    private fun addVideoIfPermissionGranted() {
        when {
            !checkPermissionForCamera() -> {
                requestPermissionForCamera()
            }
            else -> {
                checkCamera2Support()
                buildLocalVideoTrack(frontCameraId)
                setupLocalAudioTrack()
                setAudio()
                addFrontVideo()
                room = connect(this, token, roomListener(), roomName)
            }
        }
    }

    private fun setAudio() {
        audioSwitch.start(object : AudioDeviceChangeListener {
            override fun invoke(
                audioDevices: List<AudioDevice>,
                selectedAudioDevice: AudioDevice?
            ) {
                Log.d("TAG", "invoke: ${selectedAudioDevice?.name}")
            }
        })
        val audioManager: AudioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = true
        audioSwitch.activate()
    }

    private fun buildLocalVideoTrack(cameraId: String) {
        localVideoTrack = when {
            isCamera2ApiSupported -> {
                val cameraCapture = Camera2Capturer(this, cameraId)
                cameraCapture.changeCaptureFormat(1080, 1920, 60)
                LocalVideoTrack.create(this, enableAudio, cameraCapture)
            }
            else -> {
                val cameraCapture = CameraCapturer(this, cameraId)
                cameraCapture.changeCaptureFormat(1080, 1920, 60)
                LocalVideoTrack.create(this, enableAudio, cameraCapture)
            }
        }
    }

    private fun checkCamera2Support() {
        var numberOfCameras = 0
        val manager: CameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            numberOfCameras = manager.cameraIdList.size
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: AssertionError) {
            e.printStackTrace()
        }

        if (numberOfCameras == 0) {
            Log.d("TAG", "0 cameras")
        } else {
            for (i in 0.until(numberOfCameras)) {
                isCamera2ApiSupported = if (!allowCamera2Support(i)) {
                    Log.d(
                        "TAG",
                        "camera $i doesn't have limited or full support for Camera2 API"
                    )
                    false
                } else {
                    true
                }
            }
        }
    }

    private fun allowCamera2Support(cameraId: Int): Boolean {
        val manager: CameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraIdS = manager.cameraIdList[cameraId]
            val characteristics = manager.getCameraCharacteristics(cameraIdS)
            val support =
                characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)

            when (support) {
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> {
                    isCamera2ApiSupported = false
                }
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> {
                    isCamera2ApiSupported = false
                }
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> {
                    isCamera2ApiSupported = true
                }
                else -> Log.d("TAG", "Camera $cameraId has unknown Camera2 support?!")
            }

            return support == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED || support == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        return false
    }

    private fun roomListener(): Room.Listener {
        return object : Room.Listener {
            override fun onConnected(room: Room) {
                setUpUser()
            }

            override fun onConnectFailure(room: Room, twilioException: TwilioException) {
                Log.d("TAG", "onConnectFailure: ${room.name}")
                connect(this@MainActivity, token, this, roomName)
            }

            override fun onReconnecting(room: Room, twilioException: TwilioException) {
                Log.d("TAG", "onReconnecting: ${room.name}")
                Snackbar.make(
                    remote_video,
                    "Connection lost! Reconnecting....",
                    Snackbar.LENGTH_SHORT
                ).show()
            }

            override fun onReconnected(room: Room) {
                Log.d("TAG", "onReconnected: ${room.name}")
            }

            override fun onDisconnected(room: Room, twilioException: TwilioException?) {
                Log.d("TAG", "onDisconnected: ${room.name}")
            }

            override fun onParticipantConnected(
                room: Room,
                remoteParticipant: RemoteParticipant
            ) {
                remoteParticipant.setListener(this@MainActivity)
            }

            override fun onParticipantDisconnected(
                room: Room,
                remoteParticipant: RemoteParticipant
            ) {
                remote_video.clearImage()
                finish()
                Log.d(mTAG, "onParticipantDisconnected: ${room.name}")
            }

            override fun onRecordingStarted(room: Room) {
                Log.d(mTAG, "onRecordingStarted: ${room.name}")
            }

            override fun onRecordingStopped(room: Room) {
                Log.d(mTAG, "onRecordingStopped: ${room.name}")
            }

        }
    }

    private fun setUpUser() {
        pg.visibility = View.GONE
        end.visibility = View.VISIBLE

        if (room.remoteParticipants.size == 0) {
            Snackbar.make(
                remote_video,
                "You're here alone, wait for other user's to join",
                Snackbar.LENGTH_LONG
            ).show()
        }

        try {
            publishLocalTracks()
            room.localParticipant?.setListener(this@MainActivity)
            localParticipant = room.localParticipant
            val remoteParticipant = room.remoteParticipants[0]
            remoteParticipant?.setListener(this@MainActivity)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun publishLocalTracks() {
        publishAudioTrack(localAudioTrack)
    }

    private fun connect(
        context: Context, token: String?,
        roomListener: Room.Listener, roomName: String?
    ): Room {
        return Video.connect(
            context, ConnectOptions.Builder(token ?: "")
                .roomName(roomName ?: "")
                .audioTracks(listOf(localAudioTrack))
                .videoTracks(listOf(localVideoTrack))
                .build(), roomListener
        )
    }

    private fun setupLocalAudioTrack() {
        val audioOptionsBuilder = AudioOptions.Builder()
            .echoCancellation(true)
            .noiseSuppression(true)
            .build()

        if (localAudioTrack == null) {
            localAudioTrack = createLocalAudioTrack(
                context = this,
                enabled = enableAudio,
                name = MICROPHONE_TRACK_NAME,
                audioOptionsBuilder = audioOptionsBuilder
            )
            localAudioTrack?.let { publishAudioTrack(it) }
                ?: Log.e(RuntimeException().toString(), "Failed to create local audio track")
        }
    }

    private fun publishAudioTrack(localAudioTrack: LocalAudioTrack?) {
        localAudioTrack?.let { localParticipant?.publishTrack(it) }
    }

    private fun unPublishAudioTrack(localAudioTrack: LocalAudioTrack?) =
        localAudioTrack?.let { localParticipant?.unpublishTrack(it) }

    private fun unPublishVideoTrack(localVideoTrack: LocalVideoTrack?) =
        localVideoTrack?.let { localParticipant?.unpublishTrack(it) }

    private fun publishVideoTrack(localVideoTrack: LocalVideoTrack?) =
        localVideoTrack?.let { localParticipant?.publishTrack(it) }

    private fun createLocalAudioTrack(
        context: Context,
        enabled: Boolean,
        name: String? = null,
        audioOptionsBuilder: AudioOptions
    ) = LocalAudioTrack.create(
        context, enabled, audioOptionsBuilder, name
    )

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            var cameraPermissionGranted = true
            for (grantResult in grantResults) {
                cameraPermissionGranted =
                    cameraPermissionGranted and (grantResult == PackageManager.PERMISSION_GRANTED)
            }
            addVideoIfPermissionGranted()
        }
    }

    override fun onDestroy() {
        localVideoTrack?.removeSink(localVideoView)
        localVideoTrack?.release()
        audioSwitch.stop()
        localAudioTrack?.release()
        super.onDestroy()
    }

    private fun addFrontVideo() {
        localVideoTrack?.addSink(localVideoView)
    }

    private fun switchPreview() {
        if (!selfPreview) {
            selfPreview = true
            thumbnail_video_view.clearImage()
            remote_video?.clearImage()
            localVideoTrack?.removeSink(thumbnail_video_view)
            localVideoTrack?.addSink(remote_video)
            remoteVideoTrack?.removeSink(remote_video)
            remoteVideoTrack?.addSink(thumbnail_video_view)
        } else {
            selfPreview = false
            thumbnail_video_view.clearImage()
            remote_video?.clearImage()
            localVideoTrack?.removeSink(remote_video)
            localVideoTrack?.addSink(thumbnail_video_view)
            remoteVideoTrack?.removeSink(thumbnail_video_view)
            remoteVideoTrack?.addSink(remote_video)
        }
    }

    private fun setCameraOnOff() {
        selfPreview = true
        switchPreview()
        isCameraOn = if (isCameraOn) {
            camera_back.setImageDrawable(
                ContextCompat.getDrawable(
                    this,
                    R.drawable.ic_action_camera_off
                )
            )
            camera_back.setPadding(80)
            thumbnail_video_view.visibility = View.GONE
            unPublishVideoTrack(localVideoTrack)
            false
        } else {
            camera_back.setImageDrawable(
                ContextCompat.getDrawable(
                    this,
                    R.drawable.ic_action_camera_on
                )
            )
            camera_back.setPadding(80)
            thumbnail_video_view.visibility = View.VISIBLE
            publishVideoTrack(localVideoTrack)
            true
        }
    }

    private fun setMicOnOff() {
        isMicOn = if (isMicOn) {
            mic_back.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_action_mic_off))
            mic_back.setPadding(80)
            unPublishAudioTrack(localAudioTrack)
            false
        } else {
            mic_back.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_action_mic_on))
            mic_back.setPadding(80)
            publishAudioTrack(localAudioTrack)
            true
        }
    }

    private fun disconnect() {
        room.disconnect()
        remote_video.clearImage()
        finish()
    }

    private fun checkPermissionForCamera(): Boolean {
        val isPermissionsGranted: Boolean
        val resultCamera =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val resultMicrophone =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        isPermissionsGranted =
            resultMicrophone == PackageManager.PERMISSION_GRANTED && resultCamera == PackageManager.PERMISSION_GRANTED
        return isPermissionsGranted
    }

    private fun requestPermissionForCamera() {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    override fun onAudioTrackPublished(
        remoteParticipant: RemoteParticipant,
        remoteAudioTrackPublication: RemoteAudioTrackPublication
    ) {
        Log.d(mTAG, "onAudioTrackPublished: ${remoteAudioTrackPublication.audioTrack}")
    }

    override fun onAudioTrackUnpublished(
        remoteParticipant: RemoteParticipant,
        remoteAudioTrackPublication: RemoteAudioTrackPublication
    ) {
        Log.d(mTAG, "onAudioTrackUnpublished: ${remoteAudioTrackPublication.audioTrack}")
    }

    override fun onAudioTrackSubscribed(
        remoteParticipant: RemoteParticipant,
        remoteAudioTrackPublication: RemoteAudioTrackPublication,
        remoteAudioTrack: RemoteAudioTrack
    ) {
        Log.d(mTAG, "onAudioTrackSubscribed: ${remoteAudioTrack.name}")
        remoteAudioTrack.enablePlayback(true)
    }

    override fun onVideoTrackSubscribed(
        remoteParticipant: RemoteParticipant,
        remoteVideoTrackPublication: RemoteVideoTrackPublication,
        remoteVideoTrack: RemoteVideoTrack
    ) {
        Log.d(mTAG, "onVideoTrackSubscribed: ${remoteVideoTrack.name}")
        remote_video.mirror = true
        remoteVideoTrack.addSink(remote_video)
        Snackbar.make(
            remote_video,
            remoteParticipant.identity.plus("Joined"),
            Snackbar.LENGTH_LONG
        ).show()
        this.remoteVideoTrack = remoteVideoTrack
        username.text = remoteParticipant.identity
    }

    override fun onAudioTrackSubscriptionFailed(
        remoteParticipant: RemoteParticipant,
        remoteAudioTrackPublication: RemoteAudioTrackPublication,
        twilioException: TwilioException
    ) {
        Log.d(mTAG, "onAudioTrackSubscriptionFailed: ${twilioException.message}")
    }

    override fun onAudioTrackUnsubscribed(
        remoteParticipant: RemoteParticipant,
        remoteAudioTrackPublication: RemoteAudioTrackPublication,
        remoteAudioTrack: RemoteAudioTrack
    ) {
        Log.d(mTAG, "onAudioTrackUnsubscribed: ${remoteAudioTrack.name}")
    }

    override fun onVideoTrackPublished(
        remoteParticipant: RemoteParticipant,
        remoteVideoTrackPublication: RemoteVideoTrackPublication
    ) {
        Log.d(mTAG, "onVideoTrackPublished: ${remoteVideoTrackPublication.videoTrack}")
    }

    override fun onVideoTrackUnpublished(
        remoteParticipant: RemoteParticipant,
        remoteVideoTrackPublication: RemoteVideoTrackPublication
    ) {
        Log.d(mTAG, "onVideoTrackUnpublished: ${remoteVideoTrackPublication.trackName}")
    }

    override fun onVideoTrackSubscriptionFailed(
        remoteParticipant: RemoteParticipant,
        remoteVideoTrackPublication: RemoteVideoTrackPublication,
        twilioException: TwilioException
    ) {
        Log.d(mTAG, "onVideoTrackSubscriptionFailed: ${twilioException.message}")
    }

    override fun onVideoTrackUnsubscribed(
        remoteParticipant: RemoteParticipant,
        remoteVideoTrackPublication: RemoteVideoTrackPublication,
        remoteVideoTrack: RemoteVideoTrack
    ) {
        Log.d(mTAG, "onVideoTrackUnsubscribed: ${remoteVideoTrack.name}")
        remote_video.clearImage()
        remoteVideoTrack.removeSink(remote_video)
    }

    override fun onDataTrackPublished(
        remoteParticipant: RemoteParticipant,
        remoteDataTrackPublication: RemoteDataTrackPublication
    ) {
        Log.d(mTAG, "onDataTrackPublished: ${remoteDataTrackPublication.trackName}")
    }

    override fun onDataTrackUnpublished(
        remoteParticipant: RemoteParticipant,
        remoteDataTrackPublication: RemoteDataTrackPublication
    ) {
        Log.d(mTAG, "onDataTrackUnpublished: ${remoteDataTrackPublication.trackName}")
    }

    override fun onDataTrackSubscribed(
        remoteParticipant: RemoteParticipant,
        remoteDataTrackPublication: RemoteDataTrackPublication,
        remoteDataTrack: RemoteDataTrack
    ) {
        Log.d(mTAG, "onDataTrackSubscribed: ${remoteDataTrack.name}")
    }

    override fun onDataTrackSubscriptionFailed(
        remoteParticipant: RemoteParticipant,
        remoteDataTrackPublication: RemoteDataTrackPublication,
        twilioException: TwilioException
    ) {
        Log.d(mTAG, "onDataTrackSubscriptionFailed: ${twilioException.message}")
    }

    override fun onDataTrackUnsubscribed(
        remoteParticipant: RemoteParticipant,
        remoteDataTrackPublication: RemoteDataTrackPublication,
        remoteDataTrack: RemoteDataTrack
    ) {
        Log.d(mTAG, "onDataTrackUnsubscribed: ${remoteDataTrack.name}")
    }

    override fun onAudioTrackEnabled(
        remoteParticipant: RemoteParticipant,
        remoteAudioTrackPublication: RemoteAudioTrackPublication
    ) {
        Log.d(mTAG, "onAudioTrackEnabled: ${remoteAudioTrackPublication.trackName}")
        remoteAudioTrackPublication.remoteAudioTrack?.enablePlayback(true)
    }

    override fun onAudioTrackDisabled(
        remoteParticipant: RemoteParticipant,
        remoteAudioTrackPublication: RemoteAudioTrackPublication
    ) {
        Log.d(mTAG, "onAudioTrackDisabled: ${remoteAudioTrackPublication.trackName}")
    }

    override fun onVideoTrackEnabled(
        remoteParticipant: RemoteParticipant,
        remoteVideoTrackPublication: RemoteVideoTrackPublication
    ) {
        Log.d(mTAG, "onVideoTrackEnabled: ${remoteVideoTrackPublication.trackName}")
    }

    override fun onVideoTrackDisabled(
        remoteParticipant: RemoteParticipant,
        remoteVideoTrackPublication: RemoteVideoTrackPublication
    ) {
        Log.d(mTAG, "onVideoTrackDisabled: ${remoteVideoTrackPublication.trackName}")
    }

    override fun onAudioTrackPublished(
        localParticipant: LocalParticipant,
        localAudioTrackPublication: LocalAudioTrackPublication
    ) {
        Log.d(mTAG, "onAudioTrackPublished: ${localAudioTrackPublication.audioTrack}")
    }

    override fun onAudioTrackPublicationFailed(
        localParticipant: LocalParticipant,
        localAudioTrack: LocalAudioTrack,
        twilioException: TwilioException
    ) {
        Log.d(mTAG, "onAudioTrackPublicationFailed: ${twilioException.message}")
    }

    override fun onVideoTrackPublished(
        localParticipant: LocalParticipant,
        localVideoTrackPublication: LocalVideoTrackPublication
    ) {
        Log.d(mTAG, "onVideoTrackPublished: ${localVideoTrackPublication.trackName}")
    }

    override fun onVideoTrackPublicationFailed(
        localParticipant: LocalParticipant,
        localVideoTrack: LocalVideoTrack,
        twilioException: TwilioException
    ) {
        Log.d(mTAG, "onVideoTrackPublicationFailed: ${twilioException.message}")
    }

    override fun onDataTrackPublished(
        localParticipant: LocalParticipant,
        localDataTrackPublication: LocalDataTrackPublication
    ) {
        Log.d(mTAG, "onDataTrackPublished: ${localDataTrackPublication.trackName}")
    }

    override fun onDataTrackPublicationFailed(
        localParticipant: LocalParticipant,
        localDataTrack: LocalDataTrack,
        twilioException: TwilioException
    ) {
        Log.d(mTAG, "onDataTrackPublicationFailed: ${twilioException.message}")
    }


    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.end -> {
                disconnect()
            }
            R.id.camera_off -> {
                setCameraOnOff()
            }
            R.id.mic_off -> {
                setMicOnOff()
            }
            R.id.thumbnail_video_view -> {
                switchPreview()
            }
        }
    }

}
