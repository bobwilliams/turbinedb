package com.sparcedge.turbine.blade.cache

import scala.collection.mutable
import scala.collection.immutable.TreeMap
import com.sparcedge.turbine.blade.query._
import com.sparcedge.turbine.blade.cache._
import com.sparcedge.turbine.blade.event.Event
import com.sparcedge.turbine.blade.util.{Timer,BFFUtil}
import akka.dispatch.{Await,Future,Promise,ExecutionContext}
import akka.util.duration._

//TODO: Update Aggregate Cache Functions
class AggregateCache(cache: EventCache) {

	val aggregateCache = mutable.Map[String, Future[CachedAggregate]]()
	val aggregateGrouping = Grouping("duration", Some("minute"))

	def calculateQueryResults(query: Query)(implicit ec: ExecutionContext): TreeMap[String,Iterable[ReducedResult]] = {
		val timer = new Timer

		timer.start()
		val aggregates = retrieveCachedAggregatesForQuery(query)
		timer.stop("Create/Retrieve Cached Aggregates")
		timer.start()
		val updatedAggregates = aggregates.map { case (property,aggregate) => (property,sliceAggregate(query, aggregate)) }
		timer.stop("Slice/Merge Boundary Data")
		timer.start()
		val reducedAggregates = updatedAggregates map { case (property,aggregate) => QueryResolver.removeHourGroupFlattendAndReduceAggregate(aggregate, property) }
		timer.stop("Flatten/Re-reduce Aggregates")
		timer.start()
		val flattened = QueryResolver.flattenAggregates(reducedAggregates)
		timer.stop("Flatten Grouped Aggregates")

		flattened
	}

	private def retrieveCachedAggregatesForQuery(query: Query)(implicit ec: ExecutionContext): Map[String,CachedAggregate] = {
		val reducers = query.reduce match {
			case Some(reduce) => reduce.reducerList
			case None => List[Reducer]()
		}
		
		val propertyReduceMap = retrieveAndOptionallyAddCachedAggregates(query, reducers)
		propertyReduceMap map { case (property,aggFuture) => (property, await(aggFuture)) }
	}

	private def retrieveAndOptionallyAddCachedAggregates(query: Query, reducers: List[Reducer])(implicit ec: ExecutionContext): Map[String, Future[CachedAggregate]] = {
		var aggPromises = List[(Reducer,Promise[CachedAggregate])]()
		var aggFutureMap = Map[String, Future[CachedAggregate]]()

		reducers foreach { reducer =>
			val aggregateCacheString = query.createAggregateCacheString(reducer)
			val futureAggregate = aggregateCache.getOrElseUpdate(aggregateCacheString, {
				val aggPromise = Promise[CachedAggregate]()
				aggPromises = (reducer -> aggPromise) :: aggPromises
				aggPromise
			})
			aggFutureMap =  aggFutureMap + (reducer.propertyName -> futureAggregate)
		}

		if(aggPromises.size > 0) {
			calculateAggregatesAndCompletePromises(query, aggPromises)
		}
		
		aggFutureMap
	}

	private def await[T](future: Future[T]): T = {
		Await.result(future, 120 seconds)
	}

	private def calculateAggregatesAndCompletePromises(query: Query, aggPromises: List[(Reducer,Promise[CachedAggregate])]) {
		val aggregateCalculations = aggPromises.map((_._1 -> mutable.Map[String,ReducedResult]()))		
		val groupings = aggregateGrouping :: query.groupings
		val timer = new Timer

		timer.start()
		BFFUtil.processCachedEvents(cache.blade) { event =>
			QueryResolver.matchGroupReduceEventAndUpdateAggregateCalculations(event, query.matches, groupings, aggregateCalculations)
		}
		timer.stop("Created all aggregate caches")

		aggregateCalculations foreach { case (reducer, resultMap) =>
			val promise = aggPromises.find(_._1 == reducer).get._2
			promise.complete (
				Right (
					new CachedAggregate (
						matchSet = query.matches,
						groupSet = query.groupings,
						reducer = reducer,
						aggregateMap = TreeMap(resultMap.toArray:_*)
					)
				)
			)
		}
	}

	private def sliceAggregate(query: Query, aggregate: CachedAggregate): TreeMap[String,ReducedResult] = {
		val timer = new Timer
		var sliced = aggregate.aggregateMap
		val lowerBoundBroken = query.range.start > cache.periodStart
		var upperBoundBroken = query.range.end != None && query.range.end.get < cache.periodEnd

		timer.start()
		if(lowerBoundBroken) {
			sliced = sliced.from(query.startPlusMinute)
		}
		if(upperBoundBroken) {
			sliced = sliced.to(query.endMinute.get)
		}
		timer.stop("Slice Aggregate", 1)

		sliced
	}
}

case class CachedAggregate(matchSet: Iterable[Match], groupSet: List[Grouping], reducer: Reducer, var aggregateMap: TreeMap[String,ReducedResult])