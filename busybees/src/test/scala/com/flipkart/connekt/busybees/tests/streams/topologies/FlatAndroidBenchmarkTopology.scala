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
package com.flipkart.connekt.busybees.tests.streams.topologies

import java.util.concurrent.atomic.AtomicLong

import akka.stream.SourceShape
import akka.stream.scaladsl.GraphDSL.Implicits._
import akka.stream.scaladsl._
import com.flipkart.connekt.busybees.streams.flows.dispatchers.{GCMHttpDispatcherPrepare, HttpDispatcher}
import com.flipkart.connekt.busybees.streams.flows.formaters.AndroidHttpChannelFormatter
import com.flipkart.connekt.busybees.streams.flows.reponsehandlers.GCMResponseHandler
import com.flipkart.connekt.busybees.streams.flows.{FlowMetrics, RenderFlow}
import com.flipkart.connekt.busybees.streams.sources.KafkaSource
import com.flipkart.connekt.busybees.tests.streams.TopologyUTSpec
import com.flipkart.connekt.commons.entities.Channel
import com.flipkart.connekt.commons.factories.ServiceFactory
import com.flipkart.connekt.commons.iomodels.{ConnektRequest, PNCallbackEvent}
import com.flipkart.connekt.commons.metrics.Instrumented
import com.flipkart.connekt.commons.services.ConnektConfig
import com.flipkart.connekt.commons.utils.StringUtils._

import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}

//@Ignore
class FlatAndroidBenchmarkTopology extends TopologyUTSpec with Instrumented {

  override def beforeAll() = {
    super.beforeAll()
    HttpDispatcher.init(ConnektConfig.getConfig("react").get)
  }

  lazy val ioDispatcher = system.dispatchers.lookup("akka.actor.io-dispatcher")
  lazy val fmtAndroidParallelism = ConnektConfig.getInt("topology.push.androidFormatter.parallelism").get

  "FlatAndroidBenchmarkTopology with responseHandler" should "log throughput rates" in {
    val counter: AtomicLong = new AtomicLong(0)
    val prevTime = new AtomicLong(System.currentTimeMillis())

    val topic = ServiceFactory.getMessageService(Channel.PUSH).getTopicNames(Channel.PUSH, Some("android")).get.head
    val kSource = Source.fromGraph(new KafkaSource[ConnektRequest](getKafkaConsumerConf, topic, getKafkaConsumerConf.getString("group.id")))
    val repeatSource = Source.repeat {
      """
        |{
        |	"channel": "PN",
        |	"sla": "H",
        |	"channelData": {
        |		"type": "PN",
        |		"data": {
        |			"message": "Hello Kinshuk. GoodLuck!",
        |			"title": "Kinshuk GCM Push Test",
        |			"id": "123456789",
        |			"triggerSound": true,
        |			"notificationType": "Text"
        |
        |		}
        |	},
        |	"channelInfo" : {
        |	    "type" : "PN",
        |	    "ackRequired": true,
        |    	"delayWhileIdle": true,
        |     "platform" :  "android",
        |     "appName" : "ConnektSampleApp",
        |     "deviceIds" : ["513803e45cf1b344ef494a04c9fb650a"]
        |	},
        |	"meta": {
        |   "x-perf-test" : "true"
        | }
        |}
      """.stripMargin.getObj[ConnektRequest]
    }

    val render = Flow.fromGraph(new RenderFlow().flow)
    val gcmFmt = Flow.fromGraph(new AndroidHttpChannelFormatter(fmtAndroidParallelism)(ioDispatcher).flow)
    val gcmPrepare = Flow.fromGraph(new GCMHttpDispatcherPrepare().flow)
    val gcmRHandler = Flow.fromGraph(new GCMResponseHandler().flow)
    val metrics = Flow.fromGraph(new FlowMetrics[PNCallbackEvent](Channel.PUSH).flow)
    val qps = meter("android.send")

    val complexSource = Source.fromGraph(GraphDSL.create() { implicit b =>
      val merge = b.add(Merge[ConnektRequest](3))
      repeatSource ~> merge.in(0)
      Source.empty[ConnektRequest] ~> merge.in(1)
      Source.empty[ConnektRequest] ~> merge.in(2)

      SourceShape(merge.out)
    })

    val gWithRHandler = complexSource.via(render).via(gcmFmt).via(gcmPrepare).via(HttpDispatcher.gcmPoolClientFlow).via(gcmRHandler).via(metrics).to(Sink.foreach(e => {
      qps.mark()
      if (0 == (counter.incrementAndGet() % 1000)) {
        val currentTime = System.currentTimeMillis()
        val rate = 1000000 / (currentTime - prevTime.getAndSet(currentTime))
        println(s"FlatAndroidBenchmarkTopology #Rate: [$rate], MR[${qps.getMeanRate}}], 1MR[${qps.getOneMinuteRate}}] upto ${counter.get()} messages by $currentTime")
      }
    }))

    gWithRHandler.run()

    Await.result(Promise[String]().future, 400.seconds)
  }

}
