package com.hypertino.services.user

import com.hypertino.binders.value.{Lst, Null, Obj, Value}
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{BadRequest, Conflict, Created, DynamicBody, EmptyBody, ErrorBody, HRL, InternalServerError, MessagingContext, Method, NoContent, NotFound, Ok, RequestBase, ResponseBase}
import com.hypertino.hyperbus.serialization.SerializationOptions
import com.hypertino.hyperbus.subscribe.Subscribable
import com.hypertino.hyperbus.util.{IdGenerator, SeqGenerator}
import com.hypertino.service.control.api.Service
import com.hypertino.services.user.utils.ErrorCode
import com.hypertino.user.api._
import com.hypertino.user.apiref.authbasic.{EncryptionsPost, OriginalPassword}
import com.hypertino.user.apiref.hyperstorage.{ContentDelete, ContentGet, ContentPatch, ContentPut}
import monix.eval.Task
import monix.execution.Scheduler
import com.typesafe.scalalogging.StrictLogging
import scaldi.{Injectable, Injector}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

case class KeyFieldOptions(autoMerge: Boolean, ignoreCase: Boolean)
case class UserServiceConfiguration(keyFields: Map[String, KeyFieldOptions])

class UserService (implicit val injector: Injector) extends Service with Injectable with Subscribable with StrictLogging {
  protected implicit val scheduler = inject[Scheduler]
  protected val hyperbus = inject[Hyperbus]
  protected val config = UserServiceConfiguration(Map("email" → KeyFieldOptions(false, true), "facebook_user_id" → KeyFieldOptions(true, true)))
  protected implicit val so = SerializationOptions.forceOptionalFields

  import so._

  protected val handlers = hyperbus.subscribe(this, logger)
  protected final val USER_COLLECTION_BODY_TYPE = "user-collection"

  logger.info(s"${getClass.getName} is STARTED")

  def onUsersGet(implicit request: UsersGet): Task[ResponseBase] = {
    if (request.headers.hrl.query.dynamic.user_id.isDefined) {
      wrapIntoCollection(request.headers.hrl.query.dynamic.user_id.toString)
    } else {
      identityFields(request.headers.hrl.query)
        .headOption
        .map { identityKey ⇒
          getUserByIdentityKey(identityKey).flatMap {
            case Some(userId) ⇒ wrapIntoCollection(userId)
            case None ⇒ Task.eval(Ok(DynamicBody(Lst.empty, contentType = Some(USER_COLLECTION_BODY_TYPE))))
          }
        }
        .getOrElse {
          Task.eval(NotFound(ErrorBody(ErrorCode.IDENTITY_KEY_NOT_SPECIFIED, Some("Can't lookup all users"))))
        }
    }
  }

  def onUsersPost(implicit request: UsersPost): Task[ResponseBase] = {
    if (request.body.content.dynamic.user_id.isDefined) {
      Task.raiseError(BadRequest(ErrorBody(ErrorCode.USER_ID_IS_PROTECTED, Some("You can't set user_id explicitly"))))
    }
    else {
      val iflds = identityFields(request.body.content)

      getUsersByIdentityKeys(iflds).flatMap { existingUsers ⇒

        if (existingUsers.nonEmpty) {
          if (iflds.exists(f ⇒ config.keyFields.get(f._1).exists(_.autoMerge))
            && existingUsers.size == 1
            && !iflds.contains("user_id")
          ) {
            val userId = existingUsers.head
            hyperbus
              .ask(ContentGet(hyperStorageUserPath(userId)))
              .flatMap {
                case ok@Ok(_: DynamicBody, _) ⇒
                  val existingIflds = identityFields(ok.body.content)
                  // no existing ifield have conflicting value
                  if (iflds.forall{ case (k,v) ⇒
                    existingIflds.get(k) match {
                      case Some(ev) ⇒
                        ev == v
                      case None ⇒
                        true
                    }
                  }) {
                    val bodyWithoutNulls = Value.removeNullFields(request.body.content)
                    postOrPatchUser(UserPatch(userId, request.body), userId, bodyWithoutNulls, iflds, Map.empty)
                  }
                  else {
                    Task.raiseError(duplicateError(existingUsers))
                  }
              }
          }
          else {
            Task.raiseError(duplicateError(existingUsers))
          }
        }
        else {
          postOrPatchUser(request, IdGenerator.create(), request.body.content, iflds, Map.empty)
        }
      }
    }
  }

