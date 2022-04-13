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

package uk.gov.hmrc.eoricommoncomponent.frontend.connector

import javax.inject.{Inject, Named, Singleton}
import play.api.http.HeaderNames.LOCATION
import play.api.i18n.Messages
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{Call, Request}
import uk.gov.hmrc.eoricommoncomponent.frontend.config.AppConfig
import uk.gov.hmrc.eoricommoncomponent.frontend.views.ServiceName.longName
// import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse}
import uk.gov.hmrc.eoricommoncomponent.frontend.config.AddressLookupConfig
import uk.gov.hmrc.eoricommoncomponent.frontend.models.address.Address

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AddressLookupFrontendConnector @Inject()(
  http: HttpClient,
  appConfig: AppConfig,
  addressLookupConfig: AddressLookupConfig) {

  val baseUrl = appConfig.addressLookupFrontendBaseUrl
  val callback = appConfig.addressLookupCallback

  private lazy val initJourneyUrl = s"$baseUrl/api/v2/init"
  private def confirmJourneyUrl(id: String) = s"$baseUrl/api/confirmed?id=$id"

  def initJourney(call: Call)(implicit hc: HeaderCarrier, ec: ExecutionContext, messages: Messages, request: Request[_]): Future[String] = {
    val addressConfig = Json.toJson(addressLookupConfig.config(s"$callback${call.url}", longName))

    http.POST[JsValue, HttpResponse](initJourneyUrl, addressConfig) map { response =>
      println(s"*********************************** ${response.headers} **********************")
      response.header(LOCATION).getOrElse {
        throw new RuntimeException("Response from AddressLookupFrontend did not contain LOCATION header.")
      }
    }
  }

  def getAddress(id: String)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Address] =
    http.GET[JsObject](confirmJourneyUrl(id)) map (json => (json \ "address").as[Address])

}
