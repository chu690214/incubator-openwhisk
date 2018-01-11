/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.containerpool.logging

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import pureconfig.error.ConfigReaderException

class LogDriverLogStoreTests extends TestKit(ActorSystem("LogDriverLogStore")) with FlatSpecLike with Matchers {

  val testConfig = ConsumerlessLogDriverLogStoreConfig(
    "some message",
    "fluentd",
    Set("fluentd-address=localhost:24225", "tag=OW_CONTAINER"))

  behavior of "LogDriver LogStore"

  it should "fail when loading out of box configs (because whisk.logstore doesn't exist)" in {
    a[ConfigReaderException[LogDriverLogStoreConfig]] should be thrownBy new LogDriverLogStore(system) {
      val logDriverLogStore = new LogDriverLogStore(system)
    }

  }

  it should "set the container parameters from the config" in {
    val logDriverLogStore = new LogDriverLogStore(system, testConfig)
    logDriverLogStore.logParameters shouldBe Map(
      "--log-driver" -> Set("fluentd"),
      "--log-opt" -> Set("fluentd-address=localhost:24225", "tag=OW_CONTAINER"))
    logDriverLogStore.logDriverMessage shouldBe "some message"
  }
}