  def onUserGet(implicit request: UserGet): Task[Ok[DynamicBody]] = {
    hyperbus
      .ask(ContentGet(hyperStorageUserPath(request.userId)))
      .map {
        case ok@Ok(_: DynamicBody, _) ⇒ ok
      }
  }

  def onUserPatch(implicit request: UserPatch): Task[ResponseBase] = {
    if (Obj.hasPath(request.body.content.asInstanceOf[Obj], Seq("user_id")) &&
      request.body.content.dynamic.user_id.toString != request.userId) {
      Task.raiseError(BadRequest(ErrorBody(ErrorCode.USER_ID_IS_PROTECTED, Some("You can't modify user_id"))))
    }
    else {
      hyperbus
        .ask(ContentGet(hyperStorageUserPath(request.userId)))
        .flatMap {
          case ok@Ok(_: DynamicBody, _) ⇒
            val existinUser = ok.body.content

            val existingIflds = identityFields(existinUser)
            val requestIflds = identityFields(request.body.content)
            val newIflds = requestIflds.flatMap { case (k, v) ⇒
              existingIflds.get(k) match {
                case Some(ev) if ev == v ⇒ None
                case _ ⇒ Some(k → v)
              }
            }
            getUsersByIdentityKeys(newIflds).flatMap { existingUsers ⇒
              if (existingUsers.nonEmpty) {
                Task.raiseError(duplicateError(existingUsers))
              }
              else {

                val removedIflds = requestIflds.flatMap { case (k, v) ⇒
                  existingIflds.get(k) match {
                    case Some(ev) if ev.isDefined && ev != v ⇒ Some(k → ev)
                    case _ ⇒ None
                  }
                }

                postOrPatchUser(request, request.userId, request.body.content, newIflds, removedIflds)
              }
            }
        }
    }
  }

  protected def duplicateError(existingUsers: List[String])(implicit mcx: MessagingContext) = {
    import com.hypertino.binders.value._
    Conflict(ErrorBody(ErrorCode.DUPLICATE_IDENTITY_KEYS, Some("There is an existing user with identity keys"), extra = existingUsers.toValue))
  }

  protected def postOrPatchUser(request: RequestBase,
                              userId: String,
                              userBody: Value,
                              newIflds: Map[String, Value],
                              removedIflds: Map[String, Value]
                             ): Task[ResponseBase] = {
    implicit val mcx = request
    val userIdBody = DynamicBody(Obj.from("user_id" → userId))

    Task.gatherUnordered {
      newIflds.map { ifld ⇒
        val path = hyperStorageUserPathByIdentityKey(ifld._1, ifld._2.toString)
        hyperbus
          .ask(ContentPut(path, userIdBody))
          .onErrorRestart(3)
          .materialize
          .map((_, ifld, path, true))
      } ++
        removedIflds.map { ifld ⇒
          val path = hyperStorageUserPathByIdentityKey(ifld._1, ifld._2.toString)
          hyperbus
            .ask(ContentDelete(path))
            .onErrorRestart(3)
            .materialize
            .map((_, ifld, path, false))
        }
    } flatMap { lst ⇒
      val success = lst.forall(_._1.isSuccess)
      if (success) {
        encryptPassword(userBody).flatMap { newBody ⇒
          val resultTask = if (request.headers.method == Method.PATCH) {
            hyperbus.ask(ContentPatch(hyperStorageUserPath(userId), DynamicBody(newBody)))
          }
          else {
            hyperbus.ask(ContentPut(hyperStorageUserPath(userId), DynamicBody(newBody % userIdBody.content)))
          }
          resultTask
            .onErrorRestart(3)
            .materialize
            .map {
              case Success(Created(_)) ⇒
                Created(UpdatedUser(userId), location = HRL(UserGet.location, Obj.from("user_id" → userId)))

              case Success(_) ⇒
                Ok(UpdatedUser(userId))

              case Failure(exception) ⇒
                // todo: rollback keys, or use transaction manager
                val s = if (request.headers.method == Method.PATCH) {
                  "create user"
                }
                else {
                  "update user"
                }
                logger.error(s"Can't $s '$userId' for $request", exception)
                InternalServerError(ErrorBody(ErrorCode.DB_ERROR, Some(s"Can't $s")))
            }
        }
      }
      else {
        val errorId = SeqGenerator.create()
        lst.filter(_._1.isFailure).foreach { s ⇒
          val method = if (s._4) "put" else "remove"
          logger.error(s"Can't $method $userIdBody to ${s._3} #$errorId", s._1.failed.get)
        }
        Task.eval(InternalServerError(ErrorBody(ErrorCode.DB_ERROR, Some("Can't update user identity keys"), errorId = errorId)))
      }
    }
  }


