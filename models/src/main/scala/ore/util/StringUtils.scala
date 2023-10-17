package ore.util

import java.security.MessageDigest
import java.util.Locale

import ore.db.impl.OrePostgresDriver.api._

/**
  * Helper class for handling User input.
  */
object StringUtils {

  private val replaceRegex = """[^a-z\-_.0-9]""".r.unanchored

  /**
    * Returns a URL readable string from the specified string.
    *
    * @param str  String to create slug for
    * @return     Slug of string
    */
  def slugify(str: String): String = {
    val replaced = replaceRegex.replaceAllIn(compact(str).toLowerCase(Locale.ROOT).replace(' ', '-'), "")
    replaced.substring(0, Math.min(32, replaced.length))
  }

  /**
    * Returns the specified String with all consecutive spaces removed.
    *
    * @param str  String to compact
    * @return     Compacted string
    */
  def compact(str: String): String = str.trim.replaceAll(" +", " ")

  /**
    * Returns null if the specified String is empty, returns the trimmed string
    * otherwise.
    *
    * @param str String to check
    * @return Null if empty, trimmed otherwise
    */
  def noneIfEmpty(str: String): Option[String] = {
    val trimmed = str.trim
    if (trimmed.nonEmpty) Some(trimmed) else None
  }

  /**
    * Compares a Rep[String] to a String after converting them to lower case.
    *
    * @param str1 String 1
    * @param str2 String 2
    * @return     Result
    */
  def equalsIgnoreCase[T <: Table[_]](str1: T => Rep[String], str2: String): T => Rep[Boolean] =
    str1(_).toLowerCase === str2.toLowerCase

  //https://stackoverflow.com/a/9855338
  private val hexArray = "0123456789abcdef".toCharArray
  def bytesToHex(bytes: Array[Byte]): String = {
    val hexChars = new Array[Char](bytes.length * 2)
    var j        = 0
    while (j < bytes.length) {
      val v = bytes(j) & 0xFF
      hexChars(j * 2) = hexArray(v >>> 4)
      hexChars(j * 2 + 1) = hexArray(v & 0x0F)

      j += 1
    }
    new String(hexChars)
  }

  def md5ToHex(bytes: Array[Byte]): String =
    bytesToHex(MessageDigest.getInstance("MD5").digest(bytes))
}
