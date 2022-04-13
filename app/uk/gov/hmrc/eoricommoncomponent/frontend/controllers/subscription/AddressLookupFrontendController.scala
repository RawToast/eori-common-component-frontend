/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.eoricommoncomponent.frontend.controllers.subscription

import javax.inject.{Inject, Singleton}
import play.api.data.Form
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents, Request}
import play.twirl.api.HtmlFormat
import uk.gov.hmrc.eoricommoncomponent.frontend.connector.AddressLookupFrontendConnector
import uk.gov.hmrc.eoricommoncomponent.frontend.controllers.CdsController
import uk.gov.hmrc.eoricommoncomponent.frontend.controllers.auth.AuthAction
import uk.gov.hmrc.eoricommoncomponent.frontend.domain.LoggedInUserWithEnrolments
import uk.gov.hmrc.eoricommoncomponent.frontend.domain.subscription.{AddressDetailsSubscriptionFlowPage, ContactDetailsSubscriptionFlowPageMigrate}
import uk.gov.hmrc.eoricommoncomponent.frontend.forms.models.subscription.{AddressLookupParams, AddressViewModel}
import uk.gov.hmrc.eoricommoncomponent.frontend.models.Service
import uk.gov.hmrc.eoricommoncomponent.frontend.services.cache.{RequestSessionData, SessionCache}
import uk.gov.hmrc.eoricommoncomponent.frontend.views.html.subscription.address_lookup_postcode
import uk.gov.hmrc.eoricommoncomponent.frontend.services.cache.DataUnavailableException
import uk.gov.hmrc.eoricommoncomponent.frontend.services.subscription.SubscriptionDetailsService

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AddressLookupFrontendController @Inject()(
  authAction: AuthAction,
  sessionCache: SessionCache,
  requestSessionData: RequestSessionData,
  mcc: MessagesControllerComponents,
  addressLookupPostcodePage: address_lookup_postcode,
  addressLookupFrontendConnector: AddressLookupFrontendConnector,
  subscriptionFlowManager: SubscriptionFlowManager,
  subscriptionDetailsService: SubscriptionDetailsService
)(implicit ec: ExecutionContext)
    extends CdsController(mcc) {
  
  def onPageLoad(service: Service): Action[AnyContent] = authAction.ggAuthorisedUserWithEnrolmentsAction { implicit request => _: LoggedInUserWithEnrolments =>
    addressLookupFrontendConnector
      .initJourney(routes.AddressLookupFrontendController.returnFromAddressLookup(service))
      .map(Redirect(_))
  }

  def returnFromAddressLookup(service: Service, id: String):  Action[AnyContent] = authAction.ggAuthorisedUserWithEnrolmentsAction
  { implicit request =>
    _: LoggedInUserWithEnrolments => {
      for {
        address <- addressLookupFrontendConnector.getAddress(id)
        _       <- subscriptionDetailsService.cacheAddressDetails(AddressViewModel(
          street = address.lines.take(3).mkString(", "),
          city = address.lines.last,
          postcode =  address.postcode,
          countryCode = address.country.code
        ))
      }  yield {
        Redirect(subscriptionFlowManager.stepInformation(AddressDetailsSubscriptionFlowPage).nextPage.url(service))
      }
    }
  }

//  def displayPage(service: Service): Action[AnyContent] =
//    authAction.ggAuthorisedUserWithEnrolmentsAction { implicit request => _: LoggedInUserWithEnrolments =>
//      sessionCache.addressLookupParams.map {
//        case Some(addressLookupParams) =>
//          Ok(prepareView(AddressLookupParams.form().fill(addressLookupParams), false, service))
//        case _ => Ok(prepareView(AddressLookupParams.form(), false, service))
//      }
//    }

  def reviewPage(service: Service): Action[AnyContent] =
    authAction.ggAuthorisedUserWithEnrolmentsAction { implicit request => _: LoggedInUserWithEnrolments =>
      sessionCache.addressLookupParams.map {
        case Some(addressLookupParams) =>
          Ok(prepareReviewView(AddressLookupParams.form().fill(addressLookupParams), service))
        case _ => Ok(prepareReviewView(AddressLookupParams.form(), service))
      }
    }

  private def prepareReviewView(form: Form[AddressLookupParams], service: Service)(implicit
    request: Request[AnyContent]
  ): HtmlFormat.Appendable = {
    val selectedOrganisationType = requestSessionData.userSelectedOrganisationType.getOrElse(
      throw DataUnavailableException("Organisation type is not cached")
    )

    addressLookupPostcodePage(form, true, selectedOrganisationType, service)
  }

  def submitReview(service: Service): Action[AnyContent] =
    authAction.ggAuthorisedUserWithEnrolmentsAction { implicit request => _: LoggedInUserWithEnrolments =>
      AddressLookupParams.form().bindFromRequest().fold(
        formWithError => Future.successful(BadRequest(prepareReviewView(formWithError, service))),
        validAddressParams =>
          sessionCache.saveAddressLookupParams(validAddressParams).map { _ =>
            Redirect(routes.AddressLookupResultsController.reviewPage(service))
          }
      )
    }
}
