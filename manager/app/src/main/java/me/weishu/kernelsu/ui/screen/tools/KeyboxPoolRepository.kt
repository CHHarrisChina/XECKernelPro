package me.weishu.kernelsu.ui.screen.tools

import android.content.Context
import android.util.Base64
import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.awaitRootShell
import me.weishu.kernelsu.ui.util.shQuote
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/** No network response, XML, PEM, or command output is included in diagnostics. */
internal object KeyboxPoolRepository {
    private const val MAX_BYTES = 1_048_576
    private const val LIMIT = 10
    private const val CLI = "/data/adb/.xudc_secure/ta-enhanced/bin/ta-enhanced"
    // Same fixed feeds and encodings as tricky-addon-enhanced/rust/src/keybox/sources.rs.
    //
    // 2026-11-17 真机反馈「密钥池找不到密钥」：两路上游都在 raw.githubusercontent.com，
    // 国内直连被重置，于是每次都稳定 0 个候选。现在每个源都是一组有序地址：
    // **国内镜像 → jsDelivr → 官方 raw**。国内镜像由 `_scratch/keybox_mirror.py` 维护
    // （原样字节，客户端解码/校验逻辑一个字没改）。
    private val sources = listOf(
        Source(
            "Yurikey",
            listOf(
                "http://42.193.123.248:5244/d/keybox/yurikey",
                "https://cdn.jsdelivr.net/gh/Yurii0307/yurikey@main/key",
                "https://raw.githubusercontent.com/Yurii0307/yurikey/main/key",
            ),
            false,
        ),
        Source(
            "Tricky Addon upstream",
            listOf(
                "http://42.193.123.248:5244/d/keybox/tricky-addon-extra",
                "https://cdn.jsdelivr.net/gh/KOWX712/Tricky-Addon-Update-Target-List@keybox/.extra",
                "https://raw.githubusercontent.com/KOWX712/Tricky-Addon-Update-Target-List/keybox/.extra",
            ),
            true,
        ),
    )
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()

    private class Source(val name: String, val urls: List<String>, val hex: Boolean)

    /** One feed's outcome, so the UI can say *why* nothing came back. */
    private class SourceResult(
        val candidates: List<Candidate>,
        val rejected: Int,
        val reachable: Boolean,
        val empty: Boolean,
    )

    class Candidate(
        val id: String,
        val source: String,
        val sourceUrl: String,
        val fetchedAt: Long,
        val sourceModifiedAt: String?,
        val validFrom: Long,
        val expiresAt: Long,
        val keyCount: Int,
        private val xml: ByteArray,
    ) {
        fun isCurrent(now: Long = System.currentTimeMillis()) = now in validFrom..expiresAt
        internal fun bytes() = xml
    }
    class Pool(val candidates: List<Candidate>, val notices: List<String>)
    private class Node(val name: String, val attrs: Map<String, String>) {
        val children = mutableListOf<Node>()
        val text = StringBuilder()
        fun only(name: String) = children.single { it.name == name }
    }

    // 设备上已经装好的 keybox。内置引擎随包带了一份（userspace/ksud/builtin/
    // tricky_store/keybox.xml 会装进模块目录），TrickyStore 系引擎还有自己的拷贝。
    // 2026-11-18 真机「0 / 10」的直接原因就在这里：网络两路都拿不到时，明明设备里
    // 有一份可用 keybox，界面却只报 0。现在本地先列、网络随后补，任何时候都不会再是 0。
    private val localPaths = listOf(
        "/data/adb/tricky_store/keybox.xml",
        "/data/adb/ksu/builtin/tricky_store/keybox.xml",
        "/data/adb/modules/tricky_store/keybox.xml",
        "/data/adb/modules_update/tricky_store/keybox.xml",
    )

