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
package com.flipkart.connekt.firefly.dispatcher

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.ActorMaterializer
import com.flipkart.connekt.busybees.models.WAContactTracker
import com.flipkart.connekt.commons.services.ConnektConfig
import com.flipkart.connekt.firefly.sinks.http.HttpRequestTracker
import com.typesafe.config.Config
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import com.typesafe.sslconfig.ssl.{TrustManagerConfig, TrustStoreConfig}

import scala.concurrent.ExecutionContextExecutor

class HttpDispatcher(actorSystemConf: Config) {

  implicit val httpSystem: ActorSystem = ActorSystem("firefly-http-out", actorSystemConf)
  implicit val httpMat: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = httpSystem.dispatcher

  private val insecureHttpFlow = {
    val certPath = ConnektConfig.getString("wa.certificate.path").get
    val trustStoreConfig = TrustStoreConfig(None, Some(certPath)).withStoreType("PEM")
    val maxOpenRequests = ConnektConfig.getInt("wa.max.open.requests").get
    val maxConnections = ConnektConfig.getInt("wa.max.parallel.connections").get
    val trustManagerConfig = TrustManagerConfig().withTrustStoreConfigs(List(trustStoreConfig))
    val badSslConfig = AkkaSSLConfig().mapSettings(s => s.withLoose(s.loose
      .withAcceptAnyCertificate(true)
      .withDisableHostnameVerification(true)
    ).withTrustManagerConfig(trustManagerConfig))
    val badCtx = Http().createClientHttpsContext(badSslConfig)
    Http().superPool[WAContactTracker](badCtx, ConnectionPoolSettings(httpSystem).withMaxOpenRequests(maxOpenRequests).withMaxConnections(maxConnections))(httpMat)
  }

  val httpPoolFlow = Http().superPool[HttpRequestTracker]()(httpMat)

}

object HttpDispatcher {

  var dispatcher: Option[HttpDispatcher] = None

  def apply(config: Config) = {
    if (dispatcher.isEmpty) {
      dispatcher = Some(new HttpDispatcher(config))
    }
  }

  def insecureHttpFlow = dispatcher.map(_.insecureHttpFlow).get

  def httpFlow = dispatcher.map(_.httpPoolFlow).get

}
