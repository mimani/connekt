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
package com.flipkart.connekt.firefly.flows

import akka.NotUsed
import akka.http.scaladsl.model._
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.flipkart.connekt.busybees.models.WAContactTracker
import com.flipkart.connekt.commons.entities.WAContactEntity
import com.flipkart.connekt.commons.factories.{ConnektLogger, LogFile}
import com.flipkart.connekt.commons.iomodels.WAResponse
import com.flipkart.connekt.commons.services.WAContactService
import com.flipkart.connekt.commons.utils.StringUtils._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

class WAResponseHandler(implicit m: Materializer, ec: ExecutionContext) {

  val flow: Flow[(Try[HttpResponse], WAContactTracker), NotUsed, NotUsed] = Flow[(Try[HttpResponse], WAContactTracker)].map { responseTrackerPair =>

    val httpResponse = responseTrackerPair._1
    val requestTracker = responseTrackerPair._2

    httpResponse match {
      case Success(r) =>
        try {
//          TODO: Added metering for errors
          val response = r.entity.asInstanceOf[WAResponse]
          val results = response.payload.results
          ConnektLogger(LogFile.PROCESSORS).debug(s"WAResponseHandler received http response for: $results")
          r.status.intValue() match {
            case 200 if response.error.equalsIgnoreCase("false") =>
              results.map(result => {
                WAContactService.instance.add(WAContactEntity(result.input_number, result.wa_username,requestTracker.appName, result.wa_exists, None))
              })
              ConnektLogger(LogFile.PROCESSORS).trace(s"WAResponseHandler contacts updated in hbase : $results")
            case w =>
              ConnektLogger(LogFile.PROCESSORS).error(s"WAResponseHandler received http response for: ${response.getJson} , with status code $w")
          }
        } catch {
          case e: Exception =>
            ConnektLogger(LogFile.PROCESSORS).error(s"WAResponseHandler failed processing http response body for: $r", e)
        }
      case Failure(e2) =>
        ConnektLogger(LogFile.PROCESSORS).error(s"WAResponseHandler send failure for: $requestTracker", e2)
    }
    NotUsed
  }
}
