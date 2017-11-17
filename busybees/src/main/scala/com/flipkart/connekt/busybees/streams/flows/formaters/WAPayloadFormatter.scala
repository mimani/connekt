package com.flipkart.connekt.busybees.streams.flows.formaters

import com.flipkart.connekt.commons.entities.{Channel, Stencil}
import com.flipkart.connekt.commons.factories.{ConnektLogger, LogFile, ServiceFactory}
import com.flipkart.connekt.commons.iomodels._
import com.flipkart.connekt.commons.helpers.ConnektRequestHelper._
import com.flipkart.connekt.commons.iomodels.MessageStatus.WAResponseStatus
import com.flipkart.connekt.commons.helpers.CallbackRecorder._
import com.flipkart.connekt.commons.utils.StringUtils.{JSONUnMarshallFunctions, _}


object WAPayloadFormatter{
  lazy implicit val stencilService = ServiceFactory.getStencilService
  def makePayload(connektRequest: ConnektRequest): List[WAPayload] = {
    val waRequestData = connektRequest.channelData.asInstanceOf[WARequestData]
    waRequestData.waType match {
      case WAType.hsm =>
        stencilService.getStencilsByName(connektRequest.stencilId.get).headOption match {
          case Some(stencil: Stencil) =>
            List(stencilService.materialize(stencil, Map("connektRequest" -> connektRequest).getJsonNode).asInstanceOf[WAPayload])
          case _ =>
            ServiceFactory.getReportingService.recordChannelStatsDelta(connektRequest.clientId, connektRequest.contextId, connektRequest.stencilId, Channel.WA, connektRequest.appName, WAResponseStatus.StencilNotFound.toString)
            val event = WACallbackEvent(connektRequest.id, None, connektRequest.destinations.head, WAResponseStatus.StencilNotFound.toString, connektRequest.clientId, connektRequest.appName, connektRequest.contextId.get, WAResponseStatus.StencilNotFound.toString, System.currentTimeMillis())
            event.enqueue
            List()
        }
      case WAType.document =>
        List(PDFWaPayload(
          DocumentData(waRequestData.attachment.get.name, waRequestData.attachment.get.caption.getOrElse("")),
          connektRequest.channelInfo.asInstanceOf[WARequestInfo].destinations.head
        ))
      case _ =>
        ConnektLogger(LogFile.PROCESSORS).error(s"WAMediaResponseHandler unknown WA ConnektRequest Data type: ${waRequestData.waType}")
        List()
    }
  }
}
