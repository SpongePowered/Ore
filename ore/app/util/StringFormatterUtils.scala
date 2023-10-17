package util

import java.time.OffsetDateTime

import play.api.i18n.Messages

import ore.util.StringLocaleFormatterUtils

object StringFormatterUtils {

  /**
    * Formats the specified date into the standard application form.
    *
    * @param dateTime Date to format
    * @return        Standard formatted date
    */
  def prettifyDate(dateTime: OffsetDateTime)(implicit messages: Messages): String =
    StringLocaleFormatterUtils.prettifyDate(dateTime)(messages.lang.locale)

  /**
    * Formats the specified date into the standard application form time.
    *
    * @param dateTime Date to format
    * @return        Standard formatted date
    */
  def prettifyDateAndTime(dateTime: OffsetDateTime)(implicit messages: Messages): String =
    StringLocaleFormatterUtils.prettifyDateAndTime(dateTime)(messages.lang.locale)
}
