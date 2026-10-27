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
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

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
 * The APK carries PRoot, Ubuntu, Node.js, and the published OmniRush packages
 * as build-time assets. On first use it only unpacks those assets into the app
 * sandbox, then executes real `omnirush` commands. No model or response is
 * implemented in this class.
 */
internal class LocalRuntimeManager(private val context: Context) {
    companion object {
        private const val UBUNTU_AARCH64_SHA256 =
            "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa"
        private const val UBUNTU_X86_64_SHA256 =
            "3a769bce23985effb504140d43b6ddd73dac9e261d1932894afa31de81e45414"
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
        val abi = runtimeAbi()
        val rootfsHash = if (abi == "arm64") UBUNTU_AARCH64_SHA256 else UBUNTU_X86_64_SHA256

        if (!prootFile.canExecute()) {
            onProgress("Unpacking bundled PRoot", 0)
            copyAsset("runtime/proot-$abi", prootFile)
            prootFile.setExecutable(true, false)
            onProgress("Unpacking bundled PRoot", 100)
        }

        if (!hasUbuntu()) {
            onProgress("Unpacking bundled Ubuntu", 0)
            copyAsset("runtime/ubuntu-rootfs-$abi.tar.xz", archiveFile)
            verifySha256(archiveFile, rootfsHash)
            onProgress("Extracting Ubuntu", 0)
            extractRootfs(archiveFile, rootfsDir) { onProgress("Extracting Ubuntu", it) }
            archiveFile.delete()
            configureRootfs()
        }
    }

    suspend fun installOmniRush(onOutput: (String) -> Unit): LocalCommandResult {
        return withContext(Dispatchers.IO) {
            check(hasUbuntu()) { "Ubuntu runtime is not prepared yet." }
            val abi = runtimeAbi()
            onOutput("Unpacking bundled Node.js…")
            val nodeDir = rootfsDir.resolve("opt/node")
            nodeDir.deleteRecursively()
            extractAssetTar(
                assetName = "runtime/node-$abi.tar.xz",
                destination = nodeDir,
                stripSegments = 1,
                xz = true,
            )

            onOutput("Unpacking bundled OmniRush CLI…")
            val omniDir = rootfsDir.resolve("opt/omnirush")
            omniDir.deleteRecursively()
            extractAssetTar(
                assetName = "runtime/omnirush-$abi.tgz",
                destination = omniDir.resolve("app"),
                stripSegments = 1,
                xz = false,
            )
            extractAssetTar(
                assetName = "runtime/omnirush-platform-$abi.tgz",
                destination = omniDir.resolve("node_modules/@omnirush-ai/cli-linux-$abi"),
                stripSegments = 1,
                xz = false,
            )

            val launcher = rootfsDir.resolve("usr/local/bin/omnirush")
            launcher.parentFile?.mkdirs()
            launcher.writeText(
                "#!/bin/sh\nexec /opt/node/bin/node /opt/omnirush/app/src/bin.js \"\$@\"\n",
            )
            launcher.setExecutable(true, false)
            rootfsDir.resolve("root/.omnirush").mkdirs()
            installedMarker.writeText("2.2.1\n")
            val result = execute(listOf("omnirush", "--version"), onOutput)
            result
        }
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

    private fun runtimeAbi(): String = when {
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "arm64"
        Build.SUPPORTED_ABIS.any { it == "x86_64" } -> "x64"
        else -> error("This APK currently supports arm64-v8a and x86_64 devices.")
    }

    private fun copyAsset(assetName: String, destination: File) {
        destination.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
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
        TarArchiveInputStream(XZInputStream(BufferedInputStream(FileInputStream(archive)))).use {
            extractTar(it, destination, stripSegments = 0) { count ->
                if (count % 500L == 0L) onProgress((count % 10_000L).toInt() / 100)
            }
        }
        onProgress(100)
    }

    private fun extractAssetTar(
        assetName: String,
        destination: File,
        stripSegments: Int,
        xz: Boolean,
    ) {
        destination.mkdirs()
        context.assets.open(assetName).use { raw ->
            val decoded: InputStream = if (xz) XZInputStream(BufferedInputStream(raw)) else GZIPInputStream(BufferedInputStream(raw))
            decoded.use { input ->
                TarArchiveInputStream(input).use { archive ->
                    extractTar(archive, destination, stripSegments)
                }
            }
        }
    }

    private fun extractTar(
        input: TarArchiveInputStream,
        destination: File,
        stripSegments: Int,
        onEntry: (Long) -> Unit = {},
    ) {
        var entry = input.nextTarEntry
        var count = 0L
        while (entry != null) {
            val parts = entry.name.split('/').filter { it.isNotEmpty() }
            val relativeName = parts.drop(stripSegments).joinToString(File.separator)
            if (relativeName.isNotEmpty()) {
                val target = File(destination, relativeName).canonicalFile
                check(target.path == destination.canonicalPath || target.path.startsWith(destination.canonicalPath + File.separator)) {
                    "Unsafe runtime entry: ${entry.name}"
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
            }
            count += 1
            onEntry(count)
            entry = input.nextTarEntry
        }
    }
}
