package com.hypertino.services.user

import com.hypertino.binders.value.Lst
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{DynamicBody, ErrorBody, NotFound, Ok, ResponseBase}
import com.hypertino.hyperbus.serialization.SerializationOptions
import com.hypertino.service.control.api.Service
import com.hypertino.user.api.{UserGet, UserPatch, UsersGet, UsersPost}
import com.hypertino.user.use.hyperstorage.ContentGet
import monix.eval.Task
import monix.execution.Scheduler
import org.slf4j.LoggerFactory
import scaldi.{Injectable, Injector}

case class UserServiceConfiguration(keyFields: Map[String, Option[String]])

class UserService (implicit val injector: Injector) extends Service with Injectable {
  private val log = LoggerFactory.getLogger(getClass)
  private implicit val scheduler = inject[Scheduler]
  private val hyperbus = inject[Hyperbus]
  private val config = UserServiceConfiguration(Map("user_id" → None, "email" → None))
  private implicit val so = SerializationOptions.forceOptionalFields
  private val handlers = hyperbus.subscribe(this, log)

  log.info("UserService started")

  def onUsersGet(implicit request: UsersGet): Task[ResponseBase] = {
    if (request.headers.hrl.query.user_id.isDefined) {
      wrapIntoCollection(request.headers.hrl.query.user_id.toString)
    } else {
      val identityKey = request.headers.hrl.query.toMap.find { kv ⇒
        kv._1 != "user_id" && config.keyFields.contains(kv._1)
      }
      identityKey.map { ik ⇒
        hyperbus
          .ask(ContentGet(hyperStorageUserPathByIdentityKey(ik._1, ik._2.toString)))
          .map { ok ⇒
            wrapIntoCollection(ok.body.content.user_id.toString)
          }
          .flatten
      } getOrElse {
        Task.eval(NotFound(ErrorBody("identity-key-not-specified", Some("Can't lookup all users"))))
      }
    }
  }

  def onUsersPost(implicit request: UsersPost): Task[ResponseBase] = {
    //  1. check if exists with the identity-key's
    //  2. insert identity-key -> user_id
    //  3. insert user
    ???
  }

  def onUserGet(implicit request: UserGet): Task[Ok[DynamicBody]] = {
    hyperbus
      .ask(ContentGet(hyperStorageUserPath(request.userId)))
  }

  def onUserPatch(implicit request: UserPatch): Task[ResponseBase] = {
    //  1. check if exists with the changed identity-key's
    //  2. insert new identity-key -> user_id
    //  3. delete old identity-keys
    //  4. update user
    ???
  }

  private def wrapIntoCollection(userId: String): Task[ResponseBase] = {
    onUserGet(UserGet(userId)).map { ok ⇒
      Ok(DynamicBody(Lst.from(ok.body.content)))
    } onErrorRecover {
      case _: NotFound[_] ⇒ Ok(DynamicBody(Lst.empty))
    }
  }

  private def hyperStorageUserPath(userId: String): String = s"/services/user/users/{$userId}"

  private def hyperStorageUserPathByIdentityKey(identityType: String, identityKey: String): String = s"/services/user/users-by-$identityType/$identityKey"

  def stopService(controlBreak: Boolean): Unit = {
    handlers.foreach(_.cancel())
    log.info("UserService stopped")
  }
}
