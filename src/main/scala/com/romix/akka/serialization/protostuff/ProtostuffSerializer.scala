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

import akka.serialization._
import akka.actor.ExtendedActorSystem
import akka.event.Logging
import com.typesafe.config.ConfigFactory
import com.dyuproject.protostuff.ProtostuffIOUtil
import com.dyuproject.protostuff.GraphIOUtil
import com.dyuproject.protostuff.LinkedBuffer
import com.dyuproject.protostuff.runtime.OnDemandIdStrategy
import com.dyuproject.protostuff.runtime.IncrementalIdStrategy
import com.dyuproject.protostuff.runtime.DefaultIdStrategy
import com.dyuproject.protostuff.runtime.ExplicitIdStrategy
import com.dyuproject.protostuff.runtime.IdStrategy
import com.dyuproject.protostuff.runtime.RuntimeSchema
import com.dyuproject.protostuff.CollectionSchema
import com.dyuproject.protostuff.MapSchema
import com.dyuproject.protostuff.runtime.EnumIO
import java.util.Map
import scala.collection.JavaConversions._

import scala.util.DynamicVariable
import java.lang.System

import ProtostuffSerialization._
import java.util.concurrent.TimeUnit     

case class Wrapper(obj:AnyRef)

class ProtostuffSerializer (val system: ExtendedActorSystem) extends Serializer {

	import ProtostuffSerialization._
    val log = Logging(system, getClass.getName) 
	
	val settings = new Settings(system.settings.config)
	
	val mappings = settings.ClassNameMappings

	locally {
	   log.debug("Got mappings: {}", mappings)
	}
	
	val classnames = settings.ClassNames

	locally {
	   log.debug("Got classnames for incremental strategy: {}", classnames)
	}
		
	val idstrategy = getIdStrategy(settings.IdStrategy)

	locally {
	   log.debug("Got idstrategy: {}", idstrategy)
	}
		
	val bufferSize = settings.BufferSize
	
	locally {
	   log.debug("Got buffer-size: {}", bufferSize)
	}
	
	val serializer = settings.SerializerType match {
		case "graph"  => new ProtostuffGraphSerializer(idstrategy, bufferSize)
		case _    => new ProtostuffNoGraphSerializer(idstrategy, bufferSize)
	}

	locally {
	   log.debug("Got serializer: {}", serializer)
	}
	
	
    // This is whether "fromBinary" requires a "clazz" or not
    def includeManifest: Boolean = false
     
    // A unique identifier for this Serializer
    def identifier = 123454321
	
	// Delegate to a real serializer
	def toBinary(obj: AnyRef): Array[Byte] = serializer.toBinary(obj) 
	def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = serializer.fromBinary(bytes, clazz)
	
