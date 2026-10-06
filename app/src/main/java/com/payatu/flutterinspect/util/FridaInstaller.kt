package com.payatu.flutterinspect.util

import android.os.Build
import com.payatu.flutterinspect.data.shell.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.tukaani.xz.XZInputStream

object FridaInstaller {

    private const val GITHUB_LATEST_RELEASE_API = "https://api.github.com/repos/frida/frida/releases/latest"
    const val TARGET_FRIDA_INJECT_PATH = "/data/local/tmp/frida-inject"

    data class FridaStatus(
        val isInstalled: Boolean,
        val version: String? = null,
        val path: String = TARGET_FRIDA_INJECT_PATH
    )

    suspend fun checkStatus(): FridaStatus = withContext(Dispatchers.IO) {
        val checkRes = RootShell.execSu("test -x $TARGET_FRIDA_INJECT_PATH && echo 'EXISTS'")
        if (checkRes.exitCode != 0 || !checkRes.stdout.contains("EXISTS")) {
            return@withContext FridaStatus(isInstalled = false)
        }

        val verRes = RootShell.execSu("$TARGET_FRIDA_INJECT_PATH --version")
        val lines = verRes.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
        // Filter out any SELinux warning lines
        val version = lines.lastOrNull { line ->
            !line.contains("SELinux", ignoreCase = true) && !line.contains("denied", ignoreCase = true)
        } ?: "Unknown"

        FridaStatus(isInstalled = true, version = version)
    }

    suspend fun downloadAndInstall(
        cacheDir: File,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            onProgress("Determining device architecture...")
            val abi = detectDeviceAbi()
            onProgress("Target ABI: $abi. Fetching latest Frida release...")

            val releaseUrl = URL(GITHUB_LATEST_RELEASE_API)
            val conn = (releaseUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Deflutter-Android")
                connectTimeout = 15000
                readTimeout = 15000
            }

            if (conn.responseCode != 200) {
                return@withContext Result.failure(Exception("GitHub API error: HTTP ${conn.responseCode}"))
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val releaseObj = JSONObject(body)
            val tagName = releaseObj.optString("tag_name", "latest")
            val assetsArr = releaseObj.optJSONArray("assets")
                ?: return@withContext Result.failure(Exception("No assets found in latest release"))

            val assetTargetName = "frida-inject-$tagName-android-$abi.xz"
            var downloadUrl: String? = null

            for (i in 0 until assetsArr.length()) {
                val asset = assetsArr.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name == assetTargetName) {
                    downloadUrl = asset.optString("browser_download_url", null)
                    break
                }
            }

            if (downloadUrl == null) {
                return@withContext Result.failure(Exception("Could not find matching asset '$assetTargetName' in release $tagName"))
            }

            onProgress("Downloading $assetTargetName...")
            val localXzFile = File(cacheDir, assetTargetName)
            val localBinFile = File(cacheDir, "frida-inject")

            val dlConn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Deflutter-Android")
                connectTimeout = 20000
                readTimeout = 60000
            }

            dlConn.inputStream.use { input ->
                FileOutputStream(localXzFile).use { output ->
                    input.copyTo(output)
                }
            }

            onProgress("Decompressing XZ payload...")
            localXzFile.inputStream().use { fileIn ->
                XZInputStream(fileIn).use { xzIn ->
                    FileOutputStream(localBinFile).use { binOut ->
                        xzIn.copyTo(binOut)
                    }
                }
            }

            onProgress("Installing frida-inject to $TARGET_FRIDA_INJECT_PATH via root...")
            val pushRes = RootShell.execSu("cp -f '${localBinFile.absolutePath}' $TARGET_FRIDA_INJECT_PATH && chmod 755 $TARGET_FRIDA_INJECT_PATH && chcon u:object_r:system_file:s0 $TARGET_FRIDA_INJECT_PATH 2>/dev/null || true")
            
            // Clean temp files
            localXzFile.delete()
            localBinFile.delete()

            if (pushRes.exitCode != 0) {
                return@withContext Result.failure(Exception("Root installation failed: ${pushRes.stderr}"))
            }

            val verifyStatus = checkStatus()
            if (verifyStatus.isInstalled) {
                onProgress("Successfully installed Frida ${verifyStatus.version}!")
                Result.success("Installed Frida ${verifyStatus.version}")
            } else {
                Result.failure(Exception("Installation completed but binary verification failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun detectDeviceAbi(): String {
        for (abi in Build.SUPPORTED_ABIS) {
            if (abi.startsWith("arm64")) return "arm64"
            if (abi.startsWith("x86_64")) return "x86_64"
            if (abi.startsWith("armeabi-v7a") || abi.startsWith("arm")) return "arm"
            if (abi.startsWith("x86")) return "x86"
        }
        return "arm64"
    }
}
