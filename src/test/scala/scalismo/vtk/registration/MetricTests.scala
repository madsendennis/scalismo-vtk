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
import scalismo.common.interpolation.BSplineImageInterpolator2D
import scalismo.common.{BoxDomain, DifferentiableField}
import scalismo.geometry.*
import scalismo.geometry.Point.implicits.*
import scalismo.image.{DiscreteImageDomain, DiscreteImageDomain2D, StructuredPoints}
import scalismo.numerics.{GridSampler, LBFGSOptimizer, UniformSampler}
import scalismo.registration.{L2Regularizer, MeanHuberLossMetric, MutualInformationMetric, Registration}
import scalismo.transformations.{TranslationSpace, TranslationSpace1D, TranslationSpace2D}
import scalismo.utils.Random
import scalismo.vtk.ScalismoTestSuite
import scalismo.vtk.io.ImageIO

import _root_.java.io.File
import java.net.URLDecoder

class MetricTests extends ScalismoTestSuite {

  implicit val rng: Random = Random(42L)

  describe("The mutual information metric") {
    val testImgURL = getClass.getResource("/dm128.vtk").getPath

    val fixedImage = ImageIO.read2DScalarImage[Float](new File(URLDecoder.decode(testImgURL, "UTF-8"))).get
    val fixedImageCont = fixedImage.interpolateDifferentiable(BSplineImageInterpolator2D[Float](3))
    val translationSpace = TranslationSpace2D
    val sampler = GridSampler(DiscreteImageDomain2D(fixedImage.domain.boundingBox, size = IntVector(50, 50)))

    it("has the global minimum where the images are similar") {

      val metric = MutualInformationMetric(fixedImageCont, fixedImage.domain, fixedImageCont, translationSpace, sampler)
      val zeroVec = DenseVector.zeros[Double](translationSpace.numberOfParameters)

      for (_ <- 0 until 10) {
        val params = DenseVector.rand(translationSpace.numberOfParameters, rng.breezeRandBasis.gaussian)
        metric.value(params) should be >= metric.value(zeroVec)
      }
    }

    it("goes to a lower value when following the (negative) gradient") {

      val metric = MutualInformationMetric(fixedImageCont, fixedImage.domain, fixedImageCont, translationSpace, sampler)
      for (_ <- 0 until 10) {
        val params = DenseVector.rand(translationSpace.numberOfParameters, rng.breezeRandBasis.gaussian)

        val origValue = metric.value(params)
        val grad = metric.derivative(params)

        metric.value(params - grad * 1e-5) should be < origValue
      }
    }

    it("recovers the parameters in a registration") {

      val trueParams = DenseVector.ones[Double](translationSpace.numberOfParameters)
      val movingImage = fixedImageCont.compose(translationSpace.transformationForParameters(-trueParams))

      val metric = MutualInformationMetric(fixedImageCont, fixedImage.domain, movingImage, translationSpace, sampler)

      val initialParameters = DenseVector.zeros[Double](translationSpace.numberOfParameters)
      val regIt =
        Registration(metric, L2Regularizer(translationSpace), 0.0, LBFGSOptimizer(20)).iterator(initialParameters)
      val finalParams = regIt.toIndexedSeq.last.parameters

      breeze.linalg.norm(finalParams - trueParams) should be < 1e-1
    }
  }

  describe("The huber loss metric") {
    val testImgURL = getClass.getResource("/dm128.vtk").getPath

    val fixedImage = ImageIO.read2DScalarImage[Float](new File(URLDecoder.decode(testImgURL, "UTF-8"))).get
    val fixedImageCont = fixedImage.interpolateDifferentiable(BSplineImageInterpolator2D[Float](3))
    val translationSpace = TranslationSpace2D
    val sampler = GridSampler(DiscreteImageDomain2D(fixedImage.domain.boundingBox, size = IntVector(50, 50)))

    it("has the global minimum where the images are similar") {

      val metric = MeanHuberLossMetric(fixedImageCont, fixedImageCont, translationSpace, sampler)
      val zeroVec = DenseVector.zeros[Double](translationSpace.numberOfParameters)

      for (_ <- 0 until 10) {
        val params = DenseVector.rand(translationSpace.numberOfParameters, rng.breezeRandBasis.gaussian)
        metric.value(params) should be >= metric.value(zeroVec)
      }
    }

    it("goes to a lower value when following the (negative) gradient") {

      val metric = MeanHuberLossMetric(fixedImageCont, fixedImageCont, translationSpace, sampler)
      for (_ <- 0 until 10) {
        val params = DenseVector.rand(translationSpace.numberOfParameters, rng.breezeRandBasis.gaussian)

        val origValue = metric.value(params)
        val grad = metric.derivative(params)

        metric.value(params - grad * 1e-1) should be < origValue
      }
    }

    it("recovers the parameters in a registration") {

      val trueParams = DenseVector.ones[Double](translationSpace.numberOfParameters) * 5.0
      val movingImage = fixedImageCont.compose(translationSpace.transformationForParameters(-trueParams))

      val metric = MeanHuberLossMetric(fixedImageCont, movingImage, translationSpace, sampler)

      val initialParameters = DenseVector.zeros[Double](translationSpace.numberOfParameters)
      val regIt =
        Registration(metric, L2Regularizer(translationSpace), 0.0, LBFGSOptimizer(20)).iterator(initialParameters)
      val regSteps = regIt.toIndexedSeq
      val finalParams = regSteps.last.parameters

      breeze.linalg.norm(finalParams - trueParams) should be < 1e-1
    }
  }

}
