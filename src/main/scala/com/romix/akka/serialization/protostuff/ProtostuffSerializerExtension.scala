/*******************************************************************************
 * Copyright 2012 Roman Levenstein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.romix.akka.serialization.protostuff

import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.ExtendedActorSystem
import com.typesafe.config.Config

import akka.actor.{ ActorSystem, Extension, ExtendedActorSystem, Address, DynamicAccess }
import akka.event.Logging
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable.ArrayBuffer
import java.io.NotSerializableException

object ProtostuffSerialization {

  class Settings(val config: Config) {
  
    import scala.collection.JavaConverters._
    import config._

	// type can be: graph, simple
    val SerializerType: String = if(config.hasPath("akka.actor.protostuff.type")) config.getString("akka.actor.protostuff.type") else "graph"

    val BufferSize: Int = if(config.hasPath("akka.actor.protostuff.buffer-size")) config.getInt("akka.actor.protostuff.buffer-size") else 4096
	
	// Each entry should be: FQCN -> integer id
    val ClassNameMappings: Map[String, String] = if (config.hasPath("akka.actor.protostuff.mappings")) configToMap(getConfig("akka.actor.protostuff.mappings")) else Map[String, String]()

    val ClassNames: java.util.List[String] = if (config.hasPath("akka.actor.protostuff.classes")) config.getStringList("akka.actor.protostuff.classes") else new java.util.ArrayList()
	
	// Strategy: default, explicit, incremental
    val IdStrategy: String = if(config.hasPath("akka.actor.protostuff.idstrategy")) config.getString("akka.actor.protostuff.idstrategy") else "default"

    private def configToMap(cfg: Config): Map[String, String] =
      cfg.root.unwrapped.asScala.toMap.map { case (k, v) => (k, v.toString) }
  }  
}

class ProtostuffSerialization(val system: ExtendedActorSystem) extends Extension {
  import ProtostuffSerialization._
	 
  val settings = new Settings(system.settings.config)
  val log = Logging(system, getClass.getName) 
  
}

object ProtostuffSerializationExtension extends ExtensionId[ProtostuffSerialization] with ExtensionIdProvider {
  override def get(system: ActorSystem): ProtostuffSerialization = super.get(system)
  override def lookup = ProtostuffSerializationExtension
  override def createExtension(system: ExtendedActorSystem): ProtostuffSerialization = new ProtostuffSerialization(system)
}