  protected def encryptPassword(user: Value)(implicit mcx: MessagingContext): Task[Value] = {
    if (user.dynamic.password.isDefined) {
      hyperbus
        .ask(EncryptionsPost(OriginalPassword(user.dynamic.password.toString)))
        .map{ response ⇒
          user % Obj.from("password" → response.body.value, "has_password" → true)
        }
    }
    else Task.eval {
      if (Obj.hasPath(user.asInstanceOf[Obj], Seq("password")) && user.dynamic.password.isNull) {
        user % Obj.from("has_password" → false)
      }
      else {
        user
      }
    }
  }

  protected def wrapIntoCollection(userId: String)(implicit mcx: MessagingContext): Task[ResponseBase] = {
    hyperbus
      .ask(ContentGet(hyperStorageUserPath(userId)))
      .map(_.body.content)
      .materialize
      .map {
        case Success(user) ⇒ Success(Ok(DynamicBody(Lst.from(user), contentType=Some(USER_COLLECTION_BODY_TYPE))))
        case Failure(NotFound(_)) ⇒ Success(Ok(DynamicBody(Lst.empty, contentType=Some(USER_COLLECTION_BODY_TYPE))))
        case Failure(other) ⇒ Failure(other)
      }
      .dematerialize
  }

  protected def identityFields(obj: Value): Map[String, Value] = {
    obj.toMap.filter(kv ⇒ config.keyFields.contains(kv._1)).toMap.filterNot(_._2 == Null)
  }

  protected def getUsersByIdentityKeys(identityKeys: Map[String, Value])
                                    (implicit mcx: MessagingContext): Task[List[String]] = {
    Task.gatherUnordered {
      identityKeys.map { case (identityKeyType, identityKey) ⇒
        getUserByIdentityKey(identityKeyType, identityKey)
      }
    }.map(_.flatten.toList)
  }

  protected def getUserByIdentityKey(identityKey: (String, Value))
                                  (implicit mcx: MessagingContext): Task[Option[String]] = {
    hyperbus
      .ask(ContentGet(hyperStorageUserPathByIdentityKey(identityKey._1, identityKey._2.toString)))
      .map { ok ⇒
        Some(ok.body.content.dynamic.user_id.toString)
      }
      .onErrorRecover {
        case _: NotFound[_] ⇒
          None
      }
  }

  protected def hyperStorageUserPath(userId: String): String = s"user-service/users/$userId"

  protected def hyperStorageUserPathByIdentityKey(identityType: String, identityKey: String): String = {
    val key = if (config.keyFields.get(identityType).exists(_.ignoreCase)) identityKey.toLowerCase() else identityKey
    s"user-service/users-by-$identityType/$key"
  }

  override def stopService(controlBreak: Boolean, timeout: FiniteDuration): Future[Unit] = Future {
    handlers.foreach(_.cancel())
    logger.info(s"${getClass.getName} is STOPPED")
  }
}
