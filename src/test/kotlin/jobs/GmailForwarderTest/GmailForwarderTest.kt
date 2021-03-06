package jobs.GmailForwarderTest

import com.google.api.services.gmail.model.Message
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import config.Configuration
import config.RequiredConfigItem
import config.removeAndSet
import datastore.DropboxWriteFailure
import datastore.ErrorDownloadingFileFromDropbox
import datastore.SimpleDropboxClient
import gmail.SimpleGmailClient
import jobs.GmailForwarderJob.GmailForwarder
import jobs.GmailForwarderJob.GmailForwarder.Companion.encodeBase64
import jobs.GmailForwarderJob.GmailForwarderConfig
import jobs.GmailForwarderJob.GmailForwarderConfigItem
import jobs.GmailForwarderJob.GmailForwarderConfigItem.BCC_ADDRESS
import jobs.GmailForwarderJob.GmailForwarderConfigItem.FROM_ADDRESS
import jobs.GmailForwarderJob.GmailForwarderConfigItem.GMAIL_QUERY
import jobs.GmailForwarderJob.GmailForwarderConfigItem.RUN_ON_DAYS
import jobs.GmailForwarderJob.GmailForwarderConfigItem.TO_ADDRESS
import jobs.GmailForwarderJob.GmailForwarderConfigItem.TO_FULLNAME
import org.junit.Test
import result.CouldNotSendEmail
import result.Result
import result.Result.Failure
import result.Result.Success
import java.time.ZoneOffset.UTC
import java.time.ZonedDateTime
import javax.mail.internet.InternetAddress

class GmailForwarderTest {
    private val time = ZonedDateTime.of(2018, 6, 1, 0, 0, 0, 0, UTC)
    private val requiredConfig = GmailForwarderConfig("TEST_JOB")
    private val jobName = requiredConfig.formattedJobName
    private val baseConfigValues = requiredConfig.values().associate { it to "unused" }.toMutableMap()
    private val configValues: Map<GmailForwarderConfigItem, String> = baseConfigValues.apply {
        removeAndSet(RUN_ON_DAYS(jobName), "1")
        removeAndSet(TO_FULLNAME(jobName), "Jim")
        removeAndSet(TO_ADDRESS(jobName), "jim@example.com")
        removeAndSet(FROM_ADDRESS(jobName), "bob@example.com")
        removeAndSet(BCC_ADDRESS(jobName), "fred@example.com")
    }.toMap()
    @Suppress("UNCHECKED_CAST")
    private val config = Configuration(configValues as Map<RequiredConfigItem, String>, requiredConfig, null)
    private val stateFilename = "/gmailer_state.json"

    @Test
    fun `Happy path`() {
        val state =
        """
          |{
          |  "lastEmailSent": "${time.minusMonths(1)}",
          |  "emailContents": "${encodeBase64("Last month's email data")}"
          |}
          |""".trimMargin()
        val stateFile = FileLike(stateFilename, state)

        val dropboxClient = StubDropboxClient(mapOf(stateFilename to stateFile))
        val emails = listOf(Message().setRaw("Content-Type: multipart/alternative; boundary=\"---\"\r\nNew email data"))
        val jobResult = GmailForwarder(StubGmailClient(emails), dropboxClient, config).run(time)
        assertThat(jobResult, equalTo(
                "New email has been sent\n" +
                "Current state has been stored in Dropbox")
        )
    }

    @Test
    fun `Email isn't sent if one has already been sent this month`() {
        val state =
                """
          |{
          |  "lastEmailSent": "${time.withDayOfMonth(1)}",
          |  "emailContents": "${encodeBase64("Fairly new email data")}"
          |}
          |""".trimMargin()
        val stateFile = FileLike(stateFilename, state)

        val dropboxClient = StubDropboxClient(mapOf(stateFilename to stateFile))
        val emails = listOf(Message().setRaw("Content-Type: multipart/alternative; boundary=\"---\"\nNew email data"))
        val jobResult = GmailForwarder(StubGmailClient(emails), dropboxClient, config).run(time)
        assertThat(jobResult, equalTo("Exiting, email has already been sent for June 2018"))
    }

