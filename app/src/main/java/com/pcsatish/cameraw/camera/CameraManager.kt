package com.pcsatish.cameraw.camera

import android.content.Context
import android.hardware.camera2.CameraManager as AndroidCameraManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val nativeProvider: NativeCameraProvider,
    private val ipProvider: IpCameraProvider
) {
    private val androidCameraManager = context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager
    private val _currentProvider = MutableStateFlow<CameraProvider>(nativeProvider)
    val currentProvider: StateFlow<CameraProvider> = _currentProvider.asStateFlow()

    fun getAvailableCameras(): List<String> {
        return try {
            androidCameraManager.cameraIdList.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun switchProvider(type: ProviderType) {
        _currentProvider.value.close()
        when (type) {
            ProviderType.NATIVE -> _currentProvider.value = nativeProvider
            ProviderType.IP_CAMERA -> _currentProvider.value = ipProvider
        }
    }
}

enum class ProviderType {
    NATIVE,
    IP_CAMERA
}
