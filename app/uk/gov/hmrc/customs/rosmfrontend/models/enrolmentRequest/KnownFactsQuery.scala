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

package uk.gov.hmrc.customs.rosmfrontend.models.enrolmentRequest

import play.api.libs.json.{Json, OFormat}

case class KnownFactsQuery(
  service: String,
  knownFacts: List[KeyValuePair]
)

object KnownFactsQuery {
  implicit val format: OFormat[KnownFactsQuery] = Json.format[KnownFactsQuery]

  def apply(eoriNumber: String): KnownFactsQuery =
    new KnownFactsQuery(
      service = "HMRC-CUS-ORG",
      knownFacts = List(
        KeyValuePair(
          key = "EORINumber",
          value = eoriNumber
        )
      )
    )
}