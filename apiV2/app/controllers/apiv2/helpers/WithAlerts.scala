package controllers.apiv2.helpers

import io.circe.{Encoder, Json}
import io.circe.syntax._

case class WithAlerts[A](
    obj: A,
    errors: Seq[String] = Nil,
    success: Seq[String] = Nil,
    info: Seq[String] = Nil,
    warnings: Seq[String] = Nil
)
object WithAlerts {

  implicit def encoder[A: Encoder.AsObject]: Encoder[WithAlerts[A]] = (a: WithAlerts[A]) => {
    def noneIfEmpty(xs: Seq[String]): Option[Seq[String]] = if (xs.isEmpty) None else Some(xs)

    val alerts = Json.obj(
      "alerts" := Json.obj(
        "errors" := noneIfEmpty(a.errors),
        "success" := noneIfEmpty(a.success),
        "info" := noneIfEmpty(a.info),
        "warnings" := noneIfEmpty(a.warnings)
      )
    )

    alerts.deepDropNullValues.deepMerge(a.obj.asJson)
  }
}
