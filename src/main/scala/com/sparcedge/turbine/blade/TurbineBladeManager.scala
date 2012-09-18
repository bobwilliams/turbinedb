package com.sparcedge.turbine.blade

import akka.actor.{Actor,Props,ActorSystem,ActorRef}
import akka.routing.RoundRobinRouter
import akka.util.duration._
import java.util.concurrent.atomic.AtomicLong
import com.sparcedge.turbine.blade.mongo.MongoDBConnection
import com.sparcedge.turbine.blade.query.{TurbineQuery,QueryHandler,HandleQuery,Blade}
import com.sparcedge.turbine.blade.cache.{EventCacheManager,UpdateEventCacheWithNewEventsRequest}
import com.sparcedge.turbine.blade.util.{BFFUtil,Timer}
import scala.collection.mutable
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

class TurbineBladeManager(mongoConn: MongoDBConnection, preloadBlades: List[Blade]) extends Actor {
	
	implicit val mongoConnection = mongoConn
	val actorSegmentCacheMap = discoverExistingBladesAndInitializeNewBlades(preloadBlades)
	val queryHandlerRouter = context.actorOf(Props[QueryHandler].withRouter(RoundRobinRouter(50)), "QueryHandlerRouter")
	var eventCacheManagers = (actorSegmentCacheMap.map(_._2)).toIndexedSeq
	val next = new AtomicLong(0)

	context.system.scheduler.schedule(
		60 seconds,
    	60 seconds,
    	self,
    	UpdateCurrentEventCaches()
    )

	context.system.scheduler.schedule(
		30 seconds,
    	30 seconds,
    	self,
    	UpdateOneHistoricalCache()
    )

	def receive = {
		case QueryDispatchRequest(rawQuery) =>
			val query = TurbineQuery(rawQuery)
			val cacheKey = query.blade.segmentCacheString
			val cacheManager = actorSegmentCacheMap.getOrElseUpdate(cacheKey, {
				val man = context.actorOf(Props(new EventCacheManager(query.blade)), name = cacheKey)
				eventCacheManagers = eventCacheManagers :+ man
				man
			})
			queryHandlerRouter ! HandleQuery(query, cacheManager)
		case UpdateCurrentEventCaches() =>
			val dateSegment = new DateTime().toString("yyyy-MM")
			actorSegmentCacheMap.filter(_._1.endsWith(dateSegment)).foreach { case (segment, cacheManager) =>
				cacheManager ! UpdateEventCacheWithNewEventsRequest()
			}
		case UpdateOneHistoricalCache() =>
			if(!eventCacheManagers.isEmpty) {
				val man = eventCacheManagers((next.getAndIncrement % eventCacheManagers.size).asInstanceOf[Int])
				man ! UpdateEventCacheWithNewEventsRequest()
			}
		case _ =>
	}

	def discoverExistingBladesAndInitializeNewBlades(blades: List[Blade]): mutable.Map[String,ActorRef] = {
		val timer = new Timer
		timer.start()
		val existingBlades = BFFUtil.retrieveBladesFromExistingData()
		timer.stop("[TurbineBladeManager] Discovered existing blades (" + existingBlades.size + ")")
		timer.start()
		val allBlades = (blades ++ existingBlades).toSet
		val bladeMap = mutable.Map ( 
			allBlades.toSeq map { blade =>
				(blade.segmentCacheString, context.actorOf(Props(new EventCacheManager(blade)), name = blade.segmentCacheString))
			}: _*
		)
		timer.stop("[TurbineBladeManager] Created managers for all new / existing blades (" + allBlades.size + ")")
		bladeMap
	}
}

case class QueryDispatchRequest(rawQuery: String)
case class UpdateCurrentEventCaches()
case class UpdateOneHistoricalCache()