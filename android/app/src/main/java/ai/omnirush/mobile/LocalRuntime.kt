package ai.omnirush.mobile

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

internal sealed interface LocalRuntimeState {
    data object Empty : LocalRuntimeState
    data class Downloading(val label: String, val progress: Int) : LocalRuntimeState
    data object UbuntuReady : LocalRuntimeState
    data object OmniRushReady : LocalRuntimeState
    data class LoginRequired(val url: String?) : LocalRuntimeState
    data object Ready : LocalRuntimeState
    data class Failed(val message: String) : LocalRuntimeState
}

internal data class LocalCommandResult(
    val exitCode: Int,
    val output: String,
    val loginUrl: String? = null,
)

/**
 * Runs the real OmniRush CLI in a rootless Ubuntu userland on Android.
 *
 * The APK downloads the official PRoot-compatible Ubuntu archive on first use,
 * installs Node.js and the published OmniRush CLI inside it, and then executes
 * real `omnirush` commands. No model or response is implemented in this class.
 */
internal class LocalRuntimeManager(private val context: Context) {
    companion object {
        private const val UBUNTU_AARCH64_URL =
            "https://github.com/termux/proot-distro/releases/download/v4.18.0/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
        private const val UBUNTU_X86_64_URL =
            "https://github.com/termux/proot-distro/releases/download/v4.18.0/ubuntu-noble-x86_64-pd-v4.18.0.tar.xz"
        private const val UBUNTU_AARCH64_SHA256 =
            "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa"
        private const val UBUNTU_X86_64_SHA256 =
            "3a769bce23985effb504140d43b6ddd73dac9e261d1932894afa31de81e45414"
        private const val PROOT_AARCH64_URL =
            "https://skirsten.github.io/proot-portable-android-binaries/aarch64/proot"
        private const val PROOT_X86_64_URL =
            "https://skirsten.github.io/proot-portable-android-binaries/x86_64/proot"
    }

    private val runtimeDir = File(context.filesDir, "linux-runtime")
    private val rootfsDir = File(runtimeDir, "ubuntu-rootfs")
    private val archiveFile = File(runtimeDir, "ubuntu-rootfs.tar.xz")
    private val prootFile = File(runtimeDir, "proot")
    private val tempDir = File(runtimeDir, "tmp")
    private val workspaceDir = File(context.filesDir, "workspace")
    private val installedMarker = File(rootfsDir, "root/.omnirush/android-installed")
    private val browserScript = File(rootfsDir, "usr/local/bin/omnirush-android-browser")
    private val loginUrlFile = File(rootfsDir, "root/.omnirush/android-login-url")

    init {
        runtimeDir.mkdirs()
        tempDir.mkdirs()
        workspaceDir.mkdirs()
    }

    fun hasUbuntu(): Boolean = rootfsDir.resolve("bin/sh").exists() && prootFile.canExecute()

    fun hasOmniRush(): Boolean = installedMarker.exists()

    suspend fun prepare(onProgress: (String, Int) -> Unit) = withContext(Dispatchers.IO) {
        val (prootUrl, rootfsUrl, rootfsHash) = if (isAarch64()) {
            Triple(PROOT_AARCH64_URL, UBUNTU_AARCH64_URL, UBUNTU_AARCH64_SHA256)
        } else {
            Triple(PROOT_X86_64_URL, UBUNTU_X86_64_URL, UBUNTU_X86_64_SHA256)
        }

        if (!prootFile.canExecute()) {
            onProgress("Downloading PRoot", 0)
            download(prootUrl, prootFile) { onProgress("Downloading PRoot", it) }
            prootFile.setExecutable(true, false)
        }

        if (!hasUbuntu()) {
            onProgress("Downloading Ubuntu", 0)
            download(rootfsUrl, archiveFile) { onProgress("Downloading Ubuntu", it) }
            verifySha256(archiveFile, rootfsHash)
            onProgress("Extracting Ubuntu", 0)
            extractRootfs(archiveFile, rootfsDir) { onProgress("Extracting Ubuntu", it) }
            archiveFile.delete()
            configureRootfs()
        }
    }

