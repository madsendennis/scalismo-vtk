/*
 * Copyright 2015 University of Basel, Graphics and Vision Research Group
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
package scalismo.vtk.io

import breeze.linalg.{DenseMatrix, DenseVector}
import niftijio.NiftiVolume
import scalismo.common.{PointId, Scalar, ScalarArray}
import scalismo.geometry.*
import scalismo.image.*
import scalismo.io.ImageIO.{readNifti, writeNifti}
import scalismo.io.ScalarDataType
import scalismo.transformations.Rotation3D
import scalismo.vtk.ScalismoTestSuite
import scalismo.vtk.io.ImageIOTests.ImageWithType
import scalismo.vtk.utils.CanConvertToVtk
import spire.math.{UByte, UInt, UShort}

import java.io.File
import java.net.URLDecoder
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

class ImageIOTests extends ScalismoTestSuite {

  def equalImages(img1: DiscreteImage[_3D, _], img2: DiscreteImage[_3D, _]): Boolean = {

    val valFlag = (0 until img1.values.size by img1.values.size / 1000).forall { i =>
      img1.data(i) == img2.data(i)
    }

    valFlag && ((img1.domain.origin - img2.domain.origin).norm < 0.01f) &&
    ((img1.domain.spacing - img2.domain.spacing).norm < 0.01f) && (img1.domain.size == img2.domain.size)
  }

  private class DataReadWrite[D: NDSpace: CanConvertToVtk] {

    val dim = implicitly[NDSpace[D]].dimensionality

    def typeAsString[T: Scalar](): String = {
      Scalar[T].scalarType match {
        case Scalar.ByteScalar   => "char"
        case Scalar.ShortScalar  => "short"
        case Scalar.IntScalar    => "int"
        case Scalar.FloatScalar  => "float"
        case Scalar.DoubleScalar => "double"
        case Scalar.UByteScalar  => "uchar"
        case Scalar.UShortScalar => "ushort"
        case Scalar.UIntScalar   => "uint"
        case _                   => throw new NotImplementedError("" + Scalar[T].scalarType)
      }
    }

    def readImage[T: Scalar: ClassTag](f: File): Try[DiscreteImage[D, T]] = {
      val r = if (dim == 2) ImageIO.read2DScalarImage[T](f) else ImageIO.read3DScalarImage[T](f)
      r.asInstanceOf[Try[DiscreteImage[D, T]]]
    }

    def testReadWrite[T: Scalar: ClassTag]() = {
      val path = getClass.getResource("/images/vtk").getPath
      val source = new File(s"${URLDecoder.decode(path, "UTF-8")}/${dim}d_${typeAsString[T]()}.vtk")

      // read
      val read = readImage[T](source)
      read match {
        case Failure(e) => e.printStackTrace()
        case Success(img) =>
          val doubles = img.data.map(v => implicitly[Scalar[T]].toDouble(v)).iterator.toArray
          (doubles.length, doubles.min, doubles.max) should equal((8, 42.0, 49.0))
        // println("vtk " + typeOf[T] + " " + dim+ " " + img.data.getClass + " " + img.data.deep)

      }
      read should be a Symbol("Success")

      // write out, and read again
      val vtk = File.createTempFile("imageio", ".vtk")
      vtk.deleteOnExit()
      ImageIO.writeVTK[D, T](read.get, vtk) should be a Symbol("Success")

      val reread = readImage[T](vtk)
      reread match {
        case Failure(e) => e.printStackTrace()
        case Success(img) =>
          val doubles = img.data.map(v => implicitly[Scalar[T]].toDouble(v)).iterator.toArray
          (doubles.length, doubles.min, doubles.max) should equal((8, 42.0, 49.0))
        // println("vtk " + typeOf[T] + " " + dim+ " " + img.data.getClass + " " + img.data.deep)
      }
      reread should be a Symbol("Success")
      vtk.delete()

      // if in 3D, write out as nifti and read again
      if (dim == 3) {
        val nii = File.createTempFile("imageio", ".nii")
        nii.deleteOnExit()
        writeNifti(read.get.asInstanceOf[DiscreteImage[_3D, T]], nii) should be a Symbol("Success")
        val reread = readNifti[T](nii)
        reread match {
          case Failure(e) => e.printStackTrace()
          case Success(img) =>
            val doubles = img.data.map(v => implicitly[Scalar[T]].toDouble(v)).iterator.toArray
            (doubles.length, doubles.min, doubles.max) should equal((8, 42.0, 49.0))
          // println("nii " + typeOf[T] + " " + dim+ " " + img.data.getClass + " " + img.data.deep)
        }
        nii.delete()
      }
    }

    def run() = {
      testReadWrite[Short]()
      testReadWrite[Int]()
      testReadWrite[Float]()
      testReadWrite[Double]()
      testReadWrite[Byte]()
      testReadWrite[UByte]()
      testReadWrite[UShort]()
      testReadWrite[UInt]()
    }
  }

  describe("A 2D scalar image") {
    it("can be read and written in various signed and unsigned VTK and Nifti data formats") {
      new DataReadWrite[_2D]().run()
    }

    it("can be converted to vtk and back and yields the same image") {
      val path = getClass.getResource("/lena.vtk").getPath
      val lena = ImageIO.read2DScalarImage[Short](new File(URLDecoder.decode(path, "UTF-8"))).get
      val tmpImgFile = File.createTempFile("image2D", ".vtk")
      tmpImgFile.deleteOnExit()
      ImageIO.writeVTK(lena, tmpImgFile) match {
        case Failure(ex) => throw new Exception(ex)
        case Success(_)  =>
      }
      val lenaFromVTK = ImageIO.read2DScalarImage[Short](tmpImgFile).get
      lena should equal(lenaFromVTK)
      tmpImgFile.delete()
    }
  }

  describe("A 3D scalar image") {

    it("can be read and written in various signed and unsigned VTK and Nifti data formats") {
      new DataReadWrite[_3D]().run()
    }

    it("can be stored to VTK and re-read in right precision") {
      val domain = DiscreteImageDomain3D(Point(-72.85742f, -72.85742f, -273.0f),
                                         EuclideanVector(0.85546875f, 0.85546875f, 1.5f),
                                         IntVector(15, 15, 15)
      )
      val values = DenseVector.zeros[Short](15 * 15 * 15).data
      val discreteImage = DiscreteImage(domain, ScalarArray(values))
      val f = File.createTempFile("dummy", ".vtk")
      f.deleteOnExit()
      ImageIO.writeVTK(discreteImage, f)
      val readImg = ImageIO.read3DScalarImage[Short](f).get

      readImg.data should equal(discreteImage.data)

      assert(equalImages(readImg, discreteImage))

    }

    it("can be converted to vtk and back and yields the same image") {
      val path = getClass.getResource("/3dimage.nii").getPath
      val discreteImage = ImageIO.read3DScalarImage[Short](new File(URLDecoder.decode(path, "UTF-8"))).get
      val f = File.createTempFile("dummy", ".vtk")
      f.deleteOnExit()
      ImageIO.writeVTK(discreteImage, f)
      val readImg = ImageIO.read3DScalarImage[Short](f).get

      assert(equalImages(readImg, discreteImage))
    }

  }

  describe("ImageIO") {
    it("is type safe") {

      def convertTo[D: NDSpace: CanConvertToVtk, OUT: Scalar: ClassTag](
        in: DiscreteImage[D, Int]
      ): ImageWithType[D, OUT] = {
        val img = in.map(implicitly[Scalar[OUT]].fromInt)
        ImageWithType(img, ScalarDataType.fromType[OUT].toString)
      }

      val data = (1 to 8).toArray
      val dom2 = DiscreteImageDomain2D(Point(0, 0), EuclideanVector(1, 1), IntVector(2, 2))
      val img2 = DiscreteImage(dom2, ScalarArray(data.take(4)))
      val dom3 = DiscreteImageDomain3D(Point(0, 0, 0), EuclideanVector(1, 1, 1), IntVector(2, 2, 2))
      val img3 = DiscreteImage(dom3, ScalarArray(data))

      def imageSeq[D: NDSpace: CanConvertToVtk](img: DiscreteImage[D, Int]) =
        Seq(
          convertTo[D, Byte](img),
          convertTo[D, Short](img),
          convertTo[D, Int](img),
          convertTo[D, Double](img),
          convertTo[D, Float](img),
          convertTo[D, UByte](img),
          convertTo[D, UShort](img),
          convertTo[D, UInt](img)
        )

      def read[D: NDSpace, T: Scalar: ClassTag](file: File): Try[DiscreteImage[D, T]] = {
        implicitly[NDSpace[D]].dimensionality match {
          case 3 => ImageIO.read3DScalarImage[T](file).asInstanceOf[Try[DiscreteImage[D, T]]]
          case 2 => ImageIO.read2DScalarImage[T](file).asInstanceOf[Try[DiscreteImage[D, T]]]
          case _ => Failure(new NotImplementedError())
        }
      }

      def check[D: NDSpace, T: Scalar: ClassTag](result: Try[DiscreteImage[D, T]], actualType: String): Unit = {
        val tryType = ScalarDataType.fromType[T].toString
        if (tryType == actualType) {
          result should be a Symbol("Success")
        } else {
          result should be a Symbol("Failure")
          result.failed.get.getMessage.contains(s"expected $tryType") should be(true)
          result.failed.get.getMessage.contains(s"found $actualType") should be(true)
        }
      }

      def eval[D: NDSpace](data: Seq[ImageWithType[D, _]]): Unit = {
        for (c <- data) {
          val vtk = File.createTempFile(c.typeName, ".vtk")
          vtk.deleteOnExit()

          def checkAll(file: File) = {
            check(read[D, Byte](file), c.typeName)
            check(read[D, Short](file), c.typeName)
            check(read[D, Int](file), c.typeName)
            check(read[D, Float](file), c.typeName)
            check(read[D, Double](file), c.typeName)
            check(read[D, UByte](file), c.typeName)
            check(read[D, UShort](file), c.typeName)
            check(read[D, UInt](file), c.typeName)
          }

          c.writeVtk(vtk) should be a Symbol("Success")
          ScalarDataTypeVTK.ofFile(vtk).get.toString should equal(c.typeName)

          checkAll(vtk)
          vtk.delete()

          if (implicitly[NDSpace[D]].dimensionality == 3) {
            val nii = File.createTempFile(c.typeName, ".nii")
            nii.deleteOnExit()

            c.writeNii(nii) should be a Symbol("Success")
            ScalarDataTypeVTK.ofFile(nii).get.toString should equal(c.typeName)
            checkAll(nii)
            nii.delete()
          }
        }
      }

      eval(imageSeq(img2))
      eval(imageSeq(img3))
    }
  }

}

object ImageIOTests {
  case class ImageWithType[D: NDSpace: CanConvertToVtk, T: Scalar: ClassTag](
    img: DiscreteImage[D, T],
    typeName: String
  ) {
    def writeVtk(file: File) = ImageIO.writeVTK(img, file)
    def writeNii(file: File) = {
      if (implicitly[NDSpace[D]].dimensionality == 3)
        writeNifti(img.asInstanceOf[DiscreteImage[_3D, T]], file)
      else Failure(new NotImplementedError)
    }
  }
}
