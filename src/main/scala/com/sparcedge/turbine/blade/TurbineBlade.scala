package com.sparcedge.turbine.blade

import akka.actor.{ActorSystem,Actor,Props}
import com.sparcedge.turbine.blade.config.BladeConfig
import com.sparcedge.turbine.blade.mongo.MongoDBConnection

object TurbineBlade extends App {

	val configStr = args.headOption.getOrElse {
		throw new Exception("No Configuration Supplied")
	}

	import scala.collection.JavaConversions._

	for(env <- System.getenv()) {
		println(env)
	}

	val config = BladeConfig(configStr)
	val actorSystem = ActorSystem("TurbineBladeActorSystem")
	val mongoConnection = MongoDBConnection(config)
	val bladeManager = actorSystem.actorOf(Props(new TurbineBladeManager(mongoConnection)), name = "BladeManager")

	println("{\"status\": \"running\"}")

	for( rawQuery <- io.Source.fromInputStream(System.in)("UTF-8").getLines ) {
		bladeManager ! QueryDispatchRequest(rawQuery)
	}

	actorSystem.shutdown
}