    suspend fun installOmniRush(onOutput: (String) -> Unit): LocalCommandResult {
        val command = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y ca-certificates curl
            curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
            apt-get install -y nodejs npm
            npm install -g omnirush@latest
            mkdir -p /root/.omnirush
            touch /root/.omnirush/android-installed
            omnirush --version
        """.trimIndent()
        return execute(listOf("/bin/sh", "-lc", command), onOutput)
    }

    suspend fun login(onOutput: (String) -> Unit): LocalCommandResult {
        loginUrlFile.delete()
        browserScript.parentFile?.mkdirs()
        browserScript.writeText(
            "#!/bin/sh\nprintf '%s' \"$1\" > /root/.omnirush/android-login-url\nexit 0\n",
        )
        browserScript.setExecutable(true, false)
        val result = execute(listOf("omnirush", "login"), onOutput)
        return result.copy(loginUrl = loginUrlFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null })
    }

    suspend fun runPrompt(prompt: String, onOutput: (String) -> Unit): LocalCommandResult {
        return execute(listOf("omnirush", "-p", prompt, "--engine", "pi"), onOutput)
    }

    private suspend fun execute(arguments: List<String>, onOutput: (String) -> Unit): LocalCommandResult =
        withContext(Dispatchers.IO) {
            check(hasUbuntu()) { "Ubuntu runtime is not installed yet." }
            check(hasOmniRush() || arguments.firstOrNull() != "omnirush") {
                "OmniRush is not installed yet. Install it first."
            }

            val command = mutableListOf(
                prootFile.absolutePath,
                "--link2symlink",
                "-0",
                "-r",
                rootfsDir.absolutePath,
                "-b",
                "/dev",
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-b",
                "${workspaceDir.absolutePath}:/workspace",
                "-w",
                "/workspace",
                "/usr/bin/env",
                "-i",
                "HOME=/root",
                "USER=root",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "BROWSER=/usr/local/bin/omnirush-android-browser",
                "/bin/sh",
                "-lc",
                "exec \"$@\"",
                "omnirush-android",
            )
            command += arguments

            val process = ProcessBuilder(command)
                .directory(workspaceDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["PROOT_TMP_DIR"] = tempDir.absolutePath
                    environment()["TMPDIR"] = tempDir.absolutePath
                }
                .start()

            val output = StringBuilder()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    output.appendLine(line)
                    onOutput(line)
                }
            }
            val exitCode = process.waitFor()
            LocalCommandResult(exitCode, output.toString())
        }

    private fun configureRootfs() {
        rootfsDir.resolve("etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        rootfsDir.resolve("root/.omnirush").mkdirs()
    }

    private fun isAarch64(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    private fun download(url: String, destination: File, onProgress: (Int) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            requestMethod = "GET"
        }
        try {
            check(connection.responseCode in 200..299) { "Download failed with HTTP ${connection.responseCode}." }
            val total = connection.contentLengthLong
            var received = 0L
            destination.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        if (total > 0) onProgress(((received * 100) / total).toInt())
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun verifySha256(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) { "Ubuntu archive checksum did not match." }
    }

    private fun extractRootfs(archive: File, destination: File, onProgress: (Int) -> Unit) {
        destination.mkdirs()
        TarArchiveInputStream(XZInputStream(BufferedInputStream(FileInputStream(archive)))).use { input ->
            var entry = input.nextTarEntry
            var count = 0L
            while (entry != null) {
                val target = File(destination, entry.name).canonicalFile
                check(target.path == destination.canonicalPath || target.path.startsWith(destination.canonicalPath + File.separator)) {
                    "Unsafe rootfs entry: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else if (entry.isSymbolicLink) {
                    target.parentFile?.mkdirs()
                    runCatching { Files.deleteIfExists(target.toPath()) }
                    Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(entry.linkName))
                } else if (entry.isLink) {
                    target.parentFile?.mkdirs()
                    val linkTarget = File(destination, entry.linkName).canonicalFile
                    if (linkTarget.exists()) {
                        runCatching { Files.deleteIfExists(target.toPath()) }
                        Files.createLink(target.toPath(), linkTarget.toPath())
                    }
                } else if (entry.isFile) {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                    target.setExecutable((entry.mode and 0x40) != 0, false)
                }
                count += 1
                if (count % 500L == 0L) onProgress((count % 10_000L).toInt() / 100)
                entry = input.nextTarEntry
            }
        }
        onProgress(100)
    }
}
