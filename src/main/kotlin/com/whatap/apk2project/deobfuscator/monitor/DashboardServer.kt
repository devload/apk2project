package com.whatap.apk2project.deobfuscator.monitor

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/**
 * 대시보드 HTTP 서버
 * status.json과 dashboard.html을 제공
 */
class DashboardServer(
    private val outputDir: File,
    private val port: Int = 9090
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var server: HttpServer? = null

    fun start() {
        try {
            server = HttpServer.create(InetSocketAddress(port), 0)

            // Dashboard HTML 제공
            server?.createContext("/dashboard") { exchange ->
                handleDashboard(exchange)
            }

            // Status JSON 제공 (CORS 허용)
            server?.createContext("/status.json") { exchange ->
                handleStatus(exchange)
            }

            // 루트 경로 리다이렉트
            server?.createContext("/") { exchange ->
                if (exchange.requestURI.path == "/") {
                    exchange.responseHeaders.add("Location", "/dashboard")
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                } else {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                }
            }

            server?.executor = null
            server?.start()

            logger.info("Dashboard server started at http://localhost:$port/dashboard")
        } catch (e: Exception) {
            logger.error("Failed to start dashboard server: ${e.message}")
        }
    }

    fun stop() {
        server?.stop(0)
        logger.info("Dashboard server stopped")
    }

    private fun handleDashboard(exchange: HttpExchange) {
        val dashboardFile = File(outputDir, "dashboard.html")

        if (!dashboardFile.exists()) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }

        val content = dashboardFile.readBytes()
        exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
        exchange.sendResponseHeaders(200, content.size.toLong())
        exchange.responseBody.use { os ->
            os.write(content)
        }
    }

    private fun handleStatus(exchange: HttpExchange) {
        val statusFile = File(outputDir, "status.json")

        if (!statusFile.exists()) {
            // 빈 상태 반환
            val emptyStatus = """{"phase":"Initializing","status":"Starting..."}"""
            val bytes = emptyStatus.toByteArray(Charsets.UTF_8)

            exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { os ->
                os.write(bytes)
            }
            return
        }

        val content = statusFile.readBytes()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.add("Cache-Control", "no-cache, no-store, must-revalidate")
        exchange.sendResponseHeaders(200, content.size.toLong())
        exchange.responseBody.use { os ->
            os.write(content)
        }
    }
}
