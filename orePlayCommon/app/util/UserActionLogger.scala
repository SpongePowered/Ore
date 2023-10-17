package util

import controllers.sugar.Requests.{ApiRequest, AuthRequest}
import ore.StatTracker
import ore.db.{DbRef, Model, ModelQuery, ModelService}
import ore.models.user.{LoggedActionCommon, LoggedActionType}

import com.github.tminglei.slickpg.InetString

object UserActionLogger {

  def log[F[_], Ctx, M: ModelQuery](
      request: AuthRequest[_],
      action: LoggedActionType[Ctx],
      ctxId: DbRef[Ctx],
      newState: String,
      oldState: String
  )(createAction: LoggedActionCommon[Ctx] => M)(implicit service: ModelService[F]): F[Model[M]] =
    logOption(request, action, Some(ctxId), newState, oldState)(createAction)

  def logOption[F[_], Ctx, M: ModelQuery](
      request: AuthRequest[_],
      action: LoggedActionType[Ctx],
      ctxId: Option[DbRef[Ctx]],
      newState: String,
      oldState: String
  )(createAction: LoggedActionCommon[Ctx] => M)(implicit service: ModelService[F]): F[Model[M]] = {
    val common = LoggedActionCommon(
      Some(request.user.id),
      InetString(StatTracker.remoteAddress(request)),
      action,
      ctxId,
      newState,
      oldState
    )

    service.insert(createAction(common))
  }

  def logApi[F[_], Ctx, M: ModelQuery](
      request: ApiRequest[_, _],
      action: LoggedActionType[Ctx],
      ctxId: DbRef[Ctx],
      newState: String,
      oldState: String
  )(createAction: LoggedActionCommon[Ctx] => M)(implicit service: ModelService[F]): F[Model[M]] =
    logApiOption(request, action, Some(ctxId), newState, oldState)(createAction)

  def logApiOption[F[_], Ctx, M: ModelQuery](
      request: ApiRequest[_, _],
      action: LoggedActionType[Ctx],
      ctxId: Option[DbRef[Ctx]],
      newState: String,
      oldState: String
  )(createAction: LoggedActionCommon[Ctx] => M)(implicit service: ModelService[F]): F[Model[M]] = {
    val common = LoggedActionCommon(
      request.user.map(_.id),
      InetString(StatTracker.remoteAddress(request)),
      action,
      ctxId,
      newState,
      oldState
    )

    service.insert(createAction(common))
  }
}