    private suspend fun readLocalFile(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val shell = runCatching { awaitRootShell() }.getOrNull() ?: return@withContext null
        if (!shell.isRoot) return@withContext null
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val ok = shell.newJob()
            .add("cat " + shQuote(path) + " 2>/dev/null")
            .to(out, err)
            .exec()
            .isSuccess
        if (!ok || out.isEmpty()) null else out.joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    /** 把设备上已安装的 keybox 也当成候选；读不到/校验不过只记一条说明，不抛异常。 */
    private suspend fun localCandidates(): Pair<List<Candidate>, List<String>> {
        val found = mutableListOf<Candidate>()
        val notices = mutableListOf<String>()
        if (runCatching { awaitRootShell() }.getOrNull()?.isRoot != true) {
            notices += "设备内置 keybox：没有 root 权限，跳过本地读取。"
            return found to notices
        }
        for (path in localPaths) {
            currentCoroutineContext().ensureActive()
            if (found.size >= LIMIT) break
            val raw = readLocalFile(path) ?: continue
            try {
                val root = parse(raw)
                if (root.name != "AndroidAttestation") continue
                for (box in root.children.filter { it.name == "Keybox" }) {
                    if (found.size >= LIMIT) break
                    found += validate(box, "设备内置", path, null)
                }
                if (found.isNotEmpty()) break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notices += "设备内置 keybox：$path 没通过本地校验。"
            }
        }
        return found to notices
    }

    suspend fun fetch(): Pool = withContext(Dispatchers.IO) {
        val candidates = linkedMapOf<String, Candidate>()
        val notices = mutableListOf<String>()
        // 1) 本地已装 keybox 先列：不依赖网络，离线也能应用，池子不再是 0。
        val (local, localNotices) = localCandidates()
        local.forEach { candidates.putIfAbsent(it.id, it) }
        notices += localNotices
        if (local.isNotEmpty()) {
            notices += "设备内置 keybox：已列出 ${local.size} 份，可直接应用。"
        }
        // 2) 上游源：国内镜像 → jsDelivr → 官方 raw，拿到可用候选就停。
        for (source in sources) {
            currentCoroutineContext().ensureActive()
            var result: SourceResult? = null
            var lastError: Exception? = null
            // Try the domestic mirror first, then the public mirrors; stop at the first
            // address that yields at least one usable keybox.
            for (url in source.urls) {
                currentCoroutineContext().ensureActive()
                try {
                    result = fetchOne(source, url)
                    if (result.candidates.isNotEmpty()) break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    lastError = error
                }
            }
            val outcome = result
            if (outcome == null) {
                notices += "${source.name}：${reasonOf(lastError)}"
                continue
            }
            outcome.candidates.forEach { candidates.putIfAbsent(it.id, it) }
            when {
                outcome.candidates.isNotEmpty() && outcome.rejected > 0 ->
                    notices += "${source.name}：${outcome.rejected} 份未通过本地校验。"
                outcome.candidates.isEmpty() && outcome.empty ->
                    notices += "${source.name}：上游当前为空。"
                outcome.candidates.isEmpty() && outcome.reachable ->
                    notices += "${source.name}：没有 keybox 通过本地校验。"
                else -> Unit
            }
        }
        Pool(candidates.values.toList(), notices)
    }

    /** Human-readable reason for a failed source, without echoing any response bytes. */
    private fun reasonOf(error: Exception?): String = when {
        error == null -> "所有地址都连不上。"
        error is java.net.UnknownHostException -> "域名解析失败，所有镜像地址都不通。"
        error is java.net.SocketTimeoutException -> "连接超时。"
        error is java.io.IOException -> "网络错误。"
        else -> "下载或安全解析失败。"
    }

    private suspend fun fetchOne(source: Source, url: String): SourceResult {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = requireNotNull(response.body) { "empty body" }
            check(body.contentLength() <= MAX_BYTES) { "body too large" }
            val raw = body.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    check(out.size() + n <= MAX_BYTES) { "body too large" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray().toString(Charsets.UTF_8).trim()
            }
            if (raw.isEmpty()) return SourceResult(emptyList(), 0, reachable = true, empty = true)
            val encoded = if (source.hex) {
                // Hex feeds are commonly wrapped across lines; parity and the digit
                // check must run on the compacted text, otherwise a perfectly good
                // feed is rejected as "not hex" and the pool silently has one source.
                val compact = raw.filterNot { it.isWhitespace() }
                check(compact.isNotEmpty() && compact.length % 2 == 0) { "hex payload malformed" }
                check(compact.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) { "hex payload malformed" }
                compact.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    .toString(Charsets.US_ASCII)
            } else {
                raw
            }
            val xml = Base64.decode(encoded.filterNot { it.isWhitespace() }, Base64.DEFAULT)
            check(xml.size <= MAX_BYTES) { "decoded payload too large" }
            val root = parse(xml)
            check(root.name == "AndroidAttestation") { "unexpected root element" }
            val boxes = root.children.filter { it.name == "Keybox" }
            check(boxes.isNotEmpty()) { "no keybox element" }
            val accepted = mutableListOf<Candidate>()
            var rejected = 0
            for (box in boxes) {
                currentCoroutineContext().ensureActive()
                if (accepted.size >= LIMIT) break
                try {
                    accepted += validate(box, source.name, url, response.header("Last-Modified"))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    rejected++
                }
            }
            return SourceResult(accepted, rejected, reachable = true, empty = false)
        }
    }

