package code.name.monkey.retromusic.cast

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.*

object CastServerUtils {
    private const val TAG = "CastServer"
    private const val LOG_FILE_NAME = "cast_server.log"
    
    fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            logError("Port $port is not available: ${e.message}")
            false
        }
    }
    
    fun findAvailablePort(startPort: Int, endPort: Int = startPort + 10): Int {
        for (port in startPort..endPort) {
            if (isPortAvailable(port)) {
                logInfo("Found available port: $port")
                return port
            }
        }
        logError("No available ports found in range $startPort-$endPort")
        return -1
    }
    
    fun logInfo(message: String) {
        Log.i(TAG, message)
        writeToLogFile("INFO", message)
    }
    
    fun logError(message: String) {
        Log.e(TAG, message)
        writeToLogFile("ERROR", message)
    }
    
    private fun writeToLogFile(level: String, message: String) {
        try {
            val logDir = File(context.getExternalFilesDir(null), "logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            
            val logFile = File(logDir, LOG_FILE_NAME)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            
            FileWriter(logFile, true).use { writer ->
                writer.append("$timestamp [$level] $message\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file: ${e.message}")
        }
    }
    
    private lateinit var context: Context
    
    fun init(appContext: Context) {
        context = appContext.applicationContext
    }
} 