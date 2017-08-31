package com.hypertino.services.user

import com.hypertino.binders.value.{Obj, Text, Value}
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{Conflict, Created, DynamicBody, EmptyBody, ErrorBody, MessagingContext, NoContent, NotFound, Ok, ResponseBase}
import com.hypertino.hyperbus.subscribe.Subscribable
import com.hypertino.service.config.ConfigLoader
import com.hypertino.user.api.{UpdatedUser, UserPatch, UsersPost}
import com.hypertino.user.apiref.authbasic.{EncryptedPassword, EncryptionsPost}
import com.hypertino.user.apiref.hyperstorage.{ContentDelete, ContentGet, ContentPatch, ContentPut}
import com.typesafe.config.Config
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}
import scaldi.Module

import scala.collection.mutable
import scala.concurrent.duration._

class UserServiceSpec extends FlatSpec with Module with BeforeAndAfterAll with BeforeAndAfterEach with ScalaFutures with Matchers with Subscribable {
  implicit val patience = PatienceConfig(scaled(Span(60, Seconds)))
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
      Task.eval(Ok(EmptyBody))
    }
    else {
      Task.eval(Created(EmptyBody))
    }
  }

  def onContentPatch(implicit request: ContentPatch): Task[ResponseBase] = {
    hyperStorageContent.get(request.path) match {
      case Some(v) ⇒
        hyperStorageContent.put(request.path, v + request.body.content)
        Task.eval(Ok(EmptyBody))

      case None ⇒
        Task.eval(NotFound(ErrorBody("not-found")))
    }
  }

  def onContentDelete(implicit request: ContentDelete): Task[ResponseBase] = {
    if (hyperStorageContent.remove(request.path).isDefined) {
      Task.eval(Ok(EmptyBody))
    }
    else {
      Task.eval(NotFound(ErrorBody("not-found")))
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
    val u = hyperbus
      .ask(UsersPost(DynamicBody(Obj.from(
        "email" → "me@example.com",
        "password" → "123456"
      ))))
      .runAsync
      .futureValue
    u shouldBe a[Created[_]]
    u.body shouldBe a[UpdatedUser]
  }

  it should "not create new user with duplicate email" in {
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

  it should "patch user" in {
    val u = hyperbus
      .ask(UsersPost(DynamicBody(Obj.from(
        "email" → "me@example2.com",
        "password" → "123456"
      ))))
      .runAsync
      .futureValue
    u shouldBe a[Created[_]]
    u.body shouldBe a[UpdatedUser]

    val userId = u.body.userId
    hyperStorageContent.get(s"user-service/users/$userId") shouldBe Some(Obj.from(
      "email" → "me@example2.com", "password" → "654321", "has_password" → true, "user_id" → userId
    ))

    hyperStorageContent.get(s"user-service/users-by-email/me@example2.com") shouldBe Some(Obj.from(
      "user_id" → userId
    ))

    val u2 = hyperbus
      .ask(UserPatch(userId, DynamicBody(Obj.from(
        "email" → "me@example3.com",
        "password" → "abcde"
      ))))
      .runAsync
      .futureValue
    u2 shouldBe a[Ok[_]]
    u2.body.userId shouldBe userId

    hyperStorageContent.get(s"user-service/users/$userId") shouldBe Some(Obj.from(
      "email" → "me@example3.com", "password" → "edcba", "has_password" → true, "user_id" → userId)
    )

    hyperStorageContent.get(s"user-service/users-by-email/me@example2.com") shouldBe None
    hyperStorageContent.get(s"user-service/users-by-email/me@example3.com") shouldBe Some(Obj.from(
      "user_id" → userId
    ))
  }

  it should "ignore same-value-fields when patching user" in {
    val u = hyperbus
      .ask(UsersPost(DynamicBody(Obj.from(
        "email" → "me@example2.com",
        "password" → "123456"
      ))))
      .runAsync
      .futureValue
    u shouldBe a[Created[_]]
    u.body shouldBe a[UpdatedUser]

    val userId = u.body.userId
    hyperStorageContent.get(s"user-service/users/$userId") shouldBe Some(Obj.from(
      "email" → "me@example2.com", "password" → "654321", "has_password" → true, "user_id" → userId
    ))

    hyperStorageContent.get(s"user-service/users-by-email/me@example2.com") shouldBe Some(Obj.from(
      "user_id" → userId
    ))

    val u2 = hyperbus
      .ask(UserPatch(userId, DynamicBody(Obj.from(
        "email" → "me@example2.com",
        "password" → "abcde",
        "first_name" → "Yey"
      ))))
      .runAsync
      .futureValue
    u2 shouldBe a[Ok[_]]
    u2.body.userId shouldBe userId

    hyperStorageContent.get(s"user-service/users/$userId") shouldBe Some(Obj.from(
      "email" → "me@example2.com", "password" → "edcba", "has_password" → true, "user_id" → userId, "first_name" → "Yey")
    )

    hyperStorageContent.get(s"user-service/users-by-email/me@example2.com") shouldBe Some(Obj.from(
      "user_id" → userId
    ))
  }

  it should "not create patch user with duplicate email" in {
    hyperbus
      .ask(UsersPost(DynamicBody(Obj.from(
        "email" → "me@example.com",
        "password" → "123456"
      ))))
      .runAsync
      .futureValue shouldBe a[Created[_]]


    val u = hyperbus
      .ask(UsersPost(DynamicBody(Obj.from(
        "email" → "me@example2.com",
        "password" → "123456"
      ))))
      .runAsync
      .futureValue
    u shouldBe a[Created[_]]
    u.body shouldBe a[UpdatedUser]

    val userId = u.body.userId
    val u2 = hyperbus
      .ask(UserPatch(userId, DynamicBody(Obj.from(
        "email" → "me@example.com",
        "password" → "abcde"
      ))))
      .runAsync
      .failed
      .futureValue shouldBe a[Conflict[_]]
  }

  it should "merge user with duplicate email and facebook_user_id" in {
    val u1 = hyperbus
      .ask(UsersPost(DynamicBody(Obj.from(
        "email" → "me@example.com",
        "password" → "123456"
      ))))
      .runAsync
      .futureValue
    u1 shouldBe a[Created[_]]
    u1.body shouldBe a[UpdatedUser]
    val userId = u1.body.userId

    val u2 = hyperbus
      .ask(UsersPost(DynamicBody(Obj.from(
        "email" → "me@example.com",
        "facebook_user_id" → "100500"
      ))))
      .runAsync
      .futureValue
    u2 shouldBe a[Ok[_]]
    u2.body.userId shouldBe userId

    hyperStorageContent.get(s"user-service/users/$userId") shouldBe Some(Obj.from(
      "email" → "me@example.com", "password" → "654321", "has_password" → true, "user_id" → userId, "facebook_user_id" → "100500")
    )

    hyperStorageContent.get(s"user-service/users-by-email/me@example.com") shouldBe Some(Obj.from(
      "user_id" → userId
    ))

    hyperStorageContent.get(s"user-service/users-by-facebook_user_id/100500") shouldBe Some(Obj.from(
      "user_id" → userId
    ))
  }

  override def afterAll() {
    service.stopService(false, 10.seconds).futureValue
    hyperbus.shutdown(10.seconds).runAsync.futureValue
  }

  override def beforeEach(): Unit = {
    hyperStorageContent.clear()
  }
}
