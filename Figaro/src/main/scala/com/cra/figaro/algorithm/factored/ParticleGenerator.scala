/*
 * ParticleGenerator.scala
 * Class to handle sampling from continuous elements in PBP
 * 
 * Created By:      Brian Ruttenberg (bruttenberg@cra.com)
 * Creation Date:   Oct 8, 2014
 * 
 * Copyright 2014 Avrom J. Pfeffer and Charles River Analytics, Inc.
 * See http://www.cra.com or email figaro@cra.com for information.
 * 
 * See http://www.github.com/p2t2/figaro for a copy of the software license.
 */

package com.cra.figaro.algorithm.factored

import scala.collection.mutable.Map
import com.cra.figaro.algorithm.sampling.ElementSampler
import com.cra.figaro.language.Element
import com.cra.figaro.util._
import com.cra.figaro.language.Universe
import com.cra.figaro.language.Atomic
import com.cra.figaro.library.atomic.discrete.OneShifter
import com.cra.figaro.util.MapResampler

/**
 * Class to handle sampling from continuous elements in PBP
 * @param argSamples Maximum number of samples to take from atomic elements
 * @param totalSamples Maximum number of samples on the output of chains
 * @param de An instance to compute the density estimate of point during resampling
 */
class ParticleGenerator(de: DensityEstimator, val numArgSamples: Int, val numTotalSamples: Int) {

  // Caches the samples for an element
  private val sampleMap = Map[Element[_], (List[(Double, _)], Int)]()

  /**
   * Returns the set of sampled elements contained in this sampler
   */
  def sampledElements(): Set[Element[_]] = sampleMap.keySet.toSet

  /**
   * Clears all of the samples for elements in this sampler
   */
  def clear() = sampleMap.clear

  /**
   * Updates the samples for an element
   */
  def update(elem: Element[_], numSamples: Int, samples: List[(Double, _)]) = sampleMap.update(elem, (samples, numSamples))

  /**
   * Retrieves the samples for an element using the default number of samples.
   */
  def apply[T](elem: Element[T]): List[(Double, T)] = apply(elem, numArgSamples)

  /**
   * Retrieves the samples for an element using the indicated number of samples
   */
  def apply[T](elem: Element[T], numSamples: Int): List[(Double, T)] = {
    sampleMap.get(elem) match {
      case Some(e) => {
        e.asInstanceOf[(List[(Double, T)], Int)]._1
      }
      case None => {
        val sampler = ElementSampler(elem, numSamples)
        sampler.start
        val result = sampler.computeDistribution(elem).toList
        sampleMap += elem -> (result, numSamples)
        elem.universe.register(sampleMap)
        sampler.kill
        result
      }
    }
  }

  /**
   * Resample and update the element from the indicated beliefs
   * beliefs = (Probability, Value)
   */
  def resample(elem: Element[_], beliefs: List[(Double, _)], proposalVariance: Double): Unit = {

    def nextInt(i: Int) = if (random.nextBoolean) i + 1 else i - 1
    def nextDouble(d: Double) = random.nextGaussian() * proposalVariance + d

    val sampleDensity: Double = 1.0 / beliefs.size
    
    val numSamples = sampleMap(elem)._2

    val newSamples = elem match {
      case o: OneShifter => {
        val toResample = if (beliefs.size < numSamples) {
          val resampler = new MapResampler(beliefs.map(s => (s._1, s._2)))
          List.fill(numSamples)(1.0/numSamples, resampler.resample)
        } else {
          beliefs
        }
        toResample.map(b => {
          val oldValue = b._2.asInstanceOf[Int]
          val newValue = nextInt(oldValue)
          val nextValue = if (o.density(newValue) > 0.0) {
            accept(oldValue, newValue, beliefs.asInstanceOf[List[(Double, Int)]])
          } else oldValue
          (sampleDensity, nextValue)
        })
      }
      case a: Atomic[_] => { // The double is unchecked, bad stuff if the atomic is not double
        beliefs.map(b => {
          val oldValue = b._2.asInstanceOf[Double]
          val newValue = nextDouble(oldValue)
          val nextValue = if (a.asInstanceOf[Atomic[Double]].density(newValue) > 0.0) {
            accept(oldValue, newValue, beliefs.asInstanceOf[List[(Double, Double)]])
          } else oldValue
          (sampleDensity, nextValue)
        })
      }
      case _ => { // Not an atomic element, we don't know how to resample
        beliefs
      }
    }
    update(elem, numSamples, newSamples)
  }

  private def accept[T](oldValue: T, newValue: T, beliefs: List[(Double, T)]): T = {
    val oldDensity = de.getDensity(oldValue, beliefs)
    val newDensity = de.getDensity(newValue, beliefs)
    val ratio = newDensity / oldDensity

    val nextValue = if (ratio > 1) {
      newValue
    } else {
      if (random.nextDouble < ratio) newValue else oldValue
    }
    nextValue
  }
}

object ParticleGenerator {
  var defaultArgSamples = 20
  var defaultTotalSamples = 50

  private val samplerMap: Map[Universe, ParticleGenerator] = Map()

  def clear(univ: Universe) = samplerMap -= univ

  def clear() = samplerMap.clear

  def apply(univ: Universe, de: DensityEstimator, numArgSamples: Int, numTotalSamples: Int): ParticleGenerator =
    samplerMap.get(univ) match {
      case Some(e) => e
      case None => {
        samplerMap += (univ -> new ParticleGenerator(de, numArgSamples, numTotalSamples))
        univ.registerUniverse(samplerMap)
        samplerMap(univ)
      }
    }

  def apply(univ: Universe): ParticleGenerator = apply(univ, new ConstantDensityEstimator,
      defaultArgSamples, defaultTotalSamples)

  def exists(univ: Universe): Boolean = samplerMap.contains(univ)

}
