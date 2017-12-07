/*
 *         -╥⌐⌐⌐⌐            -⌐⌐⌐⌐-
 *      ≡╢░░░░⌐\░░░φ     ╓╝░░░░⌐░░░░╪╕
 *     ╣╬░░`    `░░░╢┘ φ▒╣╬╝╜     ░░╢╣Q
 *    ║╣╬░⌐        ` ╤▒▒▒Å`        ║╢╬╣
 *    ╚╣╬░⌐        ╔▒▒▒▒`«╕        ╢╢╣▒
 *     ╫╬░░╖    .░ ╙╨╨  ╣╣╬░φ    ╓φ░╢╢Å
 *      ╙╢░░░░⌐"░░░╜     ╙Å░░░░⌐░░░░╝`
 *        ``˚¬ ⌐              ˚˚⌐´
 *
 *      Copyright © 2016 Flipkart.com
 */
package com.flipkart.connekt.firefly.flows.dispatchers

import java.util.UUID

import akka.http.scaladsl.model._
import com.flipkart.connekt.busybees.models.WAContactTracker
import com.flipkart.connekt.commons.factories.{ConnektLogger, LogFile}
import com.flipkart.connekt.commons.iomodels.{Constants, ContactPayload, Payload, WAContactRequest}
import com.flipkart.connekt.commons.services.ConnektConfig
import com.flipkart.connekt.commons.utils.StringUtils._
import com.flipkart.connekt.firefly.flows.MapFlowStage

class WAContactHttpDispatcherPrepare extends MapFlowStage[Seq[ContactPayload], (HttpRequest, WAContactTracker)] {

  private val baseUrl = ConnektConfig.getString("wa.base.uri").get
  override implicit val map: Seq[ContactPayload] => List[(HttpRequest, WAContactTracker)] = contacts => {
    try {
      val uuid = generateUUID
      ConnektLogger(LogFile.PROCESSORS).debug(s"WAHttpDispatcherPrepare received with messageId : $uuid")
      ConnektLogger(LogFile.PROCESSORS).trace(s"WAHttpDispatcherPrepare received with messageId : $uuid and contacts : $contacts")
      val contactList = contacts.map(_.user_identifier).toSet
      val waPayload = WAContactRequest(Payload(users = contactList))
      val requestEntity = HttpEntity(ContentTypes.`application/json`, waPayload.getJson)
      val requestHeaders = scala.collection.immutable.Seq.empty[HttpHeader]
      val httpRequest = HttpRequest(HttpMethods.POST, sendUri, requestHeaders, requestEntity)
      val requestTrace = WAContactTracker(contactList, contacts.head.appName, contacts, uuid)
      List(httpRequest -> requestTrace)
    } catch {
      case e: Throwable =>
        ConnektLogger(LogFile.PROCESSORS).error(s"WAHttpDispatcherPrepare failed with ${e.getMessage}", e)
        List.empty
    }
  }

  private val sendUri = Uri(s"$baseUrl${Constants.WAConstants.WHATSAPP_CHECK_CONTACT_URI}")

  private def generateUUID: String = UUID.randomUUID().toString

}