    @Test
    fun `Emails cannot have been sent in the future`() {
        val state =
                """
          |{
          |  "lastEmailSent": "${time.plusSeconds(1)}",
          |  "emailContents": "${encodeBase64("Next month's email data")}"
          |}
          |""".trimMargin()
        val stateFile = FileLike("/gmailer_state.json", state)

        val dropboxClient = StubDropboxClient(mapOf(stateFilename to stateFile))
        val emails = listOf(Message().setRaw("Content-Type: multipart/alternative; boundary=\"---\"\nLast month's email data"))
        val jobResult = GmailForwarder(StubGmailClient(emails), dropboxClient, config).run(time)
        assertThat(jobResult, equalTo("Exiting due to invalid state, previous email appears to have been sent in the future"))
    }

    @Test
    fun `Email isn't sent if the exact same email contents have already been sent`() {
        val realisticBoundary = "_000_a08a0c7f4bf84c46bf8b5e5392ea7fa1________________________"

        val realisticBody =
            """
        |Content-Type: text/plain; charset="iso-8859-1"
        |MIME-Version: 1.0
        |Content-Transfer-Encoding: quoted-printable
        |
        |Some plain text message
        |over multiple
        |lines.
        |
        |--$realisticBoundary
        |Content-Type: text/html; charset="iso-8859-1"
        |Content-ID: <75FAD0DAF66DEF4BB802FB3442872FDC@______________.com>
        |MIME-Version: 1.0
        |Content-Transfer-Encoding: quoted-printable
        |
        |<html>
        |<head>
        |<meta http-equiv=3D"Content-Type" content=3D"text/html; charset=3Diso-8859-=
        |1">
        |<title></title>
        |</head>
        |<body>
        |<p style=3D"text-align: left; color: rgb(0, 0, 0); font-family: tahoma; fon=
        |t-size: 9pt;">
        |Some html message</p>
        |<span style=3D"text-align: left; color: rgb(0, 0, 0); font-family: &quot;ta=
        |homa&quot;,&quot;sans-serif&quot;; font-size: 8.5pt;">over mulitple</span>
        |<div>lines.
        |<br>
        |<br>
        |</div>
        |</body>
        |</html>
        |
        |--$realisticBoundary
        |""".trimMargin()

        val realisticEmail =
            """
        |From: Jim <jim@example.com>
        |To: Bob <bob@example.com>
        |Subject: An exciting email
        |Date: Fri, 1 Feb 2019 08:47:55 +0000
        |Message-ID: <a08a0c7f4bf84c46bf8b5e5392ea7fa1@some-server_.example.com>
        |Reply-To: "DO-NOT-REPLY@example.com"
        |	<DO-NOT-REPLY@example.com>
        |Content-Type: multipart/alternative;
        |	boundary="$realisticBoundary"
        |MIME-Version: 1.0
        |Bcc: bcc@example.com
        |
        |--$realisticBoundary
        |$realisticBody
        |""".trimMargin()

        val state =
            """
          |{
          |  "lastEmailSent": "${time.minusMonths(1)}",
          |  "emailContents": "${encodeBase64(realisticBody
                .replace(realisticBoundary, "_____________________________________________________________"))}"
          |}
          |""".trimMargin()
        val stateFile = FileLike(stateFilename, state)

        val dropboxClient = StubDropboxClient(mapOf(stateFilename to stateFile))
        val emails = listOf(Message().setRaw(realisticEmail))
        val jobResult = GmailForwarder(StubGmailClient(emails), dropboxClient, config).run(time)
        assertThat(jobResult, equalTo("Exiting as this exact email has already been sent"))
    }

