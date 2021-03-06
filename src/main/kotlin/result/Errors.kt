package result

import com.github.jknack.handlebars.Handlebars
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import javax.mail.internet.InternetAddress

interface Err { val message: String }
interface NoNeedToRun : Err

class NoNeedToRunOnThisDayOfMonth(dayOfMonth: Int, daysOfMonthToRun: List<Int>) : Err, NoNeedToRun  {
    override val message = "No need to run - day of month is $dayOfMonth, only running on day ${daysOfMonthToRun.joinToString(", ")} of each month"
}

class NoNeedToRunOnThisDayOfWeek(dayOfWeek: DayOfWeek, daysOfWeekToRun: List<DayOfWeek>) : Err, NoNeedToRun  {
    override val message = "No need to run - today is ${dayOfWeek.toString().toLowerCase().capitalize()}, only running on ${daysOfWeekToRun.joinToString(", ") { it.toString().toLowerCase().capitalize() }}"
}

class NoNeedToRunAtThisTime(now: ZonedDateTime, timeToRunAfter: ZonedDateTime, requiredTimeZone: ZoneId) : Err, NoNeedToRun  {
    private val timeZone = requiredTimeZone.getDisplayName(TextStyle.NARROW, Locale.getDefault())
    override val message = "No need to run - time is ${now.hour.padded()}:${now.minute.padded()} in $timeZone, only running after ${timeToRunAfter.hour.padded()}:${timeToRunAfter.minute.padded()} in $timeZone"

    private fun Int.padded() = this.toString().padStart(2, '0')
}

class InvalidStateInFuture : Err {
    override val message = "Exiting due to invalid state, previous email appears to have been sent in the future"
}

class ThisEmailAlreadySent : Err {
    override val message = "Exiting as this exact email has already been sent"
}

class AnEmailAlreadySentToday : Err {
    override val message = "Exiting as an email has already been sent today"
}

class AnEmailAlreadySentThisMonth(now: ZonedDateTime) : Err {
    override val message = "Exiting, email has already been sent for ${now.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${now.year}"
}

class NoMatchingResultsForQuery(queryString: String) : Err {
    override val message = "No matching results for query: '$queryString'"
}

class NonMultipartEmail: Err {
    override val message: String = "Error - forwarding non-multipart emails is not supported"

}

class CouldNotGetRawContentForEmail : Err {
    override val message = "Error - could not get raw message content for email"
}

class ErrorDecoding(messageSuffix: String? = null) : Err {
    override val message = "Error - could not decode raw message${messageSuffix?.let { ", $it" }}"
}

class CouldNotSendEmail(emailSubject: String, recipients: List<InternetAddress>) : Err {
    override val message: String = Handlebars().compileInline("Error sending email with subject '{{subject}}' to {{{recipients}}}")
                                   .apply(mapOf(
                                           "subject" to emailSubject,
                                           "recipients" to recipients.joinToString(", ") { "${it.personal.trim()} <${it.address}>" }
                                   ))
}

class NotAListOfEmailAddresses(string: String) : Err {
    override val message = "Error - $string is not a list of valid email address"
}

class UnknownError : Err {
    override val message = "Exiting due to unknown error"
}
