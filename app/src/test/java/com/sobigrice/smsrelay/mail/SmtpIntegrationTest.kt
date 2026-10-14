package com.sobigrice.smsrelay.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Comparator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.*

/** The only network destination in these tests is this process's loopback TLS server. */
class SmtpIntegrationTest {
    companion object {
        private lateinit var directory: Path
        private lateinit var serverTls: SSLContext
        private lateinit var trustedClientTls: SSLContext
        @JvmStatic @BeforeClass fun certificates() {
            directory = Files.createTempDirectory("smsrelay-smtp-test-")
            val file = directory.resolve("server.p12")
            val executable = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
            val process = ProcessBuilder(executable, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
                "-keystore", file.toString(), "-storepass", "local-test-only", "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); error("test certificate generation timed out") }
            check(process.exitValue() == 0)
            val store = KeyStore.getInstance("PKCS12").apply {
                Files.newInputStream(file).use { load(it, "local-test-only".toCharArray()) }
            }
            val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "local-test-only".toCharArray()) }
            val trust = KeyStore.getInstance("PKCS12").apply { load(null); setCertificateEntry("test", store.getCertificate("test")) }
            val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            serverTls = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
            trustedClientTls = SSLContext.getInstance("TLS").apply { init(null, tm.trustManagers, null) }
        }
        @JvmStatic @AfterClass fun removeCertificates() {
            if (::directory.isInitialized) Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
    private val account = MailAccount.legacyQq("10001@qq.com", "abcdefghijklmnop", "10002@qq.com")
    private val job = MailJob(sourceSender = "106123", body = "本地 TLS 测试：中文🍚\n.\n完整正文", receivedAt = 123,
        accountAddress = account.sender, recipientAddress = account.recipient)
    private enum class Mode { ACCEPT, AUTH_FAIL, DROP_AFTER_DATA, NO_STARTTLS }
    private class LocalSmtp(mode: Mode, private val startTls: Boolean = false) : AutoCloseable {
        private val server: ServerSocket = if (startTls) ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            else serverTls.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        @Volatile var data = ""
        @Volatile var sawAuth = false
        @Volatile var authUsedTls = false
        @Volatile var username = ""
        private val connection = AtomicReference<Socket?>()
        private val executor = Executors.newSingleThreadExecutor()
        init {
            executor.submit {
                try {
                    server.accept().use { raw ->
                        var socket: Socket = raw
                        connection.set(socket); socket.soTimeout = 3000
                        var encrypted = !startTls
                        var input = socket.inputStream.bufferedReader(Charsets.UTF_8)
                        var output = socket.outputStream.bufferedWriter(Charsets.UTF_8)
                        fun reply(text: String) { output.write(text + "\r\n"); output.flush() }
                        reply("220 localhost TLS SMTP test")
                        while (true) {
                            val line = input.readLine() ?: break
                            when {
                                line.startsWith("EHLO") -> {
                                    reply("250-localhost")
                                    if (startTls && !encrypted && mode != Mode.NO_STARTTLS) reply("250-STARTTLS")
                                    reply("250 AUTH LOGIN")
                                }
                                line == "STARTTLS" -> {
                                    reply("220 Ready to start TLS")
                                    socket = (serverTls.socketFactory.createSocket(raw, "localhost", port, true) as SSLSocket).apply {
                                        useClientMode = false; soTimeout = 3000; startHandshake()
                                    }
                                    connection.set(socket); encrypted = true
                                    input = socket.inputStream.bufferedReader(Charsets.UTF_8)
                                    output = socket.outputStream.bufferedWriter(Charsets.UTF_8)
                                }
                                line.startsWith("AUTH LOGIN") -> {
                                    sawAuth = true; authUsedTls = encrypted
                                    if (mode == Mode.AUTH_FAIL) reply("535 5.7.8 test authentication rejected")
                                    else { reply("334 VXNlcm5hbWU6"); username = String(Base64.getDecoder().decode(input.readLine()), Charsets.UTF_8); reply("334 UGFzc3dvcmQ6"); input.readLine(); reply("235 2.7.0 OK") }
                                }
                                line.startsWith("MAIL FROM") || line.startsWith("RCPT TO") -> reply("250 2.1.0 OK")
                                line == "DATA" -> {
                                    reply("354 End data with dot")
                                    val lines = mutableListOf<String>()
                                    while (true) {
                                        val text = input.readLine() ?: break
                                        if (text == ".") break
                                        lines += if (text.startsWith("..")) text.drop(1) else text
                                    }
                                    data = lines.joinToString("\r\n") + "\r\n"
                                    if (mode == Mode.DROP_AFTER_DATA) break
                                    reply("250 2.0.0 Accepted")
                                }
                                line == "QUIT" -> { reply("221 Bye"); break }
                                else -> reply("500 unsupported test command")
                            }
                        }
                    }
                } catch (_: java.io.IOException) { /* Expected when a client rejects the local test certificate. */ }
            }
        }
        override fun close() {
            server.close(); connection.get()?.close(); executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
    private fun sender(server: LocalSmtp, host: String = "localhost", trusted: Boolean = true) = SmtpMailSender(MailEndpoint(host, server.port)) {
        if (trusted) it["mail.smtp.ssl.socketFactory"] = trustedClientTls.socketFactory
        // A custom test factory otherwise falls back to the system factory after TLS failure.
        // This single-connection fixture must report the original hostname failure, not time out on a second socket.
        it.setProperty("mail.smtp.socketFactory.fallback", "false")
    }
    @Test fun acceptedTlsDeliveryContainsFullUnicodeBodyAndOriginalRecipient() {
        LocalSmtp(Mode.ACCEPT).use { server ->
            assertEquals(SendResult.Accepted, sender(server).send(job, account))
            val parsed = MimeMessage(Session.getInstance(java.util.Properties()), ByteArrayInputStream(server.data.toByteArray(Charsets.UTF_8)))
            assertTrue((parsed.content as String).replace("\r\n", "\n").contains(job.body))
            assertEquals(account.recipient, parsed.allRecipients.single().toString())
            assertTrue(server.sawAuth)
        }
    }
    @Test fun refusedAuthenticationIsBlockedBeforeData() {
        LocalSmtp(Mode.AUTH_FAIL).use { server ->
            assertEquals(SendResult.Rejected(MailFailure.AUTH), sender(server).send(job, account))
            assertEquals("", server.data)
        }
    }
    @Test fun disconnectAfterDataLeavesDeliveryUncertain() {
        LocalSmtp(Mode.DROP_AFTER_DATA).use { server ->
            assertTrue(sender(server).send(job, account) is SendResult.Uncertain)
            assertTrue(server.data.isNotEmpty())
        }
    }
    @Test fun untrustedCertificateCannotReceiveCredentials() {
        LocalSmtp(Mode.ACCEPT).use { server ->
            assertEquals(SendResult.Rejected(MailFailure.TLS), sender(server, trusted = false).send(job, account))
            assertFalse(server.sawAuth)
        }
    }
    @Test fun trustedCertificateForWrongHostnameCannotReceiveCredentials() {
        LocalSmtp(Mode.ACCEPT).use { server ->
            assertEquals(SendResult.Rejected(MailFailure.TLS), sender(server, host = "127.0.0.1").send(job, account))
            assertFalse(server.sawAuth)
        }
    }
    @Test fun customEndpointAndUsernameUseTheSavedConfiguration() {
        LocalSmtp(Mode.ACCEPT).use { server ->
            val custom = MailAccount.create("alice@example.org", "custom password!", "reader@example.net",
                "localhost", server.port, MailSecurity.TLS, "smtp-user")
            val client = SmtpMailSender(sessionSetup = { it["mail.smtp.ssl.socketFactory"] = trustedClientTls.socketFactory })
            assertEquals(SendResult.Accepted, client.send(job, custom))
            assertEquals("smtp-user", server.username)
            assertTrue(server.authUsedTls)
        }
    }
    @Test fun requiredStartTlsUpgradesBeforeSendingCredentials() {
        LocalSmtp(Mode.ACCEPT, startTls = true).use { server ->
            assertEquals(SendResult.Accepted, sender(server).send(job, account.copy(security = MailSecurity.STARTTLS)))
            assertTrue(server.sawAuth)
            assertTrue(server.authUsedTls)
        }
    }
    @Test fun missingStartTlsCannotReceiveCredentials() {
        LocalSmtp(Mode.NO_STARTTLS, startTls = true).use { server ->
            assertEquals(SendResult.Rejected(MailFailure.TLS), sender(server).send(job, account.copy(security = MailSecurity.STARTTLS)))
            assertFalse(server.sawAuth)
        }
    }

}
