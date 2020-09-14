/*
 * Copyright 2020 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.customs.rosmfrontend.controllers.registration

import javax.inject.{Inject, Singleton}
import play.api.Application
import play.api.mvc.{Action, _}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.customs.rosmfrontend.connector.SUB09SubscriptionDisplayConnector
import uk.gov.hmrc.customs.rosmfrontend.controllers.CdsController
import uk.gov.hmrc.customs.rosmfrontend.controllers.subscription.routes._
import uk.gov.hmrc.customs.rosmfrontend.domain._
import uk.gov.hmrc.customs.rosmfrontend.domain.messaging.subscription.SubscriptionDisplayResponse
import uk.gov.hmrc.customs.rosmfrontend.domain.registration.UserLocation
import uk.gov.hmrc.customs.rosmfrontend.domain.subscription.RecipientDetails
import uk.gov.hmrc.customs.rosmfrontend.models.Journey
import uk.gov.hmrc.customs.rosmfrontend.services.RandomUUIDGenerator
import uk.gov.hmrc.customs.rosmfrontend.services.cache.{RequestSessionData, SessionCache}
import uk.gov.hmrc.customs.rosmfrontend.services.subscription.{
  HandleSubscriptionService,
  SubscriptionDetailsService,
  TaxEnrolmentsService
}
import uk.gov.hmrc.customs.rosmfrontend.views.html.error_template
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SubscriptionRecoveryController @Inject()(
  override val currentApp: Application,
  override val authConnector: AuthConnector,
  handleSubscriptionService: HandleSubscriptionService,
  taxEnrolmentService: TaxEnrolmentsService,
  sessionCache: SessionCache,
  SUB09Connector: SUB09SubscriptionDisplayConnector,
  mcc: MessagesControllerComponents,
  errorTemplateView: error_template,
  uuidGenerator: RandomUUIDGenerator,
  requestSessionData: RequestSessionData,
  subscriptionDetailsService: SubscriptionDetailsService
)(implicit ec: ExecutionContext)
    extends CdsController(mcc) {

  def complete(journey: Journey.Value): Action[AnyContent] = ggAuthorisedUserWithEnrolmentsAction {
    implicit request => _: LoggedInUserWithEnrolments =>
      {
        val isRowF = Future.successful(UserLocation.isRow(requestSessionData))
        val journeyF = Future.successful(journey)
        val cachedCustomsIdF = subscriptionDetailsService.cachedCustomsId
        val result = for {
          isRow <- isRowF
          journey <- journeyF
          customId <- if (isRow) cachedCustomsIdF else Future.successful(None)
        } yield {
          (journey, isRow, customId) match {
            case (Journey.Subscribe, true, Some(_)) => subscribeForCDS // UK journey
            case (Journey.Subscribe, true, None)             => subscribeForCDSROW //subscribeForCDSROW //ROW
            case (Journey.Subscribe, false, _)               => subscribeForCDS //UK Journey
            case _                                         => subscribeGetAnEori //Journey Get An EORI
          }
        }
        result.flatMap(identity)
      }
  }

  private def subscribeGetAnEori(implicit ec: ExecutionContext, request: Request[AnyContent]): Future[Result] = {
    val result = for {
      registrationDetails <- sessionCache.registrationDetails
      safeId = registrationDetails.safeId.id
      queryParameters = ("taxPayerID" -> safeId) :: buildQueryParams
      sub09Result <- SUB09Connector.subscriptionDisplay(queryParameters)
      sub01Outcome <- sessionCache.sub01Outcome
    } yield {
      sub09Result match {
        case Right(subscriptionDisplayResponse) =>
          val email = subscriptionDisplayResponse.responseDetail.contactInformation
            .flatMap(_.emailAddress)
            .getOrElse(throw new IllegalStateException("Register Journey: No email address available."))
          val eori = subscriptionDisplayResponse.responseDetail.EORINo
            .getOrElse(throw new IllegalStateException("no eori found in the response"))
          onSUB09Success(
            sub01Outcome.processedDate,
            email,
            safeId,
            Eori(eori),
            subscriptionDisplayResponse,
            Journey.Register
          )(Redirect(Sub02Controller.end()))
        case Left(_) =>
          Future.successful(ServiceUnavailable(errorTemplateView()))
      }
    }
    result.flatMap(identity)
  }

  private def subscribeForCDS(
    implicit ec: ExecutionContext,
    request: Request[AnyContent],
    hc: HeaderCarrier
  ): Future[Result] = {
    val result = for {
      subscriptionDetails <- sessionCache.subscriptionDetails
      eori = subscriptionDetails.eoriNumber.getOrElse(throw new IllegalStateException("no eori found in the cache"))
      registerWithEoriAndIdResponse <- sessionCache.registerWithEoriAndIdResponse
      safeId = registerWithEoriAndIdResponse.responseDetail
        .flatMap(_.responseData.map(_.SAFEID))
        .getOrElse(throw new IllegalStateException("no SAFEID found in the response"))
      queryParameters = ("EORI" -> eori) :: buildQueryParams
      sub09Result <- SUB09Connector.subscriptionDisplay(queryParameters)
      sub01Outcome <- sessionCache.sub01Outcome
      email <- sessionCache.email
    } yield {
      sub09Result match {
        case Right(subscriptionDisplayResponse) =>
          onSUB09Success(
            sub01Outcome.processedDate,
            email,
            safeId,
            Eori(eori),
            subscriptionDisplayResponse,
            Journey.Subscribe
          )(Redirect(Sub02Controller.migrationEnd()))
        case Left(_) =>
          Future.successful(ServiceUnavailable(errorTemplateView()))
      }
    }
    result.flatMap(identity)
  }

  private def subscribeForCDSROW(implicit ec: ExecutionContext, request: Request[AnyContent]): Future[Result] = {
    val result = for {
      subscriptionDetails <- sessionCache.subscriptionDetails
      registrationDetails <- sessionCache.registrationDetails
      eori = subscriptionDetails.eoriNumber.getOrElse(throw new IllegalStateException("no eori found in the cache"))
      safeId = registrationDetails.safeId.id
      queryParameters = ("EORI" -> eori) :: buildQueryParams
      sub09Result <- SUB09Connector.subscriptionDisplay(queryParameters)
      sub01Outcome <- sessionCache.sub01Outcome
      email <- sessionCache.email
    } yield {
      sub09Result match {
        case Right(subscriptionDisplayResponse) =>
          onSUB09Success(
            sub01Outcome.processedDate,
            email,
            safeId,
            Eori(eori),
            subscriptionDisplayResponse,
            Journey.Subscribe
          )(Redirect(Sub02Controller.migrationEnd()))
        case Left(_) =>
          Future.successful(ServiceUnavailable(errorTemplateView()))
      }
    }
    result.flatMap(identity)
  }

  private def buildQueryParams: List[(String, String)] =
    List("regime" -> "CDS", "acknowledgementReference" -> uuidGenerator.generateUUIDAsString)

  private def onSUB09Success(
    processedDate: String,
    email: String,
    safeId: String,
    eori: Eori,
    subscriptionDisplayResponse: SubscriptionDisplayResponse,
    journey: Journey.Value
  )(redirect: => Result)(implicit headerCarrier: HeaderCarrier): Future[Result] = {
    val formBundleId =
      subscriptionDisplayResponse.responseCommon.returnParameters
        .flatMap(_.find(_.paramName.equals("ETMPFORMBUNDLENUMBER")).map(_.paramValue))
        .getOrElse(throw new IllegalStateException("NO ETMPFORMBUNDLENUMBER specified"))

    //As the result of migration person of contact is likely to be empty use string Customer
    val recipientFullName =
      subscriptionDisplayResponse.responseDetail.contactInformation.flatMap(_.personOfContact).getOrElse("Customer")
    val name = subscriptionDisplayResponse.responseDetail.CDSFullName
    val emailVerificationTimestamp =
      subscriptionDisplayResponse.responseDetail.contactInformation.flatMap(_.emailVerificationTimestamp)

    sessionCache
      .saveSub02Outcome(Sub02Outcome(processedDate, name, subscriptionDisplayResponse.responseDetail.EORINo))
      .flatMap(
        _ =>
          handleSubscriptionService
            .handleSubscription(
              formBundleId,
              RecipientDetails(journey, email, recipientFullName, Some(name), Some(processedDate)),
              TaxPayerId(safeId),
              Some(eori),
              emailVerificationTimestamp,
              SafeId(safeId)
            )
            .flatMap(_ => {
              if (journey == Journey.Subscribe) {
                issuerCall(eori, formBundleId, subscriptionDisplayResponse)(redirect)
              } else {
                Future.successful(redirect)
              }
            })
      )
  }

  private def issuerCall(eori: Eori, formBundleId: String, subscriptionDisplayResponse: SubscriptionDisplayResponse)(
    redirect: => Result
  )(implicit headerCarrier: HeaderCarrier): Future[Result] = {
    val dateOfEstablishment = subscriptionDisplayResponse.responseDetail.dateOfEstablishment
    taxEnrolmentService.issuerCall(formBundleId, eori, dateOfEstablishment).map {
      case NO_CONTENT => redirect
      case _          => throw new IllegalArgumentException("Tax enrolment call failed")
    }

  }

}