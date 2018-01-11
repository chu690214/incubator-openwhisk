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
import whisk.core.entity.Identity
import pureconfig.loadConfigOrThrow
import whisk.common.TransactionId
import whisk.core.containerpool.Container
import whisk.core.entity.{ActivationLogs, ExecutableWhiskAction, WhiskActivation}

import scala.concurrent.Future

trait LogDriverLogStoreConfig {
  def message: String
  def dockerLogDriver: String
  def dockerLogDriverOpts: Set[String]
}

case class ConsumerlessLogDriverLogStoreConfig(message: String,
                                               dockerLogDriver: String,
                                               dockerLogDriverOpts: Set[String])
    extends LogDriverLogStoreConfig

/**
 * Docker log driver based LogStore impl. Uses docker log driver to emit container logs to an external store.
 * Fetching logs from that external store is not provided in this trait.
 */
class LogDriverLogStore(actorSystem: ActorSystem,
                        config: LogDriverLogStoreConfig =
                          loadConfigOrThrow[ConsumerlessLogDriverLogStoreConfig]("whisk.logstore.log-driver"))
    extends LogStore {
  val logDriverMessage = config.message
  val logParameters = Map("--log-driver" -> Set(config.dockerLogDriver), "--log-opt" -> config.dockerLogDriverOpts)

  override def containerParameters = logParameters.map(kv => (kv._1, kv._2.toSet)).toMap

  def collectLogs(transid: TransactionId,
                  user: Identity,
                  activation: WhiskActivation,
                  container: Container,
                  action: ExecutableWhiskAction): Future[ActivationLogs] =
    Future.successful(ActivationLogs()) //no logs collected when using docker log drivers (see DockerLogStore for json-file exception)

  /** no logs exposed to API/CLI using only the LogDriverLogStore; use an extended version, e.g. the SplunkLogStore to expose logs from some external source */
  def fetchLogs(activation: WhiskActivation): Future[ActivationLogs] =
    Future.successful(ActivationLogs(Vector(logDriverMessage)))

}
object LogDriverLogStoreProvider extends LogStoreProvider {
  override def logStore(actorSystem: ActorSystem) = new LogDriverLogStore(actorSystem)
}
