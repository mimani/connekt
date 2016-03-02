package com.flipkart.connekt.busybees.streams.flows.dispatchers

import java.util.concurrent.{ExecutionException, TimeUnit}

import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import com.flipkart.connekt.commons.entities.MobilePlatform
import com.flipkart.connekt.commons.factories.{ConnektLogger, LogFile}
import com.flipkart.connekt.commons.iomodels.{APSPayloadEnvelope, PNCallbackEvent, iOSPNPayload}
import com.flipkart.connekt.commons.services.{DeviceDetailsService, KeyChainManager}
import com.flipkart.connekt.commons.utils.StringUtils._
import com.relayrides.pushy.apns.util.SimpleApnsPushNotification
import com.relayrides.pushy.apns.{ApnsClient, ClientNotConnectedException}

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer

/**
 * Created by kinshuk.bairagi on 05/02/16.
 */
class APNSDispatcher(appNames: List[String] = List.empty) extends GraphStage[FlowShape[APSPayloadEnvelope, PNCallbackEvent]] {

  type AppName = String
  val in = Inlet[APSPayloadEnvelope]("APNSDispatcher.In")
  val out = Outlet[PNCallbackEvent]("APNSDispatcher.Out")

  override def shape = FlowShape.of(in, out)

  var clients = scala.collection.mutable.Map[AppName, ApnsClient[SimpleApnsPushNotification]]()

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    setHandler(in, new InHandler {
      override def onPush(): Unit = try {

        val envelope = grab(in)
        val message = envelope.apsPayload.asInstanceOf[iOSPNPayload]
        ConnektLogger(LogFile.PROCESSORS).info(s"APNSDispatcher:: onPush:: Received Message: $envelope")

        ConnektLogger(LogFile.PROCESSORS).info(s"APNSDispatcher:: onPush:: Send Payload: " + message.data.asInstanceOf[AnyRef].getJson)
        val pushNotification = new SimpleApnsPushNotification(message.token, null, message.data.asInstanceOf[AnyRef].getJson)
        val client = clients.getOrElseUpdate(envelope.appName, getAPNSClient(envelope.appName))
        val events = ListBuffer[PNCallbackEvent]()

        try {

          val pushNotificationResponse = client.sendNotification(pushNotification).get()

          pushNotificationResponse.isAccepted match {
            case true =>
              events.addAll(envelope.deviceId.map(PNCallbackEvent(envelope.messageId, _, MobilePlatform.IOS.toString, "APNS_ACCEPTED", envelope.appName, "", "", System.currentTimeMillis())))
            case false =>
              ConnektLogger(LogFile.PROCESSORS).error("APNSDispatcher:: onPush :: Notification rejected by the APNs gateway: " + pushNotificationResponse.getRejectionReason)

              if (pushNotificationResponse.getTokenInvalidationTimestamp != null) {
                 //This device is now invalid remove device registration.
                DeviceDetailsService.get(envelope.appName, envelope.deviceId).map{
                  _.filter(_.osName == MobilePlatform.IOS.toString).foreach(d => DeviceDetailsService.delete(envelope.appName, d.deviceId))
                }.get
                events.addAll(envelope.deviceId.map(PNCallbackEvent(envelope.messageId, _, MobilePlatform.IOS.toString, "APNS_REJECTED_TOKEN_EXPIRED", envelope.appName, "", "", System.currentTimeMillis())))
                ConnektLogger(LogFile.PROCESSORS).error(s"APNSDispatcher:: Token Invalid [${message.token}] since " + pushNotificationResponse.getTokenInvalidationTimestamp)
              } else {
                events.addAll(envelope.deviceId.map(PNCallbackEvent(envelope.messageId, _, MobilePlatform.IOS.toString, "APNS_REJECTED", envelope.appName, "", "", System.currentTimeMillis())))
              }
          }
        } catch {
          case e: ExecutionException =>
            ConnektLogger(LogFile.PROCESSORS).error(s"APNSDispatcher:: onPush :: Failed to send push notification: ${envelope.messageId}, ${e.getMessage}", e)
            events.addAll(envelope.deviceId.map(PNCallbackEvent(envelope.messageId, _, MobilePlatform.IOS.toString, "APNS_SEND_FAILURE", envelope.appName, "", "", System.currentTimeMillis())))

            if (e.getCause.isInstanceOf[ClientNotConnectedException]) {
              ConnektLogger(LogFile.PROCESSORS).debug("APNSDispatcher:: onPush :: Waiting for APNSClient to reconnect.")
              client.getReconnectionFuture.await()
              ConnektLogger(LogFile.PROCESSORS).debug("APNSDispatcher:: onPush :: APNSClient Reconnected.")
            }

          case e:Throwable =>
            ConnektLogger(LogFile.PROCESSORS).error(s"APNSDispatcher:: onPush :: Failed to send push notification : ${envelope.messageId}, ${e.getMessage}", e)
            events.addAll(envelope.deviceId.map(PNCallbackEvent(envelope.messageId, _, MobilePlatform.IOS.toString, "APNS_UNKNOWN_FAILURE", envelope.appName, "", "", System.currentTimeMillis())))
        }

        if(isAvailable(out))
          push(out, events.head)

      } catch {
        case e: Throwable =>
          ConnektLogger(LogFile.PROCESSORS).error(s"APNSDispatcher:: onPush :: ${e.getMessage}", e)
          if(!hasBeenPulled(in))
            pull(in)
      }


    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        ConnektLogger(LogFile.PROCESSORS).info(s"APNSDispatcher:: onPull")
        if(!hasBeenPulled(in))
          pull(in)
      }
    })

    override def preStart(): Unit = {
      ConnektLogger(LogFile.PROCESSORS).info(s"APNSDispatcher:: preStart")
      /* Create APNSClients for all apps */
      clients ++= appNames.map(app => app -> getAPNSClient(app))
      super.preStart()
    }

    override def postStop(): Unit = {
      ConnektLogger(LogFile.PROCESSORS).info(s"APNSDispatcher:: postStop")

      // Disconnect all the APNSClients prior to stop
      clients.foreach(kv => {
        ConnektLogger(LogFile.PROCESSORS).info(s"APNSDispatcher:: Stopping ${kv._1} APNSClient.")
        kv._2.disconnect().await(5000)
      })
      super.postStop()
    }
  }

  private def getAPNSClient(appName: String) = {
    ConnektLogger(LogFile.PROCESSORS).info(s"APNSDispatcher:: Starting $appName APNSClient.")
    val credential = KeyChainManager.getAppleCredentials(appName).get
    val client = new ApnsClient[SimpleApnsPushNotification](credential.getCertificateFile, credential.passkey)
    client.connect(ApnsClient.PRODUCTION_APNS_HOST).await(30, TimeUnit.SECONDS)
    client
  }

}
