package com.whatap.apk2project.deobfuscator.monitor.gpu

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Linux GPU 샘플러
 *
 * /sys/class/drm, nvidia-smi 등을 사용하여 GPU 정보를 가져옵니다
 */
class LinuxGpuSampler : GpuSampler {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun getGpuInfo(): GpuInfo? {
        // TODO: Linux GPU 구현 필요
        // nvidia-smi, AMD GPU, Intel GPU 등 다양한 방법 고려 필요
        logger.debug("Linux GPU sampling not yet implemented")
        return null
    }

    override fun isAvailable(): Boolean {
        // Linux인지 확인
        return System.getProperty("os.name").lowercase().contains("linux")
    }
}

/**
 * Windows GPU 샘플러
 *
 * WMI, PowerShell 등을 사용하여 GPU 정보를 가져옵니다
 */
class WindowsGpuSampler : GpuSampler {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun getGpuInfo(): GpuInfo? {
        // TODO: Windows GPU 구현 필요
        logger.debug("Windows GPU sampling not yet implemented")
        return null
    }

    override fun isAvailable(): Boolean {
        return System.getProperty("os.name").lowercase().contains("windows")
    }
}

/**
 * Mock GPU 샘플러 (테스트용)
 *
 * 테스트 시 사용할 수 있는 Mock 구현
 */
class MockGpuSampler(
    private val mockGpuInfo: GpuInfo = GpuInfo(0.0, 0, 0)
) : GpuSampler {
    override fun getGpuInfo(): GpuInfo {
        return mockGpuInfo
    }

    override fun isAvailable(): Boolean = true
}
