/*
 * Copyright 2016 Azavea
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

package geotrellis.spark.etl

import geotrellis.proj4.{LatLng, Sinusoidal}
import geotrellis.raster.{CellSize, CellType}
import geotrellis.raster.resample.NearestNeighbor
import geotrellis.spark.etl.config._
import geotrellis.spark.tiling.AnchoredLayoutScheme
import geotrellis.vector.Extent
import org.apache.spark.storage.StorageLevel
import org.scalatest._

class EtlSpec extends FunSuite {
  // Test that ETL module can be instantiated in convenient ways
  val profiles = List(
    AccumuloProfile("accumulo-name", "instance", "zookeepers", "user", "password"),
    CassandraProfile("name", "hosts", "user", "password"),
    HBaseProfile("hbase-name", "zookeepers", "master")
  )

  val input = Input(
    name = "test",
    format = "geotiff",
    cache = Some(StorageLevel.NONE),
    noData = Some(0d),
    backend = Backend(
      `type`  = HadoopType,
      profile = None,
      path    = HadoopPath("path")
    )
  )

  val output =
    Output(
      backend = Backend(
        `type` = AccumuloType,
        profile = Some(profiles.head),
        path = AccumuloPath("output")
      ),
      resampleMethod = NearestNeighbor,
      reprojectMethod = BufferedReproject,
      keyIndexMethod = IngestKeyIndexMethod("zorder"),
      tileSize = 256,
      pyramid = true,
      partitions = Some(100),
      layoutScheme = Some("zoomed"),
      layoutExtent = Some(Extent(1.2, 2.3, 3.4, 4.5)),
      crs = Some("EPSG:3857"),
      resolutionThreshold = Some(0.1),
      cellSize = Some(CellSize(256.2, 256.1)),
      cellType = Some(CellType.fromString("int8")),
      encoding = Some("geotiff"),
      breaks = Some("0:ffffe5ff;0.1:f7fcb9ff;0.2:d9f0a3ff;0.3:addd8eff;0.4:78c679ff;0.5:41ab5dff;0.6:238443ff;0.7:006837ff;1:004529ff"),
      maxZoom = Some(13)
    )

  val etlConf = new EtlConf(
    input  = input,
    output = output
  )

  Etl(etlConf)
  Etl(etlConf, List(s3.S3Module, hadoop.HadoopModule))

  test("Can create Etl object given anchored layout and a specified layout extent") {

    val etl = Etl(new EtlConf(
      input = input,
      output = output.copy(layoutScheme = Some("anchored"), maxZoom = None)
    ))

    assert(etl.output.layoutScheme.isDefined)
    assert(etl.output.getLayoutScheme.isInstanceOf[AnchoredLayoutScheme])
  }

  test("Can't create ETL object without proper requirements (layoutExtent, cellSize, no maxZoom)") {
    intercept[Exception] {
      Etl(new EtlConf(
        input = input,
        output = output.copy(layoutScheme = Some("anchored"), layoutExtent = None, cellSize = None)
      ))
      Etl(new EtlConf(
        input = input,
        output = output.copy(layoutScheme = Some("anchored"), cellSize = None)
      ))
      Etl(new EtlConf(
        input = input,
        output = output.copy(layoutScheme = Some("anchored"), layoutExtent = None)
      ))
      Etl(new EtlConf(
        input = input,
        output = output.copy(layoutScheme = Some("anchored"))
      ))
    }
  }

  test("OutputPlugin.getCrs should handle proj4 strings") {
    assert(output.copy(crs = Some("EPSG:4326")).getCrs === Some(LatLng))
    assert(output.copy(crs = Some(Sinusoidal.toProj4String)).getCrs === Some(Sinusoidal))
    assert(output.copy(crs = None).getCrs === None)
    intercept[Exception] {
      output.copy(crs = Some("BAD:CRS")).getCrs
    }
  }
}
