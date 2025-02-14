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
package scalismo.vtk.registration

import breeze.linalg.DenseVector
import scalismo.common.interpolation.{
  BSplineImageInterpolator,
  BSplineImageInterpolator2D,
  BSplineImageInterpolator3D,
  NearestNeighborInterpolator
}
import scalismo.common.{EuclideanSpace2D, Field, PointId, RealSpace}
import scalismo.geometry.*
import scalismo.image.{DiscreteImageDomain2D, DiscreteImageDomain3D}
import scalismo.kernels.{DiagonalKernel, GaussianKernel}
import scalismo.numerics.{GridSampler, LBFGSOptimizer, UniformSampler}
import scalismo.registration.*
import scalismo.statisticalmodel.{GaussianProcess, LowRankGaussianProcess}
import scalismo.transformations.*
import scalismo.utils.Random
import scalismo.vtk.ScalismoTestSuite
import scalismo.vtk.io.{ImageIO, MeshIO}

import java.io.File
import java.net.URLDecoder
import scala.language.implicitConversions

class RegistrationTests extends ScalismoTestSuite {

  implicit val random: Random = Random(42)

  implicit def doubleToFloat(d: Double): Float = d.toFloat

  describe("A 2D image registration") {
    it("Recovers the correct parameters for a translation transform") {
      val testImgUrl = getClass.getResource("/dm128.vtk").getPath

      val discreteFixedImage = ImageIO.read2DScalarImage[Float](new File(URLDecoder.decode(testImgUrl, "UTF-8"))).get
      val fixedImage = discreteFixedImage.interpolateDifferentiable(BSplineImageInterpolator2D[Float](2))
      val transformationSpace = TranslationSpace2D
      val translationParams = DenseVector[Double](-10.0, 5.0)
      val translationTransform = transformationSpace.transformationForParameters(translationParams)
      val transformedLena = fixedImage compose translationTransform

      val domain = discreteFixedImage.domain

      val metricSampler = GridSampler(DiscreteImageDomain2D(domain.boundingBox, IntVector(20, 20)))
      val regIt = Registration(
        MeanSquaresMetric(fixedImage, transformedLena, transformationSpace, metricSampler),
        L2Regularizer[_2D](transformationSpace),
        regularizationWeight = 0.0,
        LBFGSOptimizer(maxNumberOfIterations = 300)
      ).iterator(DenseVector.zeros[Double](transformationSpace.numberOfParameters))

      val regResult = regIt.toSeq.last
      -regResult.parameters(0) should be(translationParams(0) +- 0.01)
      -regResult.parameters(1) should be(translationParams(1) +- 0.01)
    }

    it("Recovers the correct parameters for a rotation transform") {
      val testImgUrl = getClass.getResource("/dm128.vtk").getPath
      val discreteFixedImage = ImageIO.read2DScalarImage[Float](new File(URLDecoder.decode(testImgUrl, "UTF-8"))).get
      val fixedImage = discreteFixedImage.interpolateDifferentiable(BSplineImageInterpolator2D[Float](3))
      val domain = discreteFixedImage.domain
      val center = ((domain.boundingBox.oppositeCorner - domain.origin) * 0.5).toPoint
      val transformationSpace = RotationSpace2D(center)
      val rotationParams = DenseVector[Double](math.Pi / 8.0)
      val transform = transformationSpace.transformationForParameters(rotationParams)
      val transformedLena = fixedImage compose transform

      val metricSampler = GridSampler(DiscreteImageDomain2D(domain.boundingBox, IntVector(20, 20)))
      val metric = MeanSquaresMetric(transformedLena, fixedImage, transformationSpace, metricSampler)

      val regIter = Registration(
        metric,
        L2Regularizer(transformationSpace),
        0.0,
        LBFGSOptimizer(maxNumberOfIterations = 100)
      ).iterator(DenseVector.zeros[Double](transformationSpace.numberOfParameters))

      val regResult = regIter.toSeq.last
      regResult.parameters(0) should be(rotationParams(0) +- 0.01)
    }

    it("Recovers the correct parameters for a gp transform") {
      val testImgUrl = getClass.getResource("/dm128.vtk").getPath

      val discreteFixedImage = ImageIO.read2DScalarImage[Float](new File(URLDecoder.decode(testImgUrl, "UTF-8"))).get
      val fixedImage = discreteFixedImage.interpolateDifferentiable(BSplineImageInterpolator2D[Float](3))

      val domain = discreteFixedImage.domain
      val gp = GaussianProcess(Field(EuclideanSpace2D, (_: Point[_2D]) => EuclideanVector.zeros[_2D]),
                               DiagonalKernel(GaussianKernel[_2D](50.0) * 50.0, 2)
      )
      val sampler = UniformSampler(domain.boundingBox, numberOfPoints = 200)
      val lowRankGp = LowRankGaussianProcess.approximateGPNystrom(gp, sampler, numBasisFunctions = 3)
      val gpParams = DenseVector.ones[Double](lowRankGp.rank)
      val transformationSpace = GaussianProcessTransformationSpace(lowRankGp)

      val groundTruthTransform = transformationSpace.transformationForParameters(gpParams)

      val transformedLena = fixedImage compose groundTruthTransform

      val metricSampler = GridSampler(DiscreteImageDomain2D(domain.boundingBox, IntVector(20, 20)))
      val metric = MeanSquaresMetric(transformedLena, fixedImage, transformationSpace, metricSampler)

      val regIt =
        Registration(metric, L2Regularizer(transformationSpace), 0.0, LBFGSOptimizer(maxNumberOfIterations = 300))
          .iterator(DenseVector.zeros[Double](transformationSpace.numberOfParameters))

      val regResult = regIt.toSeq.last

      for (i <- 0 until regResult.parameters.size) {
        regResult.parameters(i) should be(gpParams(0) +- 0.1)
      }
    }

    it("Recovers the correct parameters for a gp transform with a nn interpolated gp") {
      val testImgUrl = getClass.getResource("/dm128.vtk").getPath

      val discreteFixedImage = ImageIO.read2DScalarImage[Float](new File(URLDecoder.decode(testImgUrl, "UTF-8"))).get
      val fixedImage = discreteFixedImage.interpolateDifferentiable(BSplineImageInterpolator2D[Float](3))

      val domain = discreteFixedImage.domain

      val gp = GaussianProcess(Field(EuclideanSpace2D, (_: Point[_2D]) => EuclideanVector.zeros[_2D]),
                               DiagonalKernel(GaussianKernel[_2D](50.0) * 50.0, 2)
      )
      val sampler = UniformSampler(domain.boundingBox, numberOfPoints = 200)
      val lowRankGp = LowRankGaussianProcess.approximateGPNystrom(gp, sampler, numBasisFunctions = 3)
      val nnInterpolatedGp = lowRankGp.discretize(domain).interpolate(NearestNeighborInterpolator())

      val transformationSpace = GaussianProcessTransformationSpace(nnInterpolatedGp)
      val gpParams = DenseVector.ones[Double](lowRankGp.rank)
      val groundTruthTransform = transformationSpace.transformationForParameters(gpParams)
      val transformedLena = fixedImage compose groundTruthTransform

      val metricSampler = GridSampler(DiscreteImageDomain2D(domain.boundingBox, IntVector(20, 20)))
      val metric = MeanSquaresMetric(transformedLena, fixedImage, transformationSpace, metricSampler)

      val regIt = Registration(
        metric,
        L2Regularizer(transformationSpace),
        regularizationWeight = 0.0,
        LBFGSOptimizer(maxNumberOfIterations = 300)
      ).iterator(DenseVector.zeros[Double](transformationSpace.numberOfParameters))

      val regResult = regIt.toSeq.last
      for (i <- 0 until regResult.parameters.size) {
        regResult.parameters(i) should be(gpParams(0) +- 0.1)
      }
    }

    it("Recovers the correct parameters for a composed rigid and gp transform") {
      val testImgUrl = getClass.getResource("/dm128.vtk").getPath

      val discreteFixedImage = ImageIO.read2DScalarImage[Float](new File(URLDecoder.decode(testImgUrl, "UTF-8"))).get
      val fixedImage = discreteFixedImage.interpolateDifferentiable(BSplineImageInterpolator(3))

      val domain = discreteFixedImage.domain

      val gp = GaussianProcess(Field(EuclideanSpace2D, (_: Point[_2D]) => EuclideanVector.zeros[_2D]),
                               DiagonalKernel(GaussianKernel[_2D](20.0) * 50.0, 2)
      )
      val lowRankGp =
        LowRankGaussianProcess.approximateGPCholesky(domain, gp, 0.1, NearestNeighborInterpolator()).truncate(5)
      val translationSpace = TranslationSpace2D
      val gpTransformationSpace = GaussianProcessTransformationSpace2D(lowRankGp)
      val transformationSpace =
        ProductTransformationSpace(
          translationSpace,
          gpTransformationSpace
        )
      val gtParams = DenseVector.vertcat(DenseVector.ones[Double](translationSpace.numberOfParameters) * 10.0,
                                         DenseVector.ones[Double](gpTransformationSpace.numberOfParameters) * 1.0
      )
      val groundTruthTransform = transformationSpace.transformationForParameters(gtParams)
      val transformedLena = fixedImage compose groundTruthTransform
      val metricSampler = GridSampler(DiscreteImageDomain2D(domain.boundingBox, IntVector(20, 20)))
      val metric = MeanSquaresMetric(transformedLena, fixedImage, transformationSpace, metricSampler)

      val regIt = Registration(
        metric,
        L2Regularizer(transformationSpace),
        regularizationWeight = 0.0,
        LBFGSOptimizer(maxNumberOfIterations = 300)
      ).iterator(DenseVector.zeros[Double](transformationSpace.numberOfParameters))
      val regItPrinting = for (it <- regIt) yield {
        println(it.value)
        println(it.parameters)
        it
      }

      val regResult = regItPrinting.toSeq.last
      for (i <- 0 until regResult.parameters.size) {
        regResult.parameters(i) should be(gtParams(i) +- 0.1)
      }
    }
  }

