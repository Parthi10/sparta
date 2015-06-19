/**
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.sparkta.testat

import java.io.{File, PrintStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.channels.ServerSocketChannel

import akka.event.slf4j.SLF4JLogging
import akka.util.Timeout
import com.stratio.sparkta.driver.constants.AppConstant
import com.stratio.sparkta.driver.helpers.sparkta.{MockSystem, SparktaHelper}
import com.typesafe.config.Config
import org.apache.curator.test.TestingServer
import org.scalatest.{BeforeAndAfter, Matchers, WordSpecLike}
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http._
import spray.testkit.ScalatestRouteTest

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.io.Source
import scala.util.Try

/**
 * Common operations that will be used in Acceptance Tests. All AT must extends from it.
 * @author arincon
 */
trait SparktaATSuite extends WordSpecLike with ScalatestRouteTest with SLF4JLogging with BeforeAndAfter with Matchers {

  val Localhost        = "127.0.0.1"
  val SparktaPort      = 9090
  val TestServerZKPort = 6666
  val SocketPort       = 10666
  val SparktaSleep     = 3000
  val PolicySleep      = 25000

  var zkTestServer: TestingServer       = _
  var serverSocket: ServerSocketChannel = _
  var out         : PrintStream         = _

  /**
   * Starts an embedded ZK server.
   */
  def zookeeperStart: Unit = {
    zkTestServer = new TestingServer(TestServerZKPort)
    zkTestServer.start()
  }

  /**
   * Starts a socket that will act as an input sending streams of data.
   */
  def socketStart: Unit = {
    serverSocket = ServerSocketChannel.open()
    serverSocket.socket.bind(new InetSocketAddress(Localhost, SocketPort))
  }

  /**
   * Starts an instance of Sparkta with a given configuration (reference.conf in our resources folder).
   */
  def startSparkta: Unit = {
    val sparktaConfig = SparktaHelper.initConfig(AppConstant.ConfigAppName)
    val configApi: Config = SparktaHelper.initConfig(AppConstant.ConfigApi, Some(sparktaConfig))
    val sparktaHome = SparktaHelper.initSparktaHome(new MockSystem(Map("SPARKTA_HOME" -> getSparktaHome), Map()))
    val jars = SparktaHelper.initJars(AppConstant.JarPaths, sparktaHome)
    val sparktaPort = configApi.getInt("port")

    SparktaHelper.initAkkaSystem(sparktaConfig, configApi, jars, AppConstant.ConfigAppName)
    sleep(SparktaSleep)

    openSocket(sparktaPort).isSuccess should be(true)
  }

  /**
   * Opens a socket in a given port
   * @param portNumber of the socket
   * @return a Try object that contains a socket if succeed.
   */
  def openSocket(portNumber: Int): Try[Socket] = {
      Try(new Socket(Localhost, portNumber))
  }

  /**
   * This is a workaround to find the jars either in the IDE or in a maven execution.
   * This test should be moved to acceptance tests when available
   */
  def getSparktaHome: String = {
    val fileForIde = new File(".", "plugins")

    if (fileForIde.exists()) {
      new File(".").getCanonicalPath
    } else {
      new File("../.").getCanonicalPath
    }
  }

  /**
   * Given a policy it makes an http request to start it on Sparkta.
   * @param path of the policy.
   */
  def sendPolicy(path : String): Unit = {
    val policy = Source.fromFile(new File(path)).mkString // execution context for futures
    val pipeline: HttpRequest => Future[HttpResponse] = sendReceive
    val promise: Future[HttpResponse] =
      pipeline(Post(s"http://${Localhost}:${SparktaPort}/policies",
      HttpEntity(ContentType(MediaTypes.`application/json`, HttpCharsets.`UTF-8`), policy)))

    val response: HttpResponse = Await.result(promise, Timeout(5.seconds).duration)

    response.status should be(OK)
    sleep(PolicySleep)
  }



  /**
   * Reads from a CSV file and send data to the socket.
   * @param path of the CSV.
   */
  def sendDataToSparkta(path :String): Unit = {
    out = new PrintStream(serverSocket.socket().accept().getOutputStream())

    Source.fromFile(path).getLines().toList.map(line => {
      log.info(s"> Read data: $line")
      //scalastyle:off
      out.println(line)
      //scalastyle:on
    })

    out.flush()
  }

  protected def sleep(millis: Long): Unit =
    Thread.sleep(millis)

}
