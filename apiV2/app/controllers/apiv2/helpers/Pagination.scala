package controllers.apiv2.helpers

import io.circe.derivation.annotations.SnakeCaseJsonCodec

@SnakeCaseJsonCodec case class Pagination(
    limit: Long,
    offset: Long,
    count: Long
)
