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

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.flipkart.connekt.commons.entities.Channel
import com.flipkart.connekt.commons.services.TStencilService
import com.flipkart.connekt.commons.utils.StringUtils._

case class ConnektRequest(@JsonProperty(required = false) id: String,
                          @JsonProperty(required = false) clientId: String,
                          contextId: Option[String],
                          channel: String,
                          @JsonProperty(required = true) sla: String,
                          stencilId: Option[String],
                          scheduleTs: Option[Long],
                          expiryTs: Option[Long],
                          @JsonProperty(required = true) channelInfo: ChannelRequestInfo,
                          @JsonProperty(required = false) channelData: ChannelRequestData,
                          @JsonProperty(required = false) channelDataModel: ObjectNode = getObjectNode,
                          meta: Map[String, String]) {

  def this(id: String, clientId: String, contextId: Option[String], channel: String, sla: String, stencilId: Option[String],
           scheduleTs: Option[Long], expiryTs: Option[Long], channelInfo: ChannelRequestInfo,
           channelData: ChannelRequestData, channelDataModel: ObjectNode) {
    this(id, clientId, contextId, channel, sla, stencilId, scheduleTs, expiryTs, channelInfo, channelData, channelDataModel, Map.empty[String, String])
  }

  def validate(implicit stencilService: TStencilService) = {
    require(stencilId.map(stencilService.get(_).nonEmpty).getOrElse(Option(channelData).isDefined), "given stencil Id doesn't exist")
    require(contextId.forall(_.hasOnlyAllowedChars), "`contextId` field can only contain [A-Za-z0-9_.-:|] allowed chars.")
    require(sla.isDefined, "`sla` field can cannot be null or empty.")
    require(meta != null, "`meta` field cannot be null. It is optional but non-null")
    require(channelInfo != null, "`channelInfo` field cannot be null.")
    require(contextId.forall(_.length <= 20), "`contextId` can be max 20 characters")
    Option(channelData).foreach(_.validate(channelInfo.appName.toLowerCase))
  }

  @JsonIgnore
  def isTestRequest : Boolean = meta.get("x-perf-test").exists(v => v.trim.equalsIgnoreCase("true"))

  def getComputedChannelData(implicit stencilService: TStencilService): ChannelRequestData =
    stencilId.map(stencilService.get(_)).map { stencil =>
      Channel.withName(channel) match {
        case Channel.PUSH =>
          val pushType = if (channelData != null) channelData.asInstanceOf[PNRequestData].pushType else null
          PNRequestData(pushType = pushType, data = stencilService.materialize(stencil.head, channelDataModel).asInstanceOf[String].getObj[ObjectNode])
        case Channel.SMS =>
          SmsRequestData(body = stencilService.materialize(stencil.head, channelDataModel).asInstanceOf[String])
        case Channel.EMAIL =>
          val subject = stencilService.materialize(stencil.filter(_.component.equalsIgnoreCase("subject")).head, channelDataModel).asInstanceOf[String]
          val html = stencilService.materialize(stencil.filter(_.component.equalsIgnoreCase("html")).head, channelDataModel).asInstanceOf[String]
          val txt = stencilService.materialize(stencil.filter(_.component.equalsIgnoreCase("text")).head, channelDataModel).asInstanceOf[String]
          EmailRequestData(subject = subject, html = html, text = txt, attachments = Option(channelData).map(_.asInstanceOf[EmailRequestData].attachments).orNull)
        case unsupportedChannel =>
          throw new Exception(s"`channelData` compute undefined for $unsupportedChannel")
      }
    }.getOrElse(channelData)
}
