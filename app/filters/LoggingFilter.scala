package filters

import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}

import play.api.mvc._

import akka.stream.Materializer
import com.typesafe.scalalogging.Logger

class LoggingFilter @Inject()(implicit val mat: Materializer, ec: ExecutionContext) extends Filter {

  val timingsLogger = Logger("Timings")

  def apply(nextFilter: RequestHeader => Future[Result])(requestHeader: RequestHeader): Future[Result] = {
    val startTime = System.currentTimeMillis

    nextFilter(requestHeader).map { result =>
      val endTime     = System.currentTimeMillis
      val requestTime = endTime - startTime

      if (requestTime > 1000) {
        timingsLogger.warn(
          s"${requestHeader.method} ${requestHeader.uri} took ${requestTime}ms and returned ${result.header.status}"
        )
      } else {
        timingsLogger.info(
          s"${requestHeader.method} ${requestHeader.uri} took ${requestTime}ms and returned ${result.header.status}"
        )
      }

      result.withHeaders("Request-Time" -> requestTime.toString)
    }
  }
}
