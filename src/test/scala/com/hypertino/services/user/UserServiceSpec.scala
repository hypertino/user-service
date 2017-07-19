package com.hypertino.services.user

import com.hypertino.binders.value.{Obj, Value}
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{Conflict, Created, DynamicBody, EmptyBody, ErrorBody, MessagingContext, NoContent, NotFound, Ok, ResponseBase}
import com.hypertino.service.config.ConfigLoader
import com.hypertino.user.api.UsersPost
import com.hypertino.user.apiref.authbasic.{EncryptedPassword, EncryptionsPost}
import com.hypertino.user.apiref.hyperstorage.{ContentGet, ContentPut}
import com.typesafe.config.Config
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}
import scaldi.Module

import scala.collection.mutable
import scala.concurrent.duration._

class UserServiceSpec extends FlatSpec with Module with BeforeAndAfterAll with BeforeAndAfterEach with ScalaFutures with Matchers {
  private implicit val scheduler = monix.execution.Scheduler.Implicits.global
  private implicit val mcx = MessagingContext.empty
  bind [Config] to ConfigLoader()
  bind [Scheduler] identifiedBy 'scheduler to scheduler
  bind [Hyperbus] identifiedBy 'hyperbus to injected[Hyperbus]

  private val hyperbus = inject[Hyperbus]
  private val handlers = hyperbus.subscribe(this)
  Thread.sleep(500)
  private val service = new UserService()
  val hyperStorageContent = mutable.Map[String, Value]()

  def onContentPut(implicit request: ContentPut): Task[ResponseBase] = {
    if (hyperStorageContent.put(request.path, request.body.content).isDefined) {
      Task.eval(NoContent(EmptyBody))
    }
    else {
      Task.eval(Created(EmptyBody))
    }
  }

  def onContentGet(implicit request: ContentGet): Task[ResponseBase] = {
    hyperStorageContent.get(request.path) match {
      case Some(v) ⇒ Task.eval(Ok(DynamicBody(v)))
      case None ⇒ Task.eval(NotFound(ErrorBody("not-found", Some(request.path))))
    }
  }

  def onEncryptionsPost(implicit post: EncryptionsPost): Task[ResponseBase] = Task.eval {
    Created(EncryptedPassword(
      post.body.value.reverse
    ))
  }

  "UserService" should "create new user with email" in {
    hyperbus
      .ask(UsersPost(DynamicBody(Obj.from(
        "email" → "me@example.com",
        "password" → "123456"
      ))))
      .runAsync
      .futureValue shouldBe a[Created[_]]
  }

  "UserService" should "not create new user with duplicate email" in {
    hyperbus
      .ask(UsersPost(DynamicBody(Obj.from(
        "email" → "me@example.com",
        "password" → "123456"
      ))))
      .runAsync
      .futureValue shouldBe a[Created[_]]

    hyperbus
      .ask(UsersPost(DynamicBody(Obj.from(
        "email" → "me@example.com",
        "password" → "123456"
      ))))
      .runAsync
      .failed
      .futureValue shouldBe a[Conflict[_]]
  }


  override def afterAll() {
    service.stopService(false, 10.seconds).futureValue
    hyperbus.shutdown(10.seconds).runAsync.futureValue
  }

  override def beforeEach(): Unit = {
    hyperStorageContent.clear()
  }
}
