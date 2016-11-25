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
package com.flipkart.connekt.commons.iomodels

import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.commons.lang.StringUtils

case class SmsPayload(@JsonProperty(required = false) receivers: Set[String], messageBody: SmsRequestData, isUnicodeMessage: Boolean, senderMask: String, ttl: String, isIntl: String, smsPart: String)

case class SmsPayloadEnvelope(messageId: String, clientId: String, stencilId: String, appName: String, contextId: String = StringUtils.EMPTY, payload: SmsPayload, meta: Map[String, Any], headers: Map[String, String] = Map.empty[String, String], provider: Seq[String] = Seq.empty) extends ProviderEnvelope {
  override def destinations: Set[String] = payload.receivers
}
