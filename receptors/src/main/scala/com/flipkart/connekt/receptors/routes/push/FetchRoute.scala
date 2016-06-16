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
package com.flipkart.connekt.receptors.routes.push

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.ActorMaterializer
import com.fasterxml.jackson.databind.node.ObjectNode
import com.flipkart.connekt.commons.entities.Channel
import com.flipkart.connekt.commons.entities.MobilePlatform._
import com.flipkart.connekt.commons.factories.ServiceFactory
import com.flipkart.connekt.commons.iomodels._
import com.flipkart.connekt.commons.services.{ConnektConfig, StencilService}
import com.flipkart.connekt.commons.utils.StringUtils.{JSONMarshallFunctions, JSONUnMarshallFunctions}
import com.flipkart.connekt.receptors.directives.MPlatformSegment
import com.flipkart.connekt.receptors.routes.BaseJsonHandler
import com.flipkart.connekt.receptors.wire.ResponseUtils._

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.util.Try

class FetchRoute(implicit am: ActorMaterializer) extends BaseJsonHandler {

  val seenEventTypes = ConnektConfig.getList[String]("core.pn.seen.events").map(_.toLowerCase)

  val route =
    authenticate {
      user =>
        pathPrefix("v1") {
          path("fetch" / "push" / MPlatformSegment / Segment / Segment) {
            (platform: MobilePlatform, appName: String, instanceId: String) =>
              authorize(user, "FETCH", s"FETCH_$appName") {
                get {
                  meteredResource(s"fetch.$platform.$appName") {
                    parameters('startTs.as[Long], 'endTs ? System.currentTimeMillis, 'skipIds.*) { (startTs, endTs, skipIds) =>
                      require(startTs < endTs, "startTs must be prior to endTs")
                      require(startTs > (System.currentTimeMillis - 7.days.toMillis), "Invalid startTs : startTs can be max 7 days from now")

                      val requestEvents = ServiceFactory.getCallbackService.fetchCallbackEventByContactId(s"${appName.toLowerCase}$instanceId", Channel.PUSH, startTs + 1, endTs)
                      val messageService = ServiceFactory.getPNMessageService

                      //Skip all messages which are either read/dismissed or passed in skipIds
                      val skipMessageIds: Set[String] = skipIds.toSet ++ requestEvents.map(res => res.map(_._1.asInstanceOf[PNCallbackEvent]).filter(e => seenEventTypes.contains(e.eventType.toLowerCase)).map(_.messageId)).get.toSet
                      val messages: Try[List[ConnektRequest]] = requestEvents.map(res => {
                        val messageIds: List[String] = res.map(_._1.asInstanceOf[PNCallbackEvent]).map(_.messageId).distinct
                        val filteredMessageIds: List[String] = messageIds.filterNot(skipMessageIds.contains)
                        val fetchedMessages: Try[List[ConnektRequest]] = messageService.getRequestInfo(filteredMessageIds)
                        fetchedMessages.map(_.filter(_.expiryTs.map(_ >= System.currentTimeMillis).getOrElse(true))).getOrElse(List.empty[ConnektRequest])
                      })

                      val pushRequests = messages.get.map(r => {
                        val stencils = r.stencilId.flatMap(StencilService.get(_)).getOrElse(List.empty)
                        val cR = r.copy(channelData = Option(r.channelData) match {
                          case Some(cD) => cD
                          case None =>
                            (Channel.withName(r.channel) match {
                              case Channel.PUSH =>
                                (stencils.map(s => s.component -> StencilService.render(s, r.channelDataModel).asInstanceOf[String].getObj[ObjectNode]) ++ Map("type" -> "PN")).toMap
                              case _ =>
                                (stencils.map(s => s.component -> StencilService.render(s, r.channelDataModel)) ++ Map("type" -> r.channel)).toMap
                            }).getJson.getObj[ChannelRequestData]
                        })
                        r.id -> cR
                      }).toMap

                      val finalTs = requestEvents.getOrElse(List.empty[(CallbackEvent, Long)]).map(_._2).reduceLeftOption(_ max _).getOrElse(endTs)

                      complete(GenericResponse(StatusCodes.OK.intValue, null, Response(s"Fetched result for $instanceId", pushRequests))
                        .respondWithHeaders(Seq(RawHeader("endTs", finalTs.toString), RawHeader("Access-Control-Expose-Headers", "endTs"))))
                    }
                  }
                }
              }
          }
        }
    }
}