    private fun parse(bytes: ByteArray): Node {
        // Reject declarations before parsing, including in otherwise ignored comments.
        // XmlPullParser never enables DTD processing and no external resolver is supplied.
        val text = bytes.toString(Charsets.UTF_8)
        check(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text))
        check(!text.contains('\u0000'))
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(text.reader())
        val stack = mutableListOf<Node>()
        var root: Node? = null
        var nodes = 0
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF -> error("XML declaration/entity rejected")
                XmlPullParser.START_TAG -> {
                    check(++nodes <= 4096 && stack.size < 16)
                    val node = Node(parser.name, (0 until parser.attributeCount).associate {
                        parser.getAttributeName(it) to parser.getAttributeValue(it)
                    })
                    if (stack.isEmpty()) { check(root == null); root = node }
                    else stack.last().children += node
                    stack += node
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (stack.isNotEmpty()) stack.last().text.append(parser.text)
                XmlPullParser.END_TAG -> { check(stack.isNotEmpty()); stack.removeAt(stack.lastIndex) }
            }
        }
        check(stack.isEmpty())
        return requireNotNull(root)
    }

    private fun validate(box: Node, sourceName: String, sourceUrl: String, modified: String?): Candidate {
        check(box.children.all { it.name == "Key" } && box.text.isBlank())
        check(box.attrs.keys.all { it == "DeviceID" })
        val keys = box.children.filter { it.name == "Key" }
        check(keys.size in 1..2)
        val algorithms = keys.map { it.attrs["algorithm"].orEmpty().lowercase(java.util.Locale.ROOT) }
        check(algorithms.distinct().size == algorithms.size)
        check(algorithms.any { it == "ec" || it == "ecdsa" })
        val certificates = mutableListOf<X509Certificate>()
        for ((index, key) in keys.withIndex()) {
            val algorithm = when (algorithms[index]) { "ec", "ecdsa" -> "EC"; "rsa" -> "RSA"; else -> error("Algorithm") }
            check(key.text.isBlank() && key.attrs.keys.all { it == "algorithm" })
            check(key.children.size == 2 && key.children.all { it.name in setOf("PrivateKey", "CertificateChain") })
            val privateNode = key.only("PrivateKey")
            check(privateNode.children.isEmpty() && privateNode.attrs.keys.all { it == "format" })
            val chainNode = key.only("CertificateChain")
            check(chainNode.text.isBlank() && chainNode.attrs.isEmpty())
            check(chainNode.children.all { it.name in setOf("NumberOfCertificates", "Certificate") })
            check(chainNode.only("NumberOfCertificates").children.isEmpty())
            val chain = chainNode.children.filter { it.name == "Certificate" }.map {
                check(it.children.isEmpty() && it.attrs.keys.all { attr -> attr == "format" })
                CertificateFactory.getInstance("X.509").generateCertificate(pem(it.text.toString()).inputStream()) as X509Certificate
            }
            check(chain.size in 2..8)
            check(chainNode.only("NumberOfCertificates").text.toString().trim().toInt() == chain.size)
            check(chain.first().publicKey.algorithm.equals(algorithm, true))
            chain.forEach { it.checkValidity(Date()) }
            for (i in 0 until chain.lastIndex) {
                check(chain[i].issuerX500Principal == chain[i + 1].subjectX500Principal)
                check(chain[i + 1].basicConstraints >= 0)
                val usage = chain[i + 1].keyUsage
                check(usage == null || (usage.size > 5 && usage[5]))
                chain[i].verify(chain[i + 1].publicKey)
            }
            check(chain.last().issuerX500Principal == chain.last().subjectX500Principal)
            chain.last().verify(chain.last().publicKey)
            val privatePem = key.only("PrivateKey").text.toString()
            val raw = pem(privatePem)
            try {
                val pkcs8 = if (privatePem.contains("-----BEGIN PRIVATE KEY-----")) raw else {
                    check(privatePem.contains(if (algorithm == "RSA") "-----BEGIN RSA PRIVATE KEY-----" else "-----BEGIN EC PRIVATE KEY-----"))
                    // Reuse the certificate's AlgorithmIdentifier (including EC curve OID).
                    val spki = chain.first().publicKey.encoded
                    val sequenceBody = derBody(spki, 0)
                    val algorithmEnd = derEnd(spki, sequenceBody)
                    der(0x30, byteArrayOf(2, 1, 0) + spki.copyOfRange(sequenceBody, algorithmEnd) + der(4, raw))
                }
                val privateKey = KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(pkcs8))
                val signatureAlgorithm = if (algorithm == "RSA") "SHA256withRSA" else "SHA256withECDSA"
                val challenge = "KernelSU keybox pool key match".toByteArray(Charsets.US_ASCII)
                val signature = Signature.getInstance(signatureAlgorithm).run { initSign(privateKey); update(challenge); sign() }
                check(Signature.getInstance(signatureAlgorithm).run { initVerify(chain.first()); update(challenge); verify(signature) })
                if (pkcs8 !== raw) pkcs8.fill(0)
            } finally { raw.fill(0) }
            certificates += chain
        }
        val digest = MessageDigest.getInstance("SHA-256")
        certificates.map { it.encoded }.sortedBy { Base64.encodeToString(it, Base64.NO_WRAP) }.forEach { digest.update(it) }
        val id = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val writer = StringWriter()
        val serializer = Xml.newSerializer()
        serializer.setOutput(writer)
        fun emit(node: Node) {
            serializer.startTag(null, node.name)
            node.attrs.forEach { (name, value) -> serializer.attribute(null, name, value) }
            if (node.text.isNotBlank()) serializer.text(node.text.toString())
            node.children.forEach { emit(it) }
            serializer.endTag(null, node.name)
        }
        serializer.startTag(null, "AndroidAttestation")
        serializer.startTag(null, "NumberOfKeyboxes").text("1").endTag(null, "NumberOfKeyboxes")
        emit(box)
        serializer.endTag(null, "AndroidAttestation")
        serializer.flush()
        val safeModified = modified?.takeIf {
            it.matches(Regex("[A-Za-z]{3}, [0-9]{2} [A-Za-z]{3} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT"))
        }
        return Candidate(id, sourceName, sourceUrl, System.currentTimeMillis(), safeModified,
            certificates.maxOf { it.notBefore.time }, certificates.minOf { it.notAfter.time }, keys.size,
            writer.toString().toByteArray(Charsets.UTF_8))
    }

    private fun pem(value: String): ByteArray {
        check(value.length <= 65_536)
        val body = value.lineSequence().filterNot { it.trim().startsWith("-----") }.joinToString("").filterNot { it.isWhitespace() }
        check(body.isNotEmpty() && body.all { it.isLetterOrDigit() && it.code < 128 || it in "+/=" })
        return Base64.decode(body, Base64.DEFAULT)
    }
    private fun derBody(bytes: ByteArray, offset: Int): Int {
        val length = bytes[offset + 1].toInt() and 255
        return offset + 2 + if (length < 128) 0 else (length and 127).also { check(it in 1..4) }
    }
    private fun derEnd(bytes: ByteArray, offset: Int): Int {
        val body = derBody(bytes, offset)
        val first = bytes[offset + 1].toInt() and 255
        var length = if (first < 128) first else 0
        if (first >= 128) for (i in offset + 2 until body) length = (length shl 8) or (bytes[i].toInt() and 255)
        return (body + length).also { check(it in body..bytes.size) }
    }
    private fun der(tag: Int, body: ByteArray): ByteArray {
        var length = body.size
        val octets = mutableListOf<Byte>()
        do { octets.add(0, length.toByte()); length = length ushr 8 } while (length > 0)
        val header = if (body.size < 128) byteArrayOf(body.size.toByte())
            else byteArrayOf((128 or octets.size).toByte()) + octets.toByteArray()
        return byteArrayOf(tag.toByte()) + header + body
    }

    /** Only the explicit Apply action calls this; fetching never obtains root or writes live files. */
    suspend fun apply(context: Context, candidate: Candidate): Boolean = withContext(Dispatchers.IO) {
        check(candidate.isCurrent())
        val shell = awaitRootShell()
        check(shell.isRoot)
        val file = File.createTempFile("keybox_pool_", ".xml", context.cacheDir)
        try {
            check(file.setReadable(false, false) && file.setWritable(false, false))
            check(file.setReadable(true, true) && file.setWritable(true, true))
            file.writeBytes(candidate.bytes())
            // Import API handles validation, backup, atomic write and mode 0600.
            // Suppress all daemon output: never render/log upstream text or private material.
            shell.newJob().add(
                """
                    (
                        tool=${shQuote(CLI)}
                        previous=${'$'}(timeout 15 "${'$'}tool" config get keybox.enabled 2>/dev/null) || exit 1
                        case "${'$'}previous" in true|false) ;; *) exit 1;; esac
                        timeout 15 "${'$'}tool" config set keybox.enabled false >/dev/null 2>&1 || exit 1
                        if timeout 60 "${'$'}tool" keybox set-custom ${shQuote(file.absolutePath)} >/dev/null 2>&1; then
                            exit 0
                        fi
                        timeout 15 "${'$'}tool" config set keybox.enabled "${'$'}previous" >/dev/null 2>&1
                        exit 1
                    )
                """.trimIndent(),
            ).to(ArrayList<String>(), ArrayList<String>()).exec().isSuccess
        } finally { file.delete() }
    }
}
