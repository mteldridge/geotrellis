/*
 * Copyright 2017 Astraea, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.spark.tiling

import geotrellis.raster._
import geotrellis.vector.Extent

/**
  * Layout scheme with specified layout extent.
  */
object AnchoredLayoutScheme {
  val DEFAULT_TILE_SIZE = 256

  def apply( anchoredExtent: Extent, cellSize: CellSize): AnchoredLayoutScheme =
    apply(DEFAULT_TILE_SIZE, anchoredExtent, cellSize)

  def apply(tileSize: Int, anchoredExtent: Extent, cellSize: CellSize): AnchoredLayoutScheme =
    apply(tileSize, tileSize, anchoredExtent, cellSize)

  def apply(tileCols: Int, tileRows: Int, anchoredExtent: Extent, cellSize: CellSize): AnchoredLayoutScheme =
    new AnchoredLayoutScheme(tileCols, tileRows, anchoredExtent, cellSize)
}

class AnchoredLayoutScheme(tileCols: Int, tileRows: Int, val anchoredExtent: Extent, val cellSize: CellSize)
  extends FloatingLayoutScheme(tileCols,tileRows) {

  /** @param extent is ignored, uses ''this.anchoredExtent''
    * @param cellSize is ignored.  uses ''this.cellSize''
    * */
  override def levelFor(extent: Extent, cellSize: CellSize) =
    0 -> LayoutDefinition(GridExtent(anchoredExtent, this.cellSize), tileCols, tileRows)

  override def zoomOut(level: LayoutLevel) =
    throw new UnsupportedOperationException("zoomOut not supported for FloatingLayoutScheme")

  override def zoomIn(level: LayoutLevel) =
    throw new UnsupportedOperationException("zoomIn not supported for FloatingLayoutScheme")
}



