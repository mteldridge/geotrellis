package geotrellis.spark.costdistance

import geotrellis.proj4.LatLng
import geotrellis.raster._
import geotrellis.raster.costdistance.CostDistance
import geotrellis.spark._
import geotrellis.vector._

import org.apache.log4j.Logger
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.AccumulatorV2

import scala.collection.mutable


/**
  * This Spark-enabled implementation of the standard cost-distance
  * algorithm [1] is "heavily inspired" by the MrGeo implementation
  * [2] but does not share any code with it.
  *
  * 1. Tomlin, Dana.
  *    "Propagating radial waves of travel cost in a grid."
  *    International Journal of Geographical Information Science 24.9 (2010): 1391-1413.
  *
  * 2. https://github.com/ngageoint/mrgeo/blob/0c6ed4a7e66bb0923ec5c570b102862aee9e885e/mrgeo-mapalgebra/mrgeo-mapalgebra-costdistance/src/main/scala/org/mrgeo/mapalgebra/CostDistanceMapOp.scala
  */
object IterativeCostDistance {

  type KeyCostPair = (SpatialKey, CostDistance.Cost)
  type Changes = mutable.ArrayBuffer[KeyCostPair]

  val logger = Logger.getLogger(IterativeCostDistance.getClass)

  /**
    * An accumulator to hold lists of edge changes.
    */
  class ChangesAccumulator extends AccumulatorV2[KeyCostPair, Changes] {
    private val list: Changes = mutable.ArrayBuffer.empty

    def copy: ChangesAccumulator = {
      val other = new ChangesAccumulator
      other.merge(this)
      other
    }
    def add(pair: KeyCostPair): Unit = {
      this.synchronized { list.append(pair) }
    }
    def isZero: Boolean = list.isEmpty
    def merge(other: AccumulatorV2[KeyCostPair, Changes]): Unit =
      this.synchronized { list ++= other.value }
    def reset: Unit = this.synchronized { list.clear }
    def value: Changes = list
  }

  def computeResolution[K: (? => SpatialKey), V: (? => Tile)](
    friction: RDD[(K, V)] with Metadata[TileLayerMetadata[K]]
  ) = {
    val md = friction.metadata
    val mt = md.mapTransform
    val kv = friction.first
    val key = implicitly[SpatialKey](kv._1)
    val tile = implicitly[Tile](kv._2)
    val extent = mt(key).reproject(md.crs, LatLng)
    val degrees = extent.xmax - extent.xmin
    val meters = degrees * (6378137 * 2.0 * math.Pi) / 360.0
    val pixels = tile.cols
    math.abs(meters / pixels)
  }

  /**
    * Perform the cost-distance computation.
    *
    * @param  friction  The friction layer; pixels are in units of "seconds per meter"
    * @param  points    The starting locations from-which to compute the cost of traveling
    * @param  maxCost   The maximum cost before pruning a path (in units of "seconds")
    */
  def apply[K: (? => SpatialKey), V: (? => Tile)](
    friction: RDD[(K, V)] with Metadata[TileLayerMetadata[K]],
    points: List[Point],
    maxCost: Double = Double.PositiveInfinity
  )(implicit sc: SparkContext): RDD[(K, Tile)] = {

    val md = friction.metadata
    val mt = md.mapTransform
    val resolution = computeResolution(friction)
    logger.debug(s"Computed resolution: $resolution meters/pixel")

    val bounds = friction.metadata.bounds.asInstanceOf[KeyBounds[K]]
    val minKey = implicitly[SpatialKey](bounds.minKey)
    val minKeyCol = minKey._1
    val minKeyRow = minKey._2
    val maxKey = implicitly[SpatialKey](bounds.maxKey)
    val maxKeyCol = maxKey._1
    val maxKeyRow = maxKey._2

    val accumulator = new ChangesAccumulator
    sc.register(accumulator)

    // Create RDD of initial (empty) cost tiles and load the
    // accumulator with the starting values.
    var costs: RDD[(K, V, DoubleArrayTile)] = friction.map({ case (k, v) =>
      val key = implicitly[SpatialKey](k)
      val tile = implicitly[Tile](v)
      val cols = tile.cols
      val rows = tile.rows
      val extent = mt(key)
      val rasterExtent = RasterExtent(extent, cols, rows)

      points
        .filter({ point => extent.contains(point) })
        .map({ point =>
          val col = rasterExtent.mapXToGrid(point.x)
          val row = rasterExtent.mapYToGrid(point.y)
          val friction = tile.getDouble(col, row)
          val cost = (col, row, friction, 0.0)
          accumulator.add((key, cost))
        })

      (k, v, CostDistance.generateEmptyCostTile(cols, rows))
    }).persist(StorageLevel.MEMORY_AND_DISK_SER)

    costs.count

    // Repeatedly map over the RDD of cost tiles until no more changes
    // occur on the periphery of any tile.
    do {
      val _changes: Map[SpatialKey, Seq[CostDistance.Cost]] =
        accumulator.value
          .groupBy(_._1)
          .map({ case (k, list) => (k, list.map({ case (_, v) => v })) })
          .toMap
      val changes = sc.broadcast(_changes)
      logger.debug(s"At least ${changes.value.size} changed tiles")

      accumulator.reset

      val previous = costs

      costs = previous.map({ case (k, v, oldCostTile) =>
        val key = implicitly[SpatialKey](k)
        val frictionTile = implicitly[Tile](v)
        val keyCol = key._1
        val keyRow = key._2
        val frictionTileCols = frictionTile.cols
        val frictionTileRows = frictionTile.rows
        val localChanges: Option[Seq[CostDistance.Cost]] = changes.value.get(key)

        localChanges match {
          case Some(localChanges) => {
            val q: CostDistance.Q = {
              val q = CostDistance.generateEmptyQueue(frictionTileCols, frictionTileRows)
              localChanges.foreach({ (entry: CostDistance.Cost) => q.add(entry) })
              q
            }

            val newCostTile = CostDistance.compute(
              frictionTile, oldCostTile,
              maxCost, resolution,
              q, { (entry: CostDistance.Cost) =>
                val (col, row, f, c) = entry
                if (col == 0 && (minKeyCol <= keyCol-1))
                  accumulator.add((SpatialKey(keyCol-1, keyRow), (frictionTileCols, row, f, c)))
                if (row == frictionTileRows-1 && (keyRow+1 <= maxKeyRow))
                  accumulator.add((SpatialKey(keyCol, keyRow+1), (col, -1, f, c)))
                if (col == frictionTileCols-1 && (keyCol+1 <= maxKeyCol))
                  accumulator.add((SpatialKey(keyCol+1, keyRow), (-1, row, f, c)))
                if (row == 0 && (minKeyRow <= keyRow-1))
                  accumulator.add((SpatialKey(keyCol, keyRow-1)), (col, frictionTileRows, f, c))
              })

            // XXX It would be slightly more correct to include the four
            // diagonal tiles as well, but there would be at most a one
            // pixel contribution each, so it probably is not worth the
            // expense.

            (k, v, newCostTile)
          }
          case None => (k, v, oldCostTile)
        }
      }).persist(StorageLevel.MEMORY_AND_DISK_SER)

      costs.count
      previous.unpersist()
    } while (accumulator.value.size > 0)

    costs.map({ case (k, _, cost) => (k, cost) })
  }
}