  describe("A 3D image registration") {
    val testImgUrl = getClass.getResource("/3ddm.nii").getPath
    val discreteFixedImage = ImageIO.read3DScalarImage[Float](new File(URLDecoder.decode(testImgUrl, "UTF-8"))).get
    val fixedImage = discreteFixedImage.interpolateDifferentiable(BSplineImageInterpolator3D[Float](3))

    val transformationSpace = TranslationSpace3D
    val domain = discreteFixedImage.domain

    it("Recovers the correct parameters for a translation transform") {

      val translationParams = DenseVector[Double](-10.0, 0.0, 0.0)
      val translationTransform = TranslationSpace3D.transformationForParameters(translationParams)
      val transformed = fixedImage compose translationTransform

      val metricSampler = GridSampler(DiscreteImageDomain3D(domain.boundingBox, IntVector(20, 20, 20)))
      val metric = MeanSquaresMetric(fixedImage, transformed, transformationSpace, metricSampler)

      val regIt = Registration(
        metric,
        L2Regularizer(transformationSpace),
        regularizationWeight = 0.0,
        LBFGSOptimizer(maxNumberOfIterations = 300)
      ).iterator(DenseVector.zeros[Double](transformationSpace.numberOfParameters))

      val regResult = regIt.toSeq.last

      -regResult.parameters(0) should be(translationParams(0) +- 0.01)
      -regResult.parameters(1) should be(translationParams(1) +- 0.01)
      -regResult.parameters(2) should be(translationParams(2) +- 0.01)
    }

  }
}
