akka-protostuff-serialization - protostuff-based serializers for Akka
=====================================================================

This library provides a custom protostuff-based serializer for Akka. It can be used for more efficient akka actor's remoting. 

Features
--------

*   It is more efficient than Java serialization - both in size and speed 
*   Does not require any additional build steps like compiling proto files, when using protobuf serialization
*   Almost any Scala and Java class can be serialized using it without any additional configuration or code changes

How to use this library in your project
----------------------------------------

To use this serializer, you need to do two things:
*   Include a dependency on this library into your project:

	`libraryDependencies += "com.romix.akka" % "akka-protostuff-serialization" % "1.0"`
    
*   Add some new elements to your Akka configuration file, e.g. `application.conf`


Configuration of akka-protostuff-serialization
----------------------------------------------

The following options are available for configuring this serializer:

*   You need to add a following line to the list of your Akka extensions:
	`extensions = ["com.romix.akka.serialization.protostuff.ProtostuffSerializationExtension$"]`

*   You need to add a new `protostuff` section to the akka.actor part of configuration  

		protostuff  {  
			# Possibles values for type are: graph or nograph  
			# graph supports serialization of object graphs with shared nodes  
			# and cyclic references, but this comes at the expense of a small overhead  
			# nograph does not support object grpahs with shared nodes, but is usually faster   
			type = "graph"  
			
			  
			# Possible values for idstrategy are:  
			# default, explicit, incremental  
			#  
			# default - slowest and produces bigger serialized representation. Contains fully-  
			# qualified class names (FQCNs) for each class  
			#  
			# explicit - fast and produces compact serialized representation. Requires that all  
			# classes that will be serialized are pre-registered using the "mappings" section  
			# To guarantee that both sender and receiver use the same numeric ids for the same  
			# classes it is advised to provide exactly the same entries in the "mappings" section   
			#  
			# incremental - fast and produces compact serialized representation. Support optional  
			# pre-registering of classes using the "mappings" and "classes" sections. If class is  
			# not pre-registered, it will be registered dynamically by picking a next available id  
			# To guarantee that both sender and receiver use the same numeric ids for the same   
			# classes it is advised to pre-register them using at least the "classes" section   
			  
			idstrategy = "incremental"  
			  
			# Define a default size for byte buffers used during serialization   
			buffer-size = 4096  
			  
			# Define mappings from a fully qualified class name to a numeric id.  
			# Smaller ids lead to smaller sizes of serialized representations.  
			#  
			# This section is mandatory for idstartegy=explciit  
			# This section is optional  for idstartegy=incremental  
			# This section is ignored   for idstartegy=default  
			#   
			# The smallest possible id is 2   
			mappings {  
				"package1.name1.className1" = 2,  
				"package2.name2.className2" = 3  
			}  
			  
			# Define a set of fully qualified class names for   
			# classes to be used for serialization.  
			#  
			# This section is optional  for idstartegy=incremental  
			# This section is ignored   for idstartegy=default  
			# This section is ignored   for idstartegy=explicit  
			classes = [  
				"com.romix.akka.serialization.protostuff.StringMessage",  
				"com.romix.akka.serialization.protostuff.ProtostuffSerializer"  
			]  
		}


*   You should declare in the Akka `serializers` section a new kind of serializer:  

		serializers {  
			java = "akka.serialization.JavaSerializer"  
			# Define protostuff serializer   
			protostuff = "com.romix.akka.serialization.protostuff.ProtostuffSerializer"  
		}    
     
*    As usual, you should declare in the Akka `serialization-bindings` section which classes should use protostuff serialization