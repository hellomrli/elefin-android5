package `is`.xyz.mpv

import android.content.Context
import android.system.Os
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** File work is performed on IO before creating the SurfaceView. */
object MpvEnvironment {
    fun prepare(context: Context): File {
        val fonts = File(context.filesDir, "fonts").apply { mkdirs() }
        // libass builds without an Android font provider can still load this small
        // set of system fonts. Link instead of duplicating large CJK fonts on disk.
        val systemFonts = File("/system/fonts")
        val latin = listOf("Roboto-Regular.ttf", "DroidSans.ttf")
        val cjk = listOf("NotoSansCJK-Regular.ttc", "NotoSansSC-Regular.otf", "DroidSansFallback.ttf", "SourceHanSansCN-Regular.otf")
        var systemCjkName: String? = null
        for (candidates in listOf(latin, cjk)) {
            val source = candidates.map { File(systemFonts, it) }.firstOrNull { it.isFile } ?: continue
            val target = File(fonts, source.name)
            if (!target.exists() || target.length() != source.length()) {
                target.delete()
                try { Os.symlink(source.absolutePath, target.absolutePath) }
                catch (_: Exception) { source.copyTo(target, overwrite = true) }
            }
            if (candidates === cjk) systemCjkName = source.name
        }
        for (name in context.assets.list("fonts").orEmpty()) {
            val target = File(fonts, name)
            // Only load the bundled fallback when the device has no known CJK font.
            if (name == "DroidSansFallback.ttf" && systemCjkName != null) {
                if (systemCjkName != name) target.delete()
                continue
            }
            if (!target.exists() || target.length() == 0L) {
                val temporary = File(context.cacheDir, "$name.tmp")
                context.assets.open("fonts/$name").use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(temporary.renameTo(target)) { "Cannot prepare subtitle fonts" }
            }
        }

        // Native TLS does not use network_security_config (nor does API 21).
        // Export the platform's trusted CAs, including explicitly installed local CAs.
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(null as KeyStore?)
        val certs = trust.trustManagers.filterIsInstance<X509TrustManager>()
            .flatMap { it.acceptedIssuers.toList() }
        check(certs.isNotEmpty()) { "No trusted certificates available" }
        val caFile = File(context.cacheDir, "mpv-trusted-ca.pem")
        val temporary = File(context.cacheDir, "mpv-trusted-ca.tmp")
        temporary.bufferedWriter().use { out ->
            certs.forEach { cert ->
                out.appendLine("-----BEGIN CERTIFICATE-----")
                out.appendLine(Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n"))
                out.appendLine("-----END CERTIFICATE-----")
            }
        }
        check(temporary.renameTo(caFile)) { "Cannot prepare native TLS certificates" }
        return caFile
    }
}