    @Test
    fun `Email is only sent on a particular day of the month`() {
        val state =
                """
          |{
          |  "lastEmailSent": "${time.minusMonths(1)}",
          |  "emailContents": "${encodeBase64("Last month's email data")}"
          |}
          |""".trimMargin()
        val stateFile = FileLike("/gmailer_state.json", state)

        val dropboxClient = StubDropboxClient(mapOf(stateFilename to stateFile))
        val emails = listOf(Message().setRaw("New email data"))
        val firstOfJune = ZonedDateTime.of(2018, 6, 1, 0, 0, 0, 0, UTC)
        val localConfig = config.copy(
                config = configValues.toMutableMap()
                                     .apply { removeAndSet(RUN_ON_DAYS(jobName), "2, 11,12, 31 ") }
                                     .toMap()
        )
        val jobResult = GmailForwarder(StubGmailClient(emails), dropboxClient, localConfig).run(firstOfJune)
        assertThat(jobResult, equalTo("No need to run - day of month is 1, only running on day 2, 11, 12, 31 of each month"))
    }

    @Test
    fun `Error message is provided when emails fail to be sent`() {
        val state =
                """
          |{
          |  "lastEmailSent": "${time.minusMonths(1)}",
          |  "emailContents": "${encodeBase64("Last month's email data")}"
          |}
          |""".trimMargin()
        val stateFile = FileLike("/gmailer_state.json", state)

        val dropboxClient = StubDropboxClient(mapOf(stateFilename to stateFile))
        val emails = listOf(Message().setRaw(
            """
            |Content-Type: multipart/alternative; boundary="---"
            |Subject: New email subject
            |---
            |New email data
            |""".trimMargin()
        ))
        val jobResult = GmailForwarder(StubGmailClientThatCannotSend(emails), dropboxClient, config).run(time)
        assertThat(jobResult, equalTo("Error sending email with subject 'New email subject' to Jim <jim@example.com>"))
    }

    @Test
    fun `Error message is provided when state cannot be stored in Dropbox`() {
        val state =
                """
          |{
          |  "lastEmailSent": "${time.minusMonths(1)}",
          |  "emailContents": "${encodeBase64("Last month's email data")}"
          |}
          |""".trimMargin()
        val stateFile = FileLike("/gmailer_state.json", state)

        val dropboxClient = StubDropboxClientThatCannotStore(mapOf(stateFilename to stateFile))
        val emails = listOf(Message().setRaw("Content-Type: multipart/alternative; boundary=\"---\"\nNew email data"))
        val jobResult = GmailForwarder(StubGmailClient(emails), dropboxClient, config).run(time)
        assertThat(jobResult, equalTo("New email has been sent\nError - could not store state in Dropbox"))
    }

    @Test
    fun `Error message is provided when email raw content cannot be retrieved from Gmail`() {
        val state =
                """
          |{
          |  "lastEmailSent": "${time.minusMonths(1)}",
          |  "emailContents": "${encodeBase64("Last month's email data")}"
          |}
          |""".trimMargin()
        val stateFile = FileLike("/gmailer_state.json", state)

        val dropboxClient = StubDropboxClient(mapOf(stateFilename to stateFile))
        val emails = listOf(Message().setRaw("Content-Type: multipart/alternative; boundary=\"---\"\nNew email data"))
        val jobResult = GmailForwarder(StubGmailClientThatCannotRetrieveRawContent(emails), dropboxClient, config).run(time)
        assertThat(jobResult, equalTo("Error - could not get raw message content for email"))
    }

