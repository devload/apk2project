package com.whatap.apk2project.deobfuscator.monitor.gpu

import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Mac Apple Silicon GPU 샘플러
 *
 * ioreg 명령어를 사용하여 GPU 정보를 가져옵니다
 */
class MacGpuSampler : GpuSampler {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun getGpuInfo(): GpuInfo? {
        return try {
            val process = ProcessBuilder("ioreg", "-r", "-d", "1", "-c", "IOAccelerator")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exited = process.waitFor(500, TimeUnit.MILLISECONDS)

            if (!exited) {
                logger.warn("ioreg command timed out")
                return null
            }

            // PerformanceStatistics에서 파싱
            // "Device Utilization %"=98
            val gpuMatch = Regex("\"Device Utilization %\"=(\\d+)").find(output)
            val usagePercent = gpuMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

            // "In use system memory"=20151549952 (NOT "In use system memory (driver)")
            // 정확한 키만 매칭하기 위해 뒤에 }나 ,가 오는 패턴 사용
            val memUsedMatch = Regex("\"In use system memory\"=(\\d+)[,}]").find(output)
            val memoryUsedBytes = memUsedMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val memoryUsedMb = memoryUsedBytes / (1024 * 1024)

            // "Alloc system memory"=37525897216
            val memTotalMatch = Regex("\"Alloc system memory\"=(\\d+)").find(output)
            val memoryTotalBytes = memTotalMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val memoryTotalMb = memoryTotalBytes / (1024 * 1024)

            GpuInfo(
                usagePercent = usagePercent,
                memoryUsedMb = memoryUsedMb,
                memoryTotalMb = memoryTotalMb
            )
        } catch (e: Exception) {
            logger.debug("GPU sampling failed: ${e.message}")
            null
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("ioreg", "-r", "-d", "1", "-c", "IOAccelerator")
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(2, TimeUnit.SECONDS)
            exited && process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }
}
