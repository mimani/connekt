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
package com.flipkart.connekt.busybees.streams.flows.reponsehandlers

import akka.stream._
import com.flipkart.connekt.busybees.models.APNSRequestTracker
import com.flipkart.connekt.busybees.models.MessageStatus.{APNSResponseStatus, InternalStatus}
import com.flipkart.connekt.commons.entities.MobilePlatform
import com.flipkart.connekt.commons.factories.{ConnektLogger, LogFile}
import com.flipkart.connekt.commons.helpers.CallbackRecorder._
import com.flipkart.connekt.commons.iomodels._
import com.flipkart.connekt.commons.services.DeviceDetailsService
import com.flipkart.connekt.commons.utils.StringUtils._
import com.relayrides.pushy.apns.util.SimpleApnsPushNotification
import com.relayrides.pushy.apns.{ApnsPushNotification, PushNotificationResponse}

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

class APNSResponseHandler(implicit m: Materializer, ec: ExecutionContext) extends PNProviderResponseHandler[(Try[PushNotificationResponse[SimpleApnsPushNotification]], APNSRequestTracker)] {

  override val map: ((Try[PushNotificationResponse[SimpleApnsPushNotification]], APNSRequestTracker)) => List[PNCallbackEvent] = responseTrackerPair => {

    val tryResponse = responseTrackerPair._1
    val requestTracker = responseTrackerPair._2

    val events = ListBuffer[PNCallbackEvent]()

    tryResponse match {
      case Success(pushNotificationResponse) =>

        ConnektLogger(LogFile.PROCESSORS).info(s"APNSResponseHandler received http2 response for: ${requestTracker.messageId}")

        pushNotificationResponse.isAccepted match {
          case true =>
            ConnektLogger(LogFile.PROCESSORS).trace(s"APNSResponseHandler notification accepted by the apns gateway for: ${requestTracker.messageId}")
            events += PNCallbackEvent(requestTracker.messageId, requestTracker.deviceId, APNSResponseStatus.Received, MobilePlatform.IOS.toString, requestTracker.appName, requestTracker.contextId)

          case false =>
            if (pushNotificationResponse.getTokenInvalidationTimestamp != null) {

              ConnektLogger(LogFile.PROCESSORS).info(s"APNSDispatcher token invalid [D:${requestTracker.deviceId} / T:${pushNotificationResponse.getPushNotification.getToken}] since ${pushNotificationResponse.getTokenInvalidationTimestamp} for: ${requestTracker.messageId}")
              //This device is now invalid remove device registration.
              DeviceDetailsService.get(requestTracker.appName, requestTracker.deviceId).foreach {
                _.foreach(device => if (device.osName == MobilePlatform.IOS.toString) {
                  ConnektLogger(LogFile.PROCESSORS).info(s"APNSDispatcher token invalid  deleting details of device: ${requestTracker.deviceId}.")
                  DeviceDetailsService.delete(requestTracker.appName, device.deviceId)
                })
              }

              events += PNCallbackEvent(requestTracker.messageId, requestTracker.deviceId, APNSResponseStatus.TokenExpired, MobilePlatform.IOS.toString, requestTracker.appName, requestTracker.contextId)
            } else {
              ConnektLogger(LogFile.PROCESSORS).warn(s"APNSResponseHandler notification rejected by the apns gateway: ${pushNotificationResponse.getRejectionReason} for: ${requestTracker.messageId}")
              events += PNCallbackEvent(requestTracker.messageId, requestTracker.deviceId, APNSResponseStatus.Rejected, MobilePlatform.IOS.toString, requestTracker.appName, requestTracker.contextId)
            }
        }

      case Failure(e) =>
        ConnektLogger(LogFile.PROCESSORS).error(s"APNSResponseHandler failed to send push notification for: ${requestTracker.messageId} due to: ${e.getClass.getSimpleName}, ${e.getMessage}", e)
        events += PNCallbackEvent(requestTracker.messageId, requestTracker.deviceId, InternalStatus.ProviderSendError, MobilePlatform.IOS.toString, requestTracker.appName, requestTracker.contextId, s"APNSResponseHandler-${e.getClass.getSimpleName}-${e.getMessage}")
    }
    events.persist
    events.toList
  }
}