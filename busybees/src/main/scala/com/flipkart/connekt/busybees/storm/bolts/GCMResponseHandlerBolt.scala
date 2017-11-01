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
package com.flipkart.connekt.busybees.storm.bolts

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.flipkart.connekt.busybees.storm.models.HttpResponseAndTracker
import com.flipkart.connekt.commons.entities.MobilePlatform
import com.flipkart.connekt.commons.factories.{ConnektLogger, LogFile, ServiceFactory}
import com.flipkart.connekt.commons.helpers.CallbackRecorder._
import com.flipkart.connekt.commons.iomodels.MessageStatus.{GCMResponseStatus, InternalStatus}
import com.flipkart.connekt.commons.iomodels._
import com.flipkart.connekt.commons.services.DeviceDetailsService
import com.flipkart.connekt.commons.utils.StringUtils._
import org.apache.storm.topology.base.BaseBasicBolt
import org.apache.storm.topology.{BasicOutputCollector, OutputFieldsDeclarer}
import org.apache.storm.tuple.{Fields, Tuple, TupleImpl}

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer

class GCMResponseHandlerBolt extends BaseBasicBolt {

  override def execute(input: Tuple, collector: BasicOutputCollector): Unit = {
    val responseTrackerPair = input.asInstanceOf[TupleImpl].get("httpDispatchedRequest").asInstanceOf[HttpResponseAndTracker]

    val response = responseTrackerPair.httpResponse
    val requestTracker = responseTrackerPair.requestTracker

    val messageId = requestTracker.messageId
    val appName = requestTracker.appName
    val deviceIds = requestTracker.deviceId

    val events = ListBuffer[PNCallbackEvent]()
    val eventTS = System.currentTimeMillis()

    try {
      val stringResponse = response.entity.getStringWithoutMat
      ConnektLogger(LogFile.PROCESSORS).debug(s"GCMResponseHandler received http response for: $messageId")
      ConnektLogger(LogFile.PROCESSORS).trace(s"GCMResponseHandler received http response for: $messageId http response body: $stringResponse")
      response.status.intValue() match {
        case 200 =>
          val responseBody = stringResponse.getObj[ObjectNode]
          val deviceIdItr = deviceIds.iterator

          responseBody.findValue("results").foreach(rBlock => {
            val rDeviceId = deviceIdItr.next()
            rBlock match {
              case s if s.has("message_id") =>
                if (s.has("registration_id"))
                  DeviceDetailsService.get(appName, rDeviceId).foreach(_.foreach(d => {
                    ConnektLogger(LogFile.PROCESSORS).info(s"GCMResponseHandler device token update notified on. $messageId of device: $rDeviceId")
                    DeviceDetailsService.update(d.deviceId, d.copy(token = s.get("registration_id").asText.trim))
                  }))
                ServiceFactory.getReportingService.recordPushStatsDelta(requestTracker.clientId, Option(requestTracker.contextId), requestTracker.meta.get("stencilId").map(_.toString), Option(MobilePlatform.ANDROID.toString), requestTracker.appName, GCMResponseStatus.Received_HTTP)
                events += PNCallbackEvent(messageId, requestTracker.clientId, rDeviceId, GCMResponseStatus.Received_HTTP, MobilePlatform.ANDROID, appName, requestTracker.contextId, s.get("message_id").asText(), eventTS)
              case f if f.has("error") && List("InvalidRegistration", "NotRegistered").contains(f.get("error").asText.trim) =>
                DeviceDetailsService.get(appName, rDeviceId).foreach {
                  _.foreach(device => if (device.osName == MobilePlatform.ANDROID.toString) {
                    ConnektLogger(LogFile.PROCESSORS).info(s"GCMResponseHandler device token invalid / not_found, deleting details of device: $rDeviceId.")
                    DeviceDetailsService.delete(appName, device.deviceId)
                  })
                }
                ServiceFactory.getReportingService.recordPushStatsDelta(requestTracker.clientId, Option(requestTracker.contextId), requestTracker.meta.get("stencilId").map(_.toString), Option(MobilePlatform.ANDROID.toString), requestTracker.appName, GCMResponseStatus.InvalidDevice)
                events += PNCallbackEvent(messageId, requestTracker.clientId, rDeviceId, GCMResponseStatus.InvalidDevice, MobilePlatform.ANDROID, appName, requestTracker.contextId, f.get("error").asText, eventTS)

              case ie if ie.has("error") && List("InternalServerError").contains(ie.get("error").asText.trim) =>
                //TODO: Support retry.
                ServiceFactory.getReportingService.recordPushStatsDelta(requestTracker.clientId, Option(requestTracker.contextId), requestTracker.meta.get("stencilId").map(_.toString), Option(MobilePlatform.ANDROID.toString), requestTracker.appName, GCMResponseStatus.InternalError)
                events += PNCallbackEvent(messageId, requestTracker.clientId, rDeviceId, GCMResponseStatus.InternalError, MobilePlatform.ANDROID, appName, requestTracker.contextId, ie.toString, eventTS)
              case e: JsonNode =>
                ConnektLogger(LogFile.PROCESSORS).error(s"GCMResponseHandler unknown for message: $messageId, device: $rDeviceId", e)
                ServiceFactory.getReportingService.recordPushStatsDelta(requestTracker.clientId, Option(requestTracker.contextId), requestTracker.meta.get("stencilId").map(_.toString), Option(MobilePlatform.ANDROID.toString), requestTracker.appName, GCMResponseStatus.Error)
                events += PNCallbackEvent(messageId, requestTracker.clientId, rDeviceId, GCMResponseStatus.Error, MobilePlatform.ANDROID, appName, requestTracker.contextId, e.toString, eventTS)
            }
          })

        case 400 =>
          ServiceFactory.getReportingService.recordPushStatsDelta(requestTracker.clientId, Option(requestTracker.contextId), requestTracker.meta.get("stencilId").map(_.toString), Option(MobilePlatform.ANDROID.toString), requestTracker.appName, GCMResponseStatus.InvalidJsonError, deviceIds.size)
          events.addAll(deviceIds.map(PNCallbackEvent(messageId, requestTracker.clientId, _, GCMResponseStatus.InvalidJsonError, MobilePlatform.ANDROID, appName, requestTracker.contextId, stringResponse, eventTS)))
          ConnektLogger(LogFile.PROCESSORS).error(s"GCMResponseHandler http response - invalid json sent for: $messageId response: $stringResponse")
        case 401 =>
          ServiceFactory.getReportingService.recordPushStatsDelta(requestTracker.clientId, Option(requestTracker.contextId), requestTracker.meta.get("stencilId").map(_.toString), Option(MobilePlatform.ANDROID.toString), requestTracker.appName, GCMResponseStatus.AuthError, deviceIds.size)
          events.addAll(deviceIds.map(PNCallbackEvent(messageId, requestTracker.clientId, _, GCMResponseStatus.AuthError, MobilePlatform.ANDROID, appName, requestTracker.contextId, "", eventTS)))
          ConnektLogger(LogFile.PROCESSORS).error(s"GCMResponseHandler http response - the sender account used to send a message couldn't be authenticated for: $messageId response: $stringResponse")
        case w if 5 == (w / 100) =>
          ServiceFactory.getReportingService.recordPushStatsDelta(requestTracker.clientId, Option(requestTracker.contextId), requestTracker.meta.get("stencilId").map(_.toString), Option(MobilePlatform.ANDROID.toString), requestTracker.appName, GCMResponseStatus.InternalError, deviceIds.size)
          events.addAll(deviceIds.map(PNCallbackEvent(messageId, requestTracker.clientId, _, GCMResponseStatus.InternalError, MobilePlatform.ANDROID, appName, requestTracker.contextId, "", eventTS)))
          ConnektLogger(LogFile.PROCESSORS).error(s"GCMResponseHandler http response - the gcm server encountered an error while trying to process the request for: $messageId code: $w response: $stringResponse")
        case w =>
          ServiceFactory.getReportingService.recordPushStatsDelta(requestTracker.clientId, Option(requestTracker.contextId), requestTracker.meta.get("stencilId").map(_.toString), Option(MobilePlatform.ANDROID.toString), requestTracker.appName, GCMResponseStatus.Error, deviceIds.size)
          events.addAll(deviceIds.map(PNCallbackEvent(messageId, requestTracker.clientId, _, GCMResponseStatus.Error, MobilePlatform.ANDROID, appName, requestTracker.contextId, stringResponse, eventTS)))
          ConnektLogger(LogFile.PROCESSORS).error(s"GCMResponseHandler http response - gcm response unhandled for: $messageId code: $w response: $stringResponse")
      }
    } catch {
      case e: Exception =>
        ServiceFactory.getReportingService.recordPushStatsDelta(requestTracker.clientId, Option(requestTracker.contextId), requestTracker.meta.get("stencilId").map(_.toString), Option(MobilePlatform.ANDROID.toString), requestTracker.appName, InternalStatus.GcmResponseParseError, deviceIds.size)
        events.addAll(deviceIds.map(PNCallbackEvent(messageId, requestTracker.clientId, _, InternalStatus.GcmResponseParseError, MobilePlatform.ANDROID, appName, requestTracker.contextId, e.getMessage, eventTS)))
        ConnektLogger(LogFile.PROCESSORS).error(s"GCMResponseHandler failed processing http response body for: $messageId", e)
    }

    events.enqueue
    events.toList

  }

  override def declareOutputFields(declarer: OutputFieldsDeclarer): Unit = {
    declarer.declare(new Fields("gcmResponseHandlerRequest"))
  }

}