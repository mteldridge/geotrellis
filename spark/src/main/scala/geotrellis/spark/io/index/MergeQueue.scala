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

package geotrellis.spark.io.index

import scala.collection.TraversableOnce


object MergeQueue {

  def apply(ranges: TraversableOnce[(Long, Long)]): Seq[(Long, Long)] = {

    var merged: List[(Long, Long)] = Nil
    ranges.toSeq.sortBy(_._1).foreach(r => {
      merged = merged match {
        case a :: rest if r._1 - 1 <= a._2 => (a._1, a._2 max r._2) :: rest
        case _ => r :: merged
      }
    })
    merged.reverse
  }
}
