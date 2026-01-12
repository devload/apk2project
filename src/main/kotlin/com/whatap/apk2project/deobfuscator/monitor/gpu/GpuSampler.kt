package com.whatap.apk2project.deobfuscator.monitor.gpu

/**
 * GPU 정보 데이터 클래스
 */
data class GpuInfo(
    val usagePercent: Double,        // GPU 사용률 (0-100)
    val memoryUsedMb: Long,          // 사용 중인 메모리 (MB)
    val memoryTotalMb: Long,         // 전체 메모리 (MB)
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * GPU 샘플링 인터페이스
 *
 * 플랫폼별로 구현하여 크로스 플랫폼 GPU 모니터링 지원
 */
interface GpuSampler {
    /**
     * 현재 GPU 정보를 가져옵니다
     *
     * @return GPU 정보, 샘플링 실패시 null 또는 기본값(0, 0, 0)
     */
    fun getGpuInfo(): GpuInfo?

    /**
     * GPU 샘플러가 사용 가능한지 확인합니다
     */
    fun isAvailable(): Boolean
}

/**
 * GPU 샘플러 팩토리
 */
object GpuSamplerFactory {
    /**
     * 현재 플랫폼에 맞는 GPU 샘플러를 반환합니다
     */
    fun create(): GpuSampler {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") -> MacGpuSampler()
            os.contains("linux") -> LinuxGpuSampler()
            os.contains("windows") -> WindowsGpuSampler()
            else -> MockGpuSampler() // 지원하지 않는 플랫폼
        }
    }

    /**
     * 테스트용 Mock GPU 샘플러 생성
     */
    fun createMock(gpuInfo: GpuInfo = GpuInfo(0.0, 0, 0)): GpuSampler {
        return MockGpuSampler(gpuInfo)
    }
}
