package com.hypertino.services.user

import com.hypertino.binders.value.{Lst, Null, Obj, Value}
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{BadRequest, Conflict, Created, DynamicBody, ErrorBody, HRL, InternalServerError, MessagingContext, NotFound, Ok, ResponseBase}
import com.hypertino.hyperbus.serialization.SerializationOptions
import com.hypertino.hyperbus.util.{IdGenerator, SeqGenerator}
import com.hypertino.service.control.api.Service
import com.hypertino.user.api.{UserGet, UserPatch, UsersGet, UsersPost}
import com.hypertino.user.apiref.authbasic.{EncryptionsPost, OriginalPassword}
import com.hypertino.user.apiref.hyperstorage.{ContentGet, ContentPut}
import monix.eval.Task
import monix.execution.Scheduler
import org.slf4j.LoggerFactory
import scaldi.{Injectable, Injector}

import scala.util.{Failure, Success}

case class UserServiceConfiguration(keyFields: Map[String, Option[String]])

class UserService (implicit val injector: Injector) extends Service with Injectable {
  protected val log = LoggerFactory.getLogger(getClass)
  protected implicit val scheduler = inject[Scheduler]
  protected val hyperbus = inject[Hyperbus]
  protected val config = UserServiceConfiguration(Map("email" → None))
  protected implicit val so = SerializationOptions.forceOptionalFields
  import so._
  protected val handlers = hyperbus.subscribe(this, log)

  log.info("UserService started")

  def onUsersGet(implicit request: UsersGet): Task[ResponseBase] = {
    if (request.headers.hrl.query.user_id.isDefined) {
      wrapIntoCollection(request.headers.hrl.query.user_id.toString)
    } else {
      identityFields(request.headers.hrl.query)
        .headOption
        .map { identityKey ⇒
          getUserByIdentityKey(identityKey).flatMap {
            case Some(userId) ⇒ wrapIntoCollection(userId)
            case None ⇒ Task.eval(NotFound(ErrorBody("user-not-found", Some(s"$identityKey"))))
          }
        }
        .getOrElse {
          Task.eval(NotFound(ErrorBody("identity-key-not-specified", Some("Can't lookup all users"))))
        }
    }
  }

  // todo: encrypt password!?!
  def onUsersPost(implicit request: UsersPost): Task[ResponseBase] = {
    if (request.body.content.user_id.isDefined) {
      Task.raiseError(BadRequest(ErrorBody("user-id-prohibited", Some("You can't set user_id explicitly"))))
    }
    else {
      import com.hypertino.binders.value._
      val iflds = identityFields(request.body.content)
      getUsersByIdentityKeys(iflds).flatMap { existingUsers ⇒
        if (existingUsers.nonEmpty) {
          Task.raiseError(Conflict(ErrorBody("duplicate-identity-keys", Some("There is an existing user with identity keys"), extra = existingUsers.toValue)))
        }
        else {
          // todo: we need here a transaction manager
          val userId = IdGenerator.create()
          val userIdBody = DynamicBody(Obj.from("user_id" → userId))

          Task.gatherUnordered {
            iflds.map { ifld ⇒
              val path = hyperStorageUserPathByIdentityKey(ifld._1, ifld._2.toString)
              hyperbus
                .ask(ContentPut(path, userIdBody))
                .onErrorRestart(3)
                .materialize
                .map((_, ifld, path))
            }
          } map { lst ⇒
            val success = lst.forall(_._1.isSuccess)
            if (success) {
              encryptPassword(request.body.content).flatMap { userBody ⇒
                hyperbus
                  .ask(ContentPut(hyperStorageUserPath(userId), DynamicBody(userBody)))
                  .onErrorRestart(3)
                  .materialize
                  .map {
                    case Success(Created(_)) ⇒
                      Created(userIdBody, location=HRL(UserGet.location, Obj.from("user_id" → userId)))

                    case Failure(exception) ⇒
                      // todo: rollback keys, or use transaction manager
                      log.error(s"Can't create user '$userId' for $request", exception)
                      InternalServerError(ErrorBody("db-error", Some("Can't create user")))
                  }
              }
            }
            else {
              val errorId = SeqGenerator.create()
              lst.filter(_._1.isFailure).foreach { s ⇒
                log.error(s"Can't put $userIdBody to ${s._3} #$errorId", s._1.failed.get)
              }
              Task.eval(InternalServerError(ErrorBody("db-error", Some("Can't update user identity keys"), errorId=errorId)))
            }
          } flatten
        }
      }
    }
  }

  def onUserGet(implicit request: UserGet): Task[Ok[DynamicBody]] = {
    getUserByUserId(request.userId)
      .map(user ⇒ Ok(DynamicBody(user)))
  }

  def onUserPatch(implicit request: UserPatch): Task[ResponseBase] = {
    //  1. check if exists with the changed identity-key's
    //  2. insert new identity-key -> user_id
    //  3. delete old identity-keys
    //  4. update user
    ???
  }

  protected def encryptPassword(user: Value)(implicit mcx: MessagingContext): Task[Value] = {
    if (user.password.isDefined) {
      hyperbus
        .ask(EncryptionsPost(OriginalPassword(user.password.toString)))
        .map{ response ⇒
          user + Obj.from("password" → response.body.value, "has_password" → true)
        }
    }
    else Task.eval {
      user
    }
  }

  protected def wrapIntoCollection(userId: String)(implicit mcx: MessagingContext): Task[ResponseBase] = {
    getUserByUserId(userId).map {
      case Null ⇒ NotFound(ErrorBody("resource-not-found", Some(s"User $userId is not found")))
      case user: Obj ⇒ Ok(DynamicBody(Lst.from(user)))
    }
  }

  protected def identityFields(obj: Value): Map[String, Value] = {
    obj.toMap.filter(kv ⇒ config.keyFields.contains(kv._1)).toMap
  }

  protected def getUserByUserId(userId: String)(implicit mcx: MessagingContext): Task[Value] = {
    hyperbus
      .ask(ContentGet(hyperStorageUserPath(userId)))
      .map(_.body.content)
      .onErrorRecover {
        case _: NotFound[_] ⇒ Null
      }
  }

  protected def getUsersByIdentityKeys(identityKeys: Map[String, Value])
                                    (implicit mcx: MessagingContext): Task[Map[String, String]] = {
    Task.gatherUnordered {
      identityKeys.map { case (identityKeyType, identityKey) ⇒
        getUserByIdentityKey(identityKeyType, identityKey).map {
          case Some(userId) ⇒ Some(identityKeyType → userId)
          case None ⇒ None
        }
      }
    }.map(_.flatten.toMap)
  }

  protected def getUserByIdentityKey(identityKey: (String, Value))
                                  (implicit mcx: MessagingContext): Task[Option[String]] = {
    hyperbus
      .ask(ContentGet(hyperStorageUserPathByIdentityKey(identityKey._1, identityKey._2.toString)))
      .map { ok ⇒
        Some(ok.body.content.user_id.toString)
      }
      .onErrorRecover {
        case _: NotFound[_] ⇒
          None
      }
  }

  protected def hyperStorageUserPath(userId: String): String = s"/services/user/users/{$userId}"

  protected def hyperStorageUserPathByIdentityKey(identityType: String, identityKey: String): String = s"/services/user/users-by-$identityType/$identityKey"

  def stopService(controlBreak: Boolean): Unit = {
    handlers.foreach(_.cancel())
    log.info("UserService stopped")
  }
}