    @Test
    fun `Error message is provided when there are no matches for search query`() {
        val state =
          """
          |{
          |  "lastEmailSent": "${time.minusMonths(1)}",
          |  "emailContents": "${encodeBase64("Last month's email data")}"
          |}
          |""".trimMargin()
        val stateFile = FileLike("/gmailer_state.json", state)

        val dropboxClient = StubDropboxClient(mapOf(stateFilename to stateFile))
        val localConfig = config.copy(
                config = configValues.toMutableMap()
                                     .apply { removeAndSet(GMAIL_QUERY(jobName), "some search query") }
                                     .toMap()
        )
        val jobResult = GmailForwarder(StubGmailClientThatReturnsNoMatches(emptyList()), dropboxClient, localConfig).run(time)
        assertThat(jobResult, equalTo("No matching results for query: 'some search query'"))
    }

    @Test
    fun `Error message is provided when state file does not exist in Dropbox`() {
        val dropboxClient = StubDropboxClient(mapOf())
        val emails = listOf(Message().setRaw("Content-Type: multipart/alternative; boundary=\"---\"\nNew email data"))
        val jobResult = GmailForwarder(StubGmailClient(emails), dropboxClient, config).run(time)
        assertThat(jobResult, equalTo("Error downloading file /gmailer_state.json from Dropbox"))
    }

    @Test
    fun `Non multipart emails are not forwarded`() {
        val state =
            """
          |{
          |  "lastEmailSent": "${time.minusMonths(1)}",
          |  "emailContents": "${encodeBase64("Last month's email data")}"
          |}
          |""".trimMargin()
        val stateFile = FileLike(stateFilename, state)

        val dropboxClient = StubDropboxClient(mapOf(stateFilename to stateFile))
        val emails = listOf(Message().setRaw("Non-multipart email data"))
        val jobResult = GmailForwarder(StubGmailClient(emails), dropboxClient, config).run(time)
        assertThat(jobResult, equalTo("Error - forwarding non-multipart emails is not supported"))
    }
}

open class StubGmailClient(private val emails: List<Message>) : SimpleGmailClient {
    val sentMail = mutableListOf<Message>()

    override fun lastEmailForQuery(queryString: String): Message? {
        return emails.last()
    }

    override fun rawContentOf(cookedMessage: Message): ByteArray? =
            cookedMessage.raw.toByteArray()

    override fun send(message: Message, subject: String, recipients: List<InternetAddress>): Result<CouldNotSendEmail, Message> {
        sentMail.add(message)
        return Success(message)
    }
}

class StubGmailClientThatCannotSend(emails: List<Message>) : StubGmailClient(emails) {
    override fun send(message: Message, subject: String, recipients: List<InternetAddress>): Result<CouldNotSendEmail, Message> = Failure(CouldNotSendEmail(subject, recipients))
}

class StubGmailClientThatCannotRetrieveRawContent(emails: List<Message>) : StubGmailClient(emails) {
    override fun rawContentOf(cookedMessage: Message): ByteArray? = null
}

class StubGmailClientThatReturnsNoMatches(emails: List<Message>) : StubGmailClient(emails) {
    override fun lastEmailForQuery(queryString: String): Message? = null
}


open class StubDropboxClient(initialFiles: Map<String, FileLike>) : SimpleDropboxClient {
    private var files: MutableMap<String, FileLike> = initialFiles.toMutableMap()

    override fun readFile(filename: String): Result<ErrorDownloadingFileFromDropbox, String> {
        val fileMaybe = files[filename]
        return fileMaybe?.let { fileLike ->
            Success(fileLike.contents)
        } ?: Failure(ErrorDownloadingFileFromDropbox(filename))
    }

    override fun writeFile(fileContents: String, filename: String, fileDescription: String): Result<DropboxWriteFailure, String> {
        files[filename] = FileLike(filename, fileContents)
        return Success("$fileDescription\nCurrent state has been stored in Dropbox")
    }
}

class StubDropboxClientThatCannotStore(initialFiles: Map<String, FileLike>) : StubDropboxClient(initialFiles) {
    override fun writeFile(fileContents: String, filename: String, fileDescription: String): Result<DropboxWriteFailure, String> =
            Failure(DropboxWriteFailure(fileDescription))
}

data class FileLike(val name: String, val contents: String)
