package controllers.apiv2.helpers

import cats.data.NonEmptyList
import cats.kernel.Semigroup
import io.circe.derivation.annotations.SnakeCaseJsonCodec

@SnakeCaseJsonCodec case class ApiError(error: String)
@SnakeCaseJsonCodec case class ApiErrors(errors: NonEmptyList[String])
object ApiErrors {
  implicit val semigroup: Semigroup[ApiErrors] = (x: ApiErrors, y: ApiErrors) => ApiErrors(x.errors.concatNel(y.errors))
}

@SnakeCaseJsonCodec case class UserError(userError: String)
@SnakeCaseJsonCodec case class UserErrors(userErrors: NonEmptyList[String])
