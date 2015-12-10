package com.flipkart.connekt.commons.services

import com.flipkart.connekt.commons.iomodels.CallbackEvent

import scala.util.Try

/**
 *
 *
 * @author durga.s
 * @version 12/9/15
 */
trait TCallbackService extends TService {
  def persistCallbackEvent(requestId: String, channel: String, callbackEvent: CallbackEvent): Try[String]
  def fetchCallbackEvent(requestId: String, channel: String): Try[Option[CallbackEvent]]
}