    private def getIdStrategy(strategy: String): IdStrategy = {
		strategy match  {
			case "default" => return new DefaultIdStrategy()
			
			case "incremental" => {
				val idNums = mappings.values map { case v => v.toInt }
				val maxIdNum = if(!mappings.isEmpty) idNums.max else 1
			
				val r = new OnDemandIdStrategy.Registry(
                    maxIdNum+1+64, maxIdNum+1, 
                    maxIdNum+1+64, maxIdNum+1, 
                    maxIdNum+1+64, maxIdNum+1, // enums
                    maxIdNum+1+64, maxIdNum+1); // pojos

				r.registerPojo(classOf[Wrapper], 1)	
				
				for ((fqcn: String, idNum: String) <- mappings) {
					val id = idNum.toInt
					// Load class
					system.dynamicAccess.getClassFor[AnyRef](fqcn) match {
						case Right(clazz) => {
							 
							// Identity what it is: POJO, map, collection, enum
							// Register it
							if(clazz.isEnum()) 
							  r.registerEnum(classOf[TimeUnit].getClass().cast(clazz), id)
							else if(classOf[java.util.Map[_, _]].isAssignableFrom(clazz))  
							  r.registerMap(MapSchema.MessageFactories.getFactory(fqcn), id)
							else if(classOf[java.util.Collection[_]].isAssignableFrom(clazz))  
							  r.registerCollection(CollectionSchema.MessageFactories.getFactory(fqcn), id)
							else
							  r.registerPojo(clazz, id)			
					  }
					  case Left(e) => {  log.error("Class could not be loaded and/or registered: {} ", fqcn); throw e }
					}
				}
				
				for(classname <- classnames) {
					// Load class
					system.dynamicAccess.getClassFor[AnyRef](classname) match {
						// TODO: IncrementalIdStrategy should allow for registrarion of enums, maps, collections
						// automatically
						case Right(clazz) => r.strategy.register(clazz)
						case Left(e) => { log.warning("Class could not be loaded and/or registered: {} ", classname); /* throw e */ }
					}
				}
				
				return r.strategy
			}
			
			case "explicit" => { 
				val r = new ExplicitIdStrategy.Registry()
				
				r.registerPojo(classOf[Wrapper], 1)					
				
				for ((fqcn: String, idNum: String) <- mappings) {
					val id = idNum.toInt
					// Load class
					system.dynamicAccess.getClassFor[AnyRef](fqcn) match {
						case Right(clazz) => {
							// Identity what it is: POJO, map, collection, enum
							// Register it
							if(clazz.isEnum()) 
							  r.registerEnum(classOf[TimeUnit].getClass().cast(clazz), id)
							else if(classOf[java.util.Map[_, _]].isAssignableFrom(clazz))  
							  r.registerMap(MapSchema.MessageFactories.getFactory(fqcn), id)
							else if(classOf[java.util.Collection[_]].isAssignableFrom(clazz))  
							  r.registerCollection(CollectionSchema.MessageFactories.getFactory(fqcn), id)
							else  
							  r.registerPojo(clazz, id)	
						}
						
						case Left(e) => { log.error("Class could not be loaded and/or registered: {} ", fqcn); throw e}
					}
				}
				return r.strategy
			}
		}
	}
	
      
}

/***
   Protostuff serializer that supports object graphs with nodes sharing.
   It produces smaller serialized representations, but introduces a bit more overhead.
*/
class ProtostuffGraphSerializer(val idStrategy: IdStrategy, val bufferSize: Int) extends Serializer {
		
	val wrapperSchema = RuntimeSchema.getSchema(classOf[Wrapper], idStrategy);	
     
    // This is whether "fromBinary" requires a "clazz" or not
    def includeManifest: Boolean = false
     
    // A unique identifier for this Serializer
    def identifier = 12454321
     
    // "toBinary" serializes the given object to an Array of Bytes
    def toBinary(obj: AnyRef): Array[Byte] = {
			val buffer = LinkedBuffer.allocate(bufferSize)
			var payload: Array[Byte] = null
			val wrapper = new Wrapper(obj)

			try {
				payload = GraphIOUtil.toByteArray(wrapper, wrapperSchema, buffer)
			} catch {
				case e:Exception => e.printStackTrace()
			} finally {
				buffer.clear()
			}
						
			payload
    }
     
    // "fromBinary" deserializes the given array,
    // using the type hint (if any, see "includeManifest" above)
    // into the optionally provided classLoader.
    def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
		val wrapper = new Wrapper(null)
		GraphIOUtil.mergeFrom(bytes, wrapper, wrapperSchema)
		wrapper.obj
    }
}

/***
   Protostuff serializer that supports object graphs without nodes sharing.
*/
class ProtostuffNoGraphSerializer(val idStrategy: IdStrategy, val bufferSize: Int) extends Serializer {

	val wrapperSchema = RuntimeSchema.getSchema(classOf[Wrapper], idStrategy);
     
    // This is whether "fromBinary" requires a "clazz" or not
    def includeManifest: Boolean = false
     
    // A unique identifier for this Serializer
    def identifier = 12454321
     
    // "toBinary" serializes the given object to an Array of Bytes
    def toBinary(obj: AnyRef): Array[Byte] = {
			val buffer = LinkedBuffer.allocate(bufferSize)
			var payload: Array[Byte] = null
			val wrapper = new Wrapper(obj)

			try {
				payload = ProtostuffIOUtil.toByteArray(wrapper, wrapperSchema, buffer)
			} finally {
				buffer.clear()
			}
						
			payload
    }
     
    // "fromBinary" deserializes the given array,
    // using the type hint (if any, see "includeManifest" above)
    // into the optionally provided classLoader.
    def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
		val wrapper = new Wrapper(null)
		ProtostuffIOUtil.mergeFrom(bytes, wrapper, wrapperSchema)
		wrapper.obj
    }
}
