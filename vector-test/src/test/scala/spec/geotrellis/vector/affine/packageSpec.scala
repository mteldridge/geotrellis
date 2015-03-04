package geotrellis.vector

import geotrellis.vector.affine._
import geotrellis.testkit.vector._

import com.vividsolutions.jts.{geom=>jts}

import org.scalatest._

class AffineTransformationSpec extends FunSpec with Matchers {

  describe ("AffineTransformation") {

    it ("should reflect a polygon over a line from (0, 0) to a user specified point") {
      val p = Polygon(Line(Point(0,0), Point(0,10), Point(10,10), Point(10,0), Point(0,0)))
      val ref = p.reflect(1, 0)
      val res = Polygon(Line(Point(0,0), Point(0,-10), Point(10,-10), Point(10,0), Point(0,0)))
      ref.equals(res) should be (true)
    }

  }